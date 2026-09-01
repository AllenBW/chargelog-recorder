// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.capture.log.Capabilities
import io.github.allenbw.chargelog.capture.log.DeviceKinds
import io.github.allenbw.chargelog.capture.log.EventKinds
import io.github.allenbw.chargelog.capture.log.RawLine
import io.github.allenbw.chargelog.measure.CurrentScale

data class SamplerProfile(
    val id: String,
    val tickMs: Long,
    val deviceModel: String,
    val osRelease: String,
    val appVersion: String,
    val deviceKind: String = DeviceKinds.PHONE,
    val deviceId: String? = null,
    val gaugeProfileId: String? = null,
    val capabilities: Capabilities? = null,
    val currentScale: CurrentScale = CurrentScale.MICRO_AMP,
)

object EndReasons {
    const val UNPLUGGED = "UNPLUGGED"
    const val SERVICE_KILLED = "SERVICE_KILLED"
}

sealed interface CaptureInput {
    /** @param targetLevel the user's charge target, passed in at session open so the
     *  pure machine never reads preferences itself; [SettleDetector] uses it as the hold level. */
    data class PowerConnected(val t: Long, val e: Long, val targetLevel: Int = 80) : CaptureInput
    data class PowerDisconnected(val t: Long, val e: Long) : CaptureInput
    data class Tick(val sample: RawLine.Sample) : CaptureInput
    data class ServiceStopping(val t: Long, val e: Long) : CaptureInput

    /**
     * An out-of-band observation (hinge angle, thermal status) that annotates
     * whatever is happening rather than driving the session. Which log it
     * belongs in depends on session state, so that decision lives here rather
     * than in the service reaching into the writer to ask if it is open.
     */
    data class Observed(val event: RawLine.Event) : CaptureInput
}

sealed interface CaptureEffect {
    data class OpenLog(val header: RawLine.Header) : CaptureEffect
    data class Append(val line: RawLine) : CaptureEffect
    data class CloseLog(val endReason: String) : CaptureEffect
    data object AcquireWakeLock : CaptureEffect
    data object ReleaseWakeLock : CaptureEffect
    data class LogEvent(val event: RawLine.Event) : CaptureEffect
    data class SetSampling(val mode: SamplingMode) : CaptureEffect
}

/**
 * How the service should produce ticks. [TICK] is the ordinary
 * wake-lock-backed timer; [EVENT] is the settled policy — no ticker, no wake lock, one sample per
 * gauge-driven `ACTION_BATTERY_CHANGED`.
 */
enum class SamplingMode { TICK, EVENT }

/**
 * Pure session lifecycle: inputs in, effects out, no Android types. The
 * service is a thin shell that executes the effects. Keeping this
 * pure is what makes the capture layer JVM-testable.
 */
class SessionStateMachine(private val profile: SamplerProfile) {

    companion object {
        /** Gate tick while settled: samples are gauge-driven, so only > 5 min of silence is a gap. */
        const val EVENT_GAP_TICK_MS = 100_000L
    }

    var recording = false
        private set
    private val gate = SampleGate()
    private val settle = SettleDetector(scale = profile.currentScale)
    private var targetLevel = 80
    private var gateTickMs = profile.tickMs

