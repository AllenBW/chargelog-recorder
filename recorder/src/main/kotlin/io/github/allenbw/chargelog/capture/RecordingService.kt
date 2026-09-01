// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import android.app.NotificationManager
import android.app.Service
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

class RecordingService : Service() {

    companion object {
        /** The foreground notification's id, exposed so a host can attach an ongoing activity to
         *  the notification it builds. */
        const val NOTIFICATION_ID = 1

        /**
         * Upper bound on how long onDestroy blocks draining the input pump.
         * Every append is flushed, so a drain that times out costs the
         * session's `session_end` line (replay marks it TRUNCATED), never
         * already-written data — and an ANR here would cost far more.
         */
        private const val TEARDOWN_DRAIN_MS = 2_000L

        /**
         * Safety-net timeout on the recording wake lock,
         * sized to a maximum plausible session. The settle detector is what
         * normally releases it; this bounds the damage if a session somehow
         * never settles and never sees its unplug.
         */
        private const val MAX_WAKELOCK_MS = 3 * 3600 * 1000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RecordingService::class.java))
        }

        fun logDir(context: Context): File = LogLayout.ownDir(context)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Keeps [tickJob] main-confined. Every other caller that starts or stops
     * the ticker — [openSessionIfEnabled], the unplug branch of
     * [powerReceiver], [onDestroy] — already runs on the main thread, and
     * [startTicking] is a check-then-act on that field. The pump runs on
     * `Dispatchers.Default`, so its `SetSampling` effect posts here rather
     * than touching [tickJob] itself: without that hop there is no
     * happens-before edge between the two, and a resumed ticker could survive
     * an unplug while the next plug launched a second loop beside it.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Sampling cadence in milliseconds, read once from
     * [CapturePrefs.sampleIntervalS] in [onCreate] and held for the rest of
     * the service's life. Feeds both the tick loop's `delay` in
     * [startTicking] and [SamplerProfile.tickMs] — which [SessionStateMachine]
     * passes straight through to `SampleGate.offer`'s gap-detection math — so
     * a value that changed mid-session would corrupt the sample-gap
     * invariants the pump depends on. A changed setting therefore takes
     * effect on the *next* service start, never the current one; this field
     * is deliberately never re-read after [onCreate]. Defaults to
     * `CapturePrefs.DEFAULT_SAMPLE_INTERVAL_S * 1000` = 1000ms, matching the
     * ticker's previous hardcoded cadence before this setting existed.
     */
    private var tickMs: Long = CapturePrefs.DEFAULT_SAMPLE_INTERVAL_S * 1000L

    /**
     * The one serialization domain for capture state. Inputs arrive from the
     * main thread (broadcast receiver, thermal listener, hinge callback,
     * service lifecycle) and from the tick loop on a `Dispatchers.Default`
     * worker; producers only enqueue, and [inputPump] — a single coroutine,
     * so it processes one input at a time — is the ONLY code that touches
     * [machine], [writer] or [wakeLock]. Unbounded, so `trySend` from a
     * broadcast receiver neither blocks nor drops.
     *
     * Before this, the receiver and the tick loop both ran `machine.on()` +
     * `execute()` unsynchronized, which crashed the process twice during a
     * 50-trial stress run (`IOException: Stream closed` — a `close()` landing
     * mid-`append()`).
     */
    private val inputs = Channel<CaptureInput>(Channel.UNLIMITED)
    private var inputPump: Job? = null
    private var tickJob: Job? = null

    /**
     * Whether a charger is currently attached, as the two power branches saw it. MAIN THREAD ONLY
     * — a plain `var`, not `@Volatile`, because every write ([openSessionIfEnabled], the
     * `ACTION_POWER_DISCONNECTED` branch) and every read (the `SetSampling` runnable posted to
     * [mainHandler]) is on the main thread, exactly like [tickJob] itself.
     *
     * It exists to close the orphan-ticker window: the pump's `SetSampling`
     * effect posts its start/stop, so a resume posted just before an unplug can land just after
     * that unplug's [stopTicking], restarting the loop while the phone sits unplugged. The loop
     * writes nothing and holds no lock, but it does call `updateNotification(host.content(...))`
     * every tick — so an orphan replaces the idle notification with live-recording text until the
     * next plug event. Checking this flag at post-run time is the happens-before-free guard.
     */
    private var plugged = false
    private lateinit var machine: SessionStateMachine
    private lateinit var snapshots: BatterySnapshots
    private lateinit var writer: RawLogWriter
    private lateinit var eventLog: EventLog
    private lateinit var hinge: HingeMonitor
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var screenOn: Boolean? = null
    @Volatile private var thermalStatus: Int? = null

    /**
     * Which cadence the recorder is currently on. Decided
     * by [SessionStateMachine] — the pump writes it from `SetSampling`, the
     * broadcast receiver reads it on the main thread to know whether a
     * `BATTERY_CHANGED` is itself the sample. `@Volatile` is the publication,
     * same as [screenOn] above; the detector that drives it stays inside the
     * machine, so nothing outside the pump ever decides this.
     */
    @Volatile private var samplingMode = SamplingMode.TICK

    /**
     * The most recently finished session's recap, for the idle notification.
     * Written from [recapOf] on [Dispatchers.IO] — either from the
     * pump's `CloseLog` branch (capturing the file before `writer.close()`
     * nulls it) or from the [onCreate] reseed — and read from the main
     * thread by [idleState]. `@Volatile` is the publication: no other
     * synchronization needed since it's a single reference swap.
     */
    @Volatile private var lastRecap: SessionRecap? = null

    /**
     * The application that hosts this recorder — every user-visible string, icon, deep link and
     * preference the notification needs, plus the charge target and the battery states a product
     * alert may want. Resolved once in [onCreate] and never reassigned; the cast fails fast, at
     * service start, if the host application does not implement [RecorderHost].
     */
    private lateinit var host: RecorderHost

    /**
     * Cadence gate state. [updateNotification] runs from three
     * threads — the receiver (main), the tick loop (`Dispatchers.Default`),
     * and the recap/reseed IO callbacks — same as [RecorderHost.build] always
     * has; `@Volatile` only buys cross-thread visibility, matching [lastRecap]
     * above. A lost race between concurrent callers costs at most one extra
     * or skipped `notify()`, never a crash or stuck notification.
     */
    @Volatile private var lastNotifiedText: String? = null
    @Volatile private var lastNotifiedAtE: Long = 0

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
                    // Stays a direct write: this line is the rolling log's
                    // ground-truth plug marker and must
                    // land in events.ndjson whatever the session state is.
                    eventLog.append(RawLine.Event(t, e, EventKinds.POWER_CONNECTED))
                    openSessionIfEnabled(t, e)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    plugged = false
                    stopTicking()
                    samplingMode = SamplingMode.TICK
                    submit(CaptureInput.PowerDisconnected(t, e))
                    // Shows whatever recap is currently known (the just-ended
                    // session's recap lands shortly after via the pump's
                    // CloseLog branch, forced through once parsed). Forced:
                    // this is a state transition, not routine churn.
                    updateNotification(host.content(idleState()), force = true)
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    snapshots.onBatteryChanged(intent)
                    // Settled: no ticker, no wake lock — every gauge-driven
                    // BATTERY_CHANGED is the sample. Same pump input as a tick, same gate.
                    if (samplingMode == SamplingMode.EVENT && LiveFeed.state.value?.recording == true) {
                        val s = snapshots.sample(screenOn, hinge.latestDeg).copy(thermalStatus = thermalStatus)
                        submit(CaptureInput.Tick(s))
                        updateNotification(host.content(recordingState(s)))
                    }
                    // Plug state rides on this same broadcast (re-sent the moment the cable
                    // changes), so reporting every one of them is all a host needs to fire, clear
                    // on plug, and re-arm its own alerts. The recorder decides nothing here.
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
        // Read once — see the KDoc on [tickMs] for why this never
        // re-reads after this point.
        tickMs = CapturePrefs.sampleIntervalS(this) * 1000L
        val pm = getSystemService(PowerManager::class.java)
        hinge = HingeMonitor(getSystemService(SensorManager::class.java)) { deg ->
            submit(CaptureInput.Observed(RawLine.Event(
                System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                EventKinds.HINGE, "deg=$deg")))
        }
        val bm = getSystemService(BatteryManager::class.java)
        // One-time capability read: MIN_VALUE is "unsupported", not data.
        val reportsCurrent = Sentinels.longOrNull(bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)) != null
        val reportsCounter = Sentinels.longOrNull(bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)) != null
        val gauge = host.gaugeProfile()
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
                // Not selectedSensorName: that is assigned in hinge.start(), thirty lines
                // below, so it is still null here and every Fold header claimed no hinge.
                // Reordering start() above this would work too, but the
                // capability question has a start()-independent answer — see [HingeMonitor.available].
                hasHinge = hinge.available,
                hasThermal = true,
            ),
        )
        snapshots = BatterySnapshots(bm)
        writer = RawLogWriter(logDir(this))
        eventLog = EventLog(logDir(this))
        screenOn = pm.isInteractive
        pm.addThermalStatusListener(mainExecutor, thermalListener)

        // Start the pump only once machine/writer/eventLog exist — from here
        // on, every state change goes through it.
        inputPump = scope.launch { for (input in inputs) execute(machine.on(input)) }

        createChannel()
        startForeground(
            NOTIFICATION_ID,
            host.build(this, host.content(idleState())),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        registerReceiver(powerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED) // sticky: delivers current state immediately
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }, Context.RECEIVER_NOT_EXPORTED)

        hinge.start()
        eventLog.append(RawLine.Event(
            System.currentTimeMillis(), SystemClock.elapsedRealtime(),
            EventKinds.SERVICE_START, "hinge=${hinge.selectedSensorName ?: "absent"}"))

        // If we started while already charging (boot on the nightstand), open a session now.
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) {
            snapshots.onBatteryChanged(sticky)
            // Named apart from the [plugged] field it would otherwise shadow: this is the sticky
            // broadcast's raw extra, the field is set by openSessionIfEnabled just below.
            val pluggedExtra = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            if (pluggedExtra > 0) {
                openSessionIfEnabled(System.currentTimeMillis(), SystemClock.elapsedRealtime())
            }
        }

        // The record master switch has to REACH this
        // notification. Before this collector, the host's recording-off
        // branch was correct but unreachable in practice — every
        // updateNotification call site is plug/unplug/tick/onCreate-driven, and
        // CapturePrefs.setRecordEnabled only writes the file, so a phone sitting
        // idle and unplugged kept showing the stale recap indefinitely after the
        // user switched recording off. Device testing caught what code review
        // alone did not: the branch was reasoned about, its caller never was.
        //
        // drop(1): prefsFlow replays the current value on collect, and
        // startForeground above has already rendered exactly that state.
        // Guarded on LiveFeed for the same reason as the recap reseed below —
        // a session already open when the switch flips keeps recording (see
        // [openSessionIfEnabled]'s open-gated/close-always polarity), so its
        // live notification must not be overwritten with idle text.
        scope.launch {
            CapturePrefs.recordEnabledFlow(this@RecordingService).drop(1).collect {
                if (LiveFeed.state.value?.recording != true) {
                    updateNotification(host.content(idleState()), force = true)
                }
            }
        }

        // Reseed [lastRecap] from disk: the raw log survives
        // process death even though the in-memory recap does not. Newest
        // session file by name (the filename is the session's start
        // wall-clock ms, so lexicographic == chronological).
        scope.launch(Dispatchers.IO) {
            RawLogWriter.sessionFiles(logDir(this@RecordingService))
                .lastOrNull()?.let { lastRecap = recapOf(it) }
            // Don't clobber a recording-state notification with the idle
            // recap — this read is racy against the pump's own state changes
            // by design (LiveFeed is the safe cross-thread snapshot).
            if (LiveFeed.state.value?.recording != true) {
                updateNotification(host.content(idleState()), force = true)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        // Silence every producer first, so nothing can queue up behind the
        // terminal input while we are draining.
        val ticks = stopTicking()
        getSystemService(PowerManager::class.java).removeThermalStatusListener(thermalListener)
        unregisterReceiver(powerReceiver)
        hinge.stop()

        // Then drain in order: join the cancelled tick loop, enqueue the
        // terminal input, close the channel, and let the pump run to the end
        // of the queue — so the session's CloseLog really is last. Bounded by
        // TEARDOWN_DRAIN_MS: a wedged pump degrades to a truncated session
        // rather than an ANR in service teardown.
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

    /**
     * Hands an input to the pump. Safe from any thread; called from the main
     * thread and from the tick loop. A rejected send means the channel is
     * already closed, i.e. teardown has passed the terminal input — the
     * session is over and there is nothing left to record.
     */
    private fun submit(input: CaptureInput) {
        inputs.trySend(input)
    }

    /**
     * Translates a plug-in signal into a pump session-open, gated by the
     * record master switch. This check lives OUTSIDE the pump
     * contract entirely: [CapturePrefs.recordEnabled] is read here, at the
     * Android-event boundary, before anything becomes a [CaptureInput], so
     * [SessionStateMachine] never even sees a disabled toggle — the reducer,
     * [LiveFeed], and [writer] are untouched by this gate. Only the
     * session-OPENING input is gated this way; [CaptureInput.PowerDisconnected]
     * and [CaptureInput.ServiceStopping] are never routed through here — they
     * always reach the pump directly, unconditionally, so a session already
     * open when the user flips the switch off still closes cleanly (flushed,
     * `session_end` written). Open-gated + close-always-passes is the only
     * polarity that can't strand an unclosed session: gating the close half
     * too would leave a file open forever with the pump refusing to hear the
     * unplug that would finish it.
     */
    private fun openSessionIfEnabled(t: Long, e: Long) {
        if (!CapturePrefs.recordEnabled(this)) return
        submit(CaptureInput.PowerConnected(t, e, targetLevel = host.chargeTargetLevel()))
        samplingMode = SamplingMode.TICK
        plugged = true
        startTicking()
    }

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            // Forced past the cadence gate: without this, a replug
            // within 5s of the unplug's forced notify would have its first
            // tick suppressed by the gate, leaving stale idle/recap text
            // showing while actively recording — mirror of the unplug bug.
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

    /** Cancels the tick loop and returns the job, so teardown can join it. */
    private fun stopTicking(): Job? {
        val job = tickJob
        tickJob = null
        job?.cancel()
        return job
    }

    /** Runs on the input pump only — see [inputs]. */
    private fun execute(effects: List<CaptureEffect>) {
        for (fx in effects) when (fx) {
            is CaptureEffect.OpenLog -> { writer.open(fx.header); LiveFeed.onOpen(fx.header.sessionStartWallClockMs) }
            is CaptureEffect.Append -> { writer.append(fx.line); (fx.line as? RawLine.Sample)?.let(LiveFeed::onSample) }
            is CaptureEffect.CloseLog -> {
                // currentFile before close(): RawLogWriter nulls it on close.
                val closedFile = writer.currentFile
                writer.close()
                LiveFeed.onClose()
                if (closedFile != null) {
                    // Parse happens off the pump — never on it, never in a
                    // receiver — so a slow disk read can't stall capture.
                    scope.launch(Dispatchers.IO) {
                        lastRecap = recapOf(closedFile)
                        updateNotification(host.content(idleState()), force = true)
                    }
                }
            }
            is CaptureEffect.LogEvent -> eventLog.append(fx.event)
            is CaptureEffect.SetSampling -> {
                samplingMode = fx.mode
                // tickJob stays main-confined (every other caller is on the main thread); the pump
                // only posts. [plugged] is what keeps a resume that lands after an unplug from
                // starting an orphan loop — see that field's KDoc.
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

    /**
     * Parses a finished session file into a [SessionRecap]. `Replay.parse` +
     * `sessionFacts` only — the service never touches Room/CaptureDao (the raw
     * log is the source of truth; the database is a disposable projection
     * rebuilt from it). Runs on [Dispatchers.IO]; never call from
     * the pump.
     *
     * Returns null only for a header-only file (no samples at all) — nothing
     * dateable to recap. A TRUNCATED session (neither a `SESSION_END` nor
     * `SERVICE_STOP` event, so `session.endedAtMs` is null) still gets a
     * recap: its last sample's wall-clock time is when data stopped, which
     * is an honest "Last charge <relative time>" anchor — [SessionRecap.endReason] carries the
     * uncertainty on to whatever the host renders.
     */
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

    /**
     * The recorder's idle state, handed to the host to render: the most recently
     * finished session's recap, if one is known, plus the record master switch — the host's own
     * text decides what a switched-off recorder says.
     */
    private fun idleState(): RecorderState.Idle =
        RecorderState.Idle(recap = lastRecap, recordEnabled = CapturePrefs.recordEnabled(this))

    /**
     * The recorder's recording state, handed to the host to render. [LiveFeed.state]
     * is the same-process, cross-thread-safe read the rest of the service already uses; its
     * session start is null in the brief window between the plug-in and the session file opening,
     * which is why [lastRecap] rides along as the host's fallback identity for an "open session"
     * affordance.
     */
    private fun recordingState(sample: RawLine.Sample): RecorderState.Recording {
        val snap = LiveFeed.state.value
        return RecorderState.Recording(
            sample = sample,
            sessionStartMs = if (snap?.recording == true) snap.sessionStartMs else null,
            lastRecap = lastRecap,
            recentLevels = snap?.recentLevels ?: emptyList(),
        )
    }

    /** Idempotent; the Application already did this at process start, but the service is what
     *  posts on these channels, so it makes sure of them itself too. */
    private fun createChannel() = NotificationChannels.ensure(this)

    /**
     * Posts what the host built for [content]. The recorder decides *when* the shade
     * changes; the host decides *what* it says.
     *
     * @param force Bypasses the cadence gate for state transitions (plug,
     *   unplug, recap arrival) — everything else (the per-tick recording
     *   line) is gated: skipped unless [HostContent.dedupeKey] actually changed
     *   AND at least 5s have passed since the last real notify,
     *   so a steady wattage reading doesn't churn the shade every second.
     */
    private fun updateNotification(content: HostContent, force: Boolean = false) {
        val key = content.dedupeKey
        if (!force && (key == lastNotifiedText || SystemClock.elapsedRealtime() - lastNotifiedAtE < 5_000)) return
        lastNotifiedText = key
        lastNotifiedAtE = SystemClock.elapsedRealtime()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, host.build(this, content))
    }
}
