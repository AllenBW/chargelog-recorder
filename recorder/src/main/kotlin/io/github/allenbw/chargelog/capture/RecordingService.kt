// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.Service
import android.util.Log
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import io.github.allenbw.chargelog.capture.log.EventKinds
import io.github.allenbw.chargelog.capture.log.EventLog
import io.github.allenbw.chargelog.capture.log.RawLine
import io.github.allenbw.chargelog.capture.log.RawLogWriter
import io.github.allenbw.chargelog.data.LogLayout
import io.github.allenbw.chargelog.measure.GaugeProfile
import io.github.allenbw.chargelog.measure.GaugeProfiles
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

class RecordingService : Service() {

    companion object {
        /** The foreground notification's id, exposed so a host can attach an ongoing activity to
         *  the notification it builds. */
        const val NOTIFICATION_ID = NotificationIds.PRIMARY

        private const val TEARDOWN_DRAIN_MS = 2_000L

        private const val MAX_WAKELOCK_MS = 3 * 3600 * 1000L

        private const val TAG = "ChargeLogRecorder"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RecordingService::class.java))
        }

        fun logDir(context: Context): File = LogLayout.ownDir(context)
    }

    // TRAP docs/recording-service-threading.md — an Exception ends one coroutine, an Error the process
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
        if (e !is Exception) throw e
        Log.w(TAG, "capture coroutine failed", e)
    })

    private val mainHandler = Handler(Looper.getMainLooper())

    private var tickMs: Long = CapturePrefs.DEFAULT_SAMPLE_INTERVAL_S * 1000L

    // TRAP docs/recording-service-threading.md — the pump alone runs effects: machine, log writer, wakeLock
    private val inputs = Channel<CaptureInput>(Channel.UNLIMITED)
    private var inputPump: Job? = null
    private var tickJob: Job? = null
    private var quietJob: Job? = null

    // TRAP docs/recording-service-threading.md — written on main only; the guarded posts read it in the poster's lock
    @Volatile private var plugged = false
    private lateinit var snapshots: BatterySnapshots
    private lateinit var effects: EffectRunner
    private lateinit var eventLog: EventLog
    private lateinit var hinge: HingeMonitor
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var screenOn: Boolean? = null
    @Volatile private var thermalStatus: Int? = null

    @Volatile private var lastChargingStatus: Int? = null

    @Volatile private var samplingMode = SamplingMode.TICK

    private val recaps = NewestRecap()

    private var switchWatch: (() -> Unit)? = null

    private lateinit var host: RecorderHost

    private val poster = ForegroundPoster(
        build = { c: HostContent -> host.build(this, c) },
        notify = { id, n -> getSystemService(NotificationManager::class.java).notify(id, n) },
        promote = { id, n -> startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) },
        cancel = { id -> getSystemService(NotificationManager::class.java).cancel(id) },
        clock = SystemClock::elapsedRealtime,
    )

    private val quiet = QuietWatch(
        clock = SystemClock::uptimeMillis,
        repost = { LiveFeed.state.value?.sample?.let { poster.post(host.content(recordingState(it))) } },
        rearm = {
            Log.w(TAG, "tick loop gone while sampling on the tick; re-arming")
            startTicking()
        },
    )

    private val ports = object : EffectPorts {
        override fun appendEvent(event: RawLine.Event) = eventLog.append(event)

        override fun acquireWakeLock() {
            releaseWakeLock()
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chargelog:recording")
                .apply { setReferenceCounted(false); acquire(MAX_WAKELOCK_MS) }
        }

        override fun releaseWakeLock() {
            val held = wakeLock
            wakeLock = null
            held?.release()
        }

        override fun onSampling(mode: SamplingMode) {
            samplingMode = mode
            val event = mode == SamplingMode.EVENT
            mainHandler.post { if (event) stopTicking() else if (plugged) startTicking() }
        }

        override fun onClosed(file: File) {
            val rank = recaps.closing()
            scope.launch(Dispatchers.IO) { postIdle(recaps.offer(rank, recapOf(file))) }
        }

        override fun fallbackGauge(): GaugeProfile = host.gaugeProfile()

        override fun onGaugeLearned(profile: GaugeProfile) {
            CapturePrefs.setLearnedGaugeId(this@RecordingService, profile.id)
            host.onGaugeRefined(profile)
        }

        override fun beat() = quiet.beat()
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        thermalStatus = status
        submit(CaptureInput.Observed(RawLine.Event(
            System.currentTimeMillis(), SystemClock.elapsedRealtime(),
            EventKinds.THERMAL, "status=$status")))
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val t = System.currentTimeMillis()
            val e = SystemClock.elapsedRealtime()
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    try {
                        eventLog.append(RawLine.Event(t, e, EventKinds.POWER_CONNECTED))
                    } catch (ioe: IOException) {
                        Log.w(TAG, "plug marker not written", ioe)
                    }
                    refreshSticky()
                    openSessionIfEnabled(t, e)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    plugged = false
                    stopTicking()
                    quietJob?.cancel()
                    samplingMode = SamplingMode.TICK
                    submit(CaptureInput.PowerDisconnected(t, e))
                    postIdle(recaps.current)
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    snapshots.onBatteryChanged(intent)
                    snapshots.lastSticky?.chargingStatus?.let { status ->
                        if (status != lastChargingStatus) {
                            lastChargingStatus = status
                            submit(CaptureInput.Observed(RawLine.Event(
                                System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                                EventKinds.CHARGING_STATUS, "status=$status")))
                        }
                    }
                    if (samplingMode == SamplingMode.EVENT && LiveFeed.state.value?.recording == true) {
                        val s = snapshots.sample(screenOn, hinge.latestDeg).copy(thermalStatus = thermalStatus)
                        submit(CaptureInput.Tick(s))
                        poster.post(host.content(recordingState(s)))
                    }
                    snapshots.lastSticky?.let { host.onBatteryState(it.level, (it.plugged ?: 0) > 0, screenOn) }
                }
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_SCREEN_OFF -> screenOn = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        host = applicationContext as? RecorderHost ?: error("Application must implement RecorderHost")
        tickMs = CapturePrefs.sampleIntervalS(this) * 1000L
        val pm = getSystemService(PowerManager::class.java)
        hinge = HingeMonitor(getSystemService(SensorManager::class.java)) { deg ->
            submit(CaptureInput.Observed(RawLine.Event(
                System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                EventKinds.HINGE, "deg=$deg")))
        }
        val bm = getSystemService(BatteryManager::class.java)
        val reportsCurrent = Sentinels.longOrNull(bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)) != null
        val reportsCounter = Sentinels.longOrNull(bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)) != null
        val reportsChargingStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.hasExtra(BatterySnapshots.EXTRA_CHARGING_STATUS)
        val gauge = GaugeProfiles.byId(CapturePrefs.learnedGaugeId(this)) ?: host.gaugeProfile()
        val machine = SessionStateMachine(
            SamplerProfiles.forHost(
                deviceKind = host.deviceKind,
                gauge = gauge,
                tickMs = tickMs,
                deviceModel = Build.MODEL,
                osRelease = Build.VERSION.RELEASE,
                appVersion = host.appVersion,
                deviceId = DeviceIdentity.id(this),
                reportsCurrent = reportsCurrent,
                reportsCounter = reportsCounter,
                hasHinge = hinge.available,
                hasThermal = true,
                socModel = Build.SOC_MODEL.takeUnless { it.isBlank() || it == Build.UNKNOWN },
                totalMemBytes = ActivityManager.MemoryInfo()
                    .also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
                    .totalMem.takeIf { it > 0L },
                designCapacityMah = host.designCapacityMah(),
                reportsChargingStatus = reportsChargingStatus,
            ),
        )
        snapshots = BatterySnapshots(bm)
        eventLog = EventLog(logDir(this))
        effects = EffectRunner(machine, RawLogWriter(logDir(this)), ports) { fx, e ->
            Log.w(TAG, "capture effect failed: ${fx?.javaClass?.simpleName ?: "input"}", e)
        }
        screenOn = pm.isInteractive
        pm.addThermalStatusListener(mainExecutor, thermalListener)

        inputPump = scope.launch {
            for (input in inputs) effects.handle(input)
        }

        NotificationChannels.ensure(this)
        // TRAP docs/recording-service-threading.md — watched before the first post reads the switch, on main
        switchWatch = CapturePrefs.watchRecordEnabled(this) {
            scope.launch(Dispatchers.IO) { postIdle(recaps.current) }
        }
        poster.start(host.content(idleState()))

        registerReceiver(powerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }, Context.RECEIVER_NOT_EXPORTED)

        hinge.start()
        try {
            eventLog.append(RawLine.Event(
                System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                EventKinds.SERVICE_START, "hinge=${hinge.selectedSensorName ?: "absent"}"))
        } catch (e: IOException) {
            Log.w(TAG, "service-start marker not written", e)
        }

        val sticky = refreshSticky()
        if (sticky != null) {
            val pluggedExtra = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            if (pluggedExtra > 0) {
                openSessionIfEnabled(System.currentTimeMillis(), SystemClock.elapsedRealtime())
            }
        }

        scope.launch(Dispatchers.IO) {
            val previous = recaps.previousLog(logDir(this@RecordingService))
            postIdle(recaps.offer(NewestRecap.STARTUP, previous?.let(::recapOf)))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        poster.close()
        switchWatch?.invoke()
        val ticks = stopTicking()
        quietJob?.cancel()
        getSystemService(PowerManager::class.java).removeThermalStatusListener(thermalListener)
        unregisterReceiver(powerReceiver)
        hinge.stop()

        runBlocking {
            withTimeoutOrNull(TEARDOWN_DRAIN_MS) {
                ticks?.join()
                submit(CaptureInput.ServiceStopping(
                    System.currentTimeMillis(), SystemClock.elapsedRealtime()))
                inputs.close()
                inputPump?.join()
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun submit(input: CaptureInput) {
        inputs.trySend(input)
    }

    private fun openSessionIfEnabled(t: Long, e: Long) {
        if (!CapturePrefs.recordEnabled(this)) return
        recaps.opening(t)
        submit(CaptureInput.PowerConnected(t, e, host.chargeTargetLevel(), snapshots.lastSticky?.cycleCount))
        samplingMode = SamplingMode.TICK
        plugged = true
        startTicking()
        watchQuiet()
    }

    private fun refreshSticky(): Intent? =
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.also(snapshots::onBatteryChanged)

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        quiet.beat()
        tickJob = scope.launch {
            var first = true
            while (isActive) {
                val s = snapshots.sample(screenOn, hinge.latestDeg).copy(thermalStatus = thermalStatus)
                submit(CaptureInput.Tick(s))
                poster.post(host.content(recordingState(s)), force = first, superseded = { !plugged })
                first = false
                delay(tickMs)
            }
        }
    }

    private fun watchQuiet() {
        quietJob?.cancel()
        // TRAP docs/recording-service-threading.md — on main, and outlives stopTicking, or a lost resume goes unseen
        quietJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(QuietWatch.PERIOD_MS)
                quiet.check(plugged && LiveFeed.state.value?.recording == true, samplingMode, tickJob?.isActive == true)
            }
        }
    }

    private fun stopTicking(): Job? {
        val job = tickJob
        tickJob = null
        job?.cancel()
        return job
    }

    // TRAP docs/recording-service-threading.md — dropped once a session opens, a recap is newer or the switch moved
    private fun postIdle(read: NewestRecap.Read) {
        val idle = idleState(read.recap)
        poster.post(host.content(idle), force = true, superseded = {
            plugged || !recaps.isNewest(read) || idle.recordEnabled != CapturePrefs.recordEnabled(this)
        })
    }

    private fun idleState(recap: SessionRecap? = recaps.current.recap): RecorderState.Idle =
        RecorderState.Idle(recap = recap, recordEnabled = CapturePrefs.recordEnabled(this))

    private fun recordingState(sample: RawLine.Sample): RecorderState.Recording {
        val snap = LiveFeed.state.value
        return RecorderState.Recording(
            sample = sample,
            sessionStartMs = if (snap?.recording == true) snap.sessionStartMs else null,
            lastRecap = recaps.current.recap,
            recentLevels = snap?.recentLevels ?: emptyList(),
        )
    }
}