    fun on(input: CaptureInput): List<CaptureEffect> = when (input) {
        is CaptureInput.PowerConnected -> if (recording) emptyList() else {
            recording = true
            gate.reset()
            settle.reset()
            targetLevel = input.targetLevel
            gateTickMs = profile.tickMs
            listOf(
                CaptureEffect.OpenLog(
                    RawLine.Header(
                        schema = 2,
                        samplerProfileId = profile.id,
                        deviceModel = profile.deviceModel,
                        osRelease = profile.osRelease,
                        appVersion = profile.appVersion,
                        tickMs = profile.tickMs,
                        sessionStartWallClockMs = input.t,
                        deviceKind = profile.deviceKind,
                        deviceId = profile.deviceId,
                        gaugeProfileId = profile.gaugeProfileId,
                        capabilities = profile.capabilities,
                    )
                ),
                CaptureEffect.Append(
                    RawLine.Event(t = input.t, e = input.e, kind = EventKinds.SESSION_START)
                ),
                // CADENCE is emitted at open as well as at every policy change, so a reader never
                // has to infer the opening cadence from the first two sample timestamps. The
                // open-time line names the profile's own tick, mirroring the settled/resumed
                // CADENCE lines below.
                CaptureEffect.Append(
                    RawLine.Event(input.t, input.e, EventKinds.CADENCE, "tickMs=${profile.tickMs},policy=tick")
                ),
                CaptureEffect.AcquireWakeLock,
                // Declared, not assumed: a session that settled and then closed leaves a
                // SetSampling(EVENT) behind it in the service's queue, and the next open has to
                // win over it in queue order or the new session would run with no ticker.
                CaptureEffect.SetSampling(SamplingMode.TICK),
            )
        }

        is CaptureInput.Tick -> if (!recording) emptyList() else {
            val gated = when (val d = gate.offer(input.sample, gateTickMs)) {
                is SampleGate.Decision.Persist ->
                    listOfNotNull(d.gap?.let { CaptureEffect.Append(it) }, CaptureEffect.Append(d.sample))
                is SampleGate.Decision.Skip ->
                    listOfNotNull(d.gap?.let { CaptureEffect.Append(it) })
            }
            gated + when (settle.offer(input.sample, targetLevel)) {
                SettleDetector.Transition.SETTLED -> {
                    gateTickMs = EVENT_GAP_TICK_MS
                    listOf(
                        CaptureEffect.Append(RawLine.Event(input.sample.t, input.sample.e, EventKinds.CAPTURE_POLICY, "settled")),
                        CaptureEffect.Append(RawLine.Event(input.sample.t, input.sample.e, EventKinds.CADENCE, "tickMs=$EVENT_GAP_TICK_MS,policy=event")),
                        CaptureEffect.ReleaseWakeLock,
                        CaptureEffect.SetSampling(SamplingMode.EVENT),
                    )
                }
                SettleDetector.Transition.RESUMED -> {
                    gateTickMs = profile.tickMs
                    listOf(
                        CaptureEffect.Append(RawLine.Event(input.sample.t, input.sample.e, EventKinds.CAPTURE_POLICY, "resumed")),
                        CaptureEffect.Append(RawLine.Event(input.sample.t, input.sample.e, EventKinds.CADENCE, "tickMs=${profile.tickMs},policy=tick")),
                        CaptureEffect.AcquireWakeLock,
                        CaptureEffect.SetSampling(SamplingMode.TICK),
                    )
                }
                null -> emptyList()
            }
        }

        // The disconnect marker goes to events.ndjson in BOTH branches, matching the connect
        // side's unconditional contract ("must land in events.ndjson whatever the session state
        // is"). It used to be emitted only in the not-recording branch — i.e. almost never, since
        // the phone is normally recording when it gets unplugged — leaving every plug-in span
        // with a start marker and no end marker for consumers to join against.
        is CaptureInput.PowerDisconnected -> if (!recording) {
            listOf(CaptureEffect.LogEvent(
                RawLine.Event(t = input.t, e = input.e, kind = EventKinds.POWER_DISCONNECTED)))
        } else {
            recording = false
            listOf(
                CaptureEffect.LogEvent(
                    RawLine.Event(t = input.t, e = input.e, kind = EventKinds.POWER_DISCONNECTED)),
                CaptureEffect.Append(
                    RawLine.Event(t = input.t, e = input.e, kind = EventKinds.SESSION_END)),
                CaptureEffect.CloseLog(EndReasons.UNPLUGGED),
                CaptureEffect.ReleaseWakeLock,
            )
        }

        is CaptureInput.Observed -> listOf(
            if (recording) CaptureEffect.Append(input.event)
            else CaptureEffect.LogEvent(input.event)
        )

        is CaptureInput.ServiceStopping -> if (!recording) emptyList() else {
            recording = false
            listOf(
                CaptureEffect.Append(
                    RawLine.Event(t = input.t, e = input.e, kind = EventKinds.SERVICE_STOP)),
                CaptureEffect.CloseLog(EndReasons.SERVICE_KILLED),
                CaptureEffect.ReleaseWakeLock,
            )
        }
    }
}
