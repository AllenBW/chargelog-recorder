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
import io.github.allenbw.chargelog.data.Replay
import io.github.allenbw.chargelog.measure.GaugeProfiles
import io.github.allenbw.chargelog.measure.GaugeScaleProbe
import io.github.allenbw.chargelog.measure.sessionFacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val mainHandler = Handler(Looper.getMainLooper())

    private var tickMs: Long = CapturePrefs.DEFAULT_SAMPLE_INTERVAL_S * 1000L

    // TRAP docs/recording-service-threading.md — the pump is the only writer of machine/writer/wakeLock
    private val inputs = Channel<CaptureInput>(Channel.UNLIMITED)
    private var inputPump: Job? = null
    private var tickJob: Job? = null

    // TRAP docs/recording-service-threading.md — main-thread only; closes the orphan-ticker window
    private var plugged = false
    private lateinit var machine: SessionStateMachine
    private lateinit var snapshots: BatterySnapshots
    private lateinit var writer: RawLogWriter
    private lateinit var eventLog: EventLog
    private lateinit var hinge: HingeMonitor
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var screenOn: Boolean? = null
    @Volatile private var thermalStatus: Int? = null

    @Volatile private var lastChargingStatus: Int? = null

    @Volatile private var samplingMode = SamplingMode.TICK

    @Volatile private var lastRecap: SessionRecap? = null

    private lateinit var host: RecorderHost

    @Volatile private var lastNotifiedText: String? = null
    @Volatile private var lastNotifiedAtE: Long = 0

    @Volatile private var lastNotifiedChannelId: String? = null
    @Volatile private var currentNotificationId: Int = NotificationIds.PRIMARY

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
                    samplingMode = SamplingMode.TICK
                    submit(CaptureInput.PowerDisconnected(t, e))
                    updateNotification(host.content(idleState()), force = true)
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
                        updateNotification(host.content(recordingState(s)))
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
        machine = SessionStateMachine(
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
        writer = RawLogWriter(logDir(this))
        eventLog = EventLog(logDir(this))
        screenOn = pm.isInteractive
        pm.addThermalStatusListener(mainExecutor, thermalListener)

        inputPump = scope.launch {
            for (input in inputs) {
                try {
                    execute(machine.on(input))
                } catch (e: IOException) {
                    Log.w(TAG, "capture effect failed", e)
                }
            }
        }

        createChannel()
        currentNotificationId = NotificationIds.PRIMARY
        startForeground(
            currentNotificationId,
            host.build(this, host.content(idleState()).copy(notificationId = currentNotificationId)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

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

        scope.launch {
            CapturePrefs.recordEnabledFlow(this@RecordingService).drop(1).collect {
                if (LiveFeed.state.value?.recording != true) {
                    updateNotification(host.content(idleState()), force = true)
                }
            }
        }

        scope.launch(Dispatchers.IO) {
            RawLogWriter.sessionFiles(logDir(this@RecordingService))
                .lastOrNull()?.let { lastRecap = recapOf(it) }
            if (LiveFeed.state.value?.recording != true) {
                updateNotification(host.content(idleState()), force = true)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        val ticks = stopTicking()
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
        submit(CaptureInput.PowerConnected(t, e, host.chargeTargetLevel(), snapshots.lastSticky?.cycleCount))
        samplingMode = SamplingMode.TICK
        plugged = true
        startTicking()
    }

    private fun refreshSticky(): Intent? =
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.also(snapshots::onBatteryChanged)

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            var first = true
            while (isActive) {
                val s = snapshots.sample(screenOn, hinge.latestDeg).copy(thermalStatus = thermalStatus)
                submit(CaptureInput.Tick(s))
                updateNotification(host.content(recordingState(s)), force = first)
                first = false
                delay(tickMs)
            }
        }
    }

    private fun stopTicking(): Job? {
        val job = tickJob
        tickJob = null
        job?.cancel()
        return job
    }

    private var gaugeProbe: GaugeScaleProbe? = null

    private fun onProbeSample(sample: RawLine.Sample) {
        val refined = gaugeProbe?.offer(sample.currentRaw) ?: return
        CapturePrefs.setLearnedGaugeId(this, refined.id)
        machine.refineGauge(refined)
        try {
            writer.append(RawLine.Event(
                sample.t, sample.e, EventKinds.GAUGE_SCALE,
                EventKinds.GAUGE_SCALE_DETAIL_KEY + refined.id,
            ))
        } catch (e: IOException) {
            Log.w(TAG, "gauge-scale marker not written", e)
        }
        host.onGaugeRefined(refined)
    }

    private fun execute(effects: List<CaptureEffect>) {
        for (fx in effects) when (fx) {
            is CaptureEffect.OpenLog -> {
                writer.open(fx.header)
                LiveFeed.onOpen(fx.header.sessionStartWallClockMs)
                gaugeProbe = GaugeScaleProbe(GaugeProfiles.byId(fx.header.gaugeProfileId) ?: host.gaugeProfile())
            }
            is CaptureEffect.Append -> {
                writer.append(fx.line)
                (fx.line as? RawLine.Sample)?.let { sample ->
                    LiveFeed.onSample(sample)
                    onProbeSample(sample)
                }
            }
            is CaptureEffect.CloseLog -> {
                val closedFile = writer.currentFile
                writer.close()
                LiveFeed.onClose()
                if (closedFile != null) {
                    scope.launch(Dispatchers.IO) {
                        lastRecap = recapOf(closedFile)
                        updateNotification(host.content(idleState()), force = true)
                    }
                }
            }
            is CaptureEffect.LogEvent -> eventLog.append(fx.event)
            is CaptureEffect.SetSampling -> {
                samplingMode = fx.mode
                mainHandler.post {
                    if (fx.mode == SamplingMode.EVENT) stopTicking() else if (plugged) startTicking()
                }
            }
            CaptureEffect.AcquireWakeLock -> {
                val pm = getSystemService(PowerManager::class.java)
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chargelog:recording")
                    .apply { setReferenceCounted(false); acquire(MAX_WAKELOCK_MS) }
            }
            CaptureEffect.ReleaseWakeLock -> {
                wakeLock?.release()
                wakeLock = null
            }
        }
    }

    private fun recapOf(file: File): SessionRecap? {
        val parsed = Replay.parse(file) ?: return null
        val endedAt = parsed.session.endedAtMs ?: parsed.samples.lastOrNull()?.wallClockMs ?: return null
        val facts = sessionFacts(parsed.session, parsed.samples)
        return SessionRecap(
            endedAtMs = endedAt,
            durationMs = facts.durationMs,
            startLevel = facts.startLevel,
            endLevel = facts.endLevel,
            energyAh = facts.energyAh,
            peakW = facts.peakW,
            source = facts.source,
            endReason = parsed.session.endReason,
            sessionId = parsed.session.id,
        )
    }

    private fun idleState(): RecorderState.Idle =
        RecorderState.Idle(recap = lastRecap, recordEnabled = CapturePrefs.recordEnabled(this))

    private fun recordingState(sample: RawLine.Sample): RecorderState.Recording {
        val snap = LiveFeed.state.value
        return RecorderState.Recording(
            sample = sample,
            sessionStartMs = if (snap?.recording == true) snap.sessionStartMs else null,
            lastRecap = lastRecap,
            recentLevels = snap?.recentLevels ?: emptyList(),
        )
    }

    private fun createChannel() = NotificationChannels.ensure(this)

    private fun updateNotification(content: HostContent, force: Boolean = false) {
        val key = content.dedupeKey
        if (!force && (key == lastNotifiedText || SystemClock.elapsedRealtime() - lastNotifiedAtE < 5_000)) return
        lastNotifiedText = key
        lastNotifiedAtE = SystemClock.elapsedRealtime()
        val channelChanged = lastNotifiedChannelId != null && lastNotifiedChannelId != content.channelId
        lastNotifiedChannelId = content.channelId
        val previousId = currentNotificationId
        val id = NotificationIds.next(previousId, channelChanged)
        currentNotificationId = id
        val built = host.build(this, content.copy(notificationId = id))
        // TRAP docs/traps/foreground-notification-ids.md — swap ids, never stopForeground
        if (channelChanged) {
            startForeground(id, built, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            getSystemService(NotificationManager::class.java).cancel(previousId)
        } else {
            getSystemService(NotificationManager::class.java).notify(id, built)
        }
    }
}
