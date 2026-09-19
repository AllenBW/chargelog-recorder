// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.capture.log.Capabilities
import io.github.allenbw.chargelog.capture.log.DeviceKinds
import io.github.allenbw.chargelog.capture.log.EventKinds
import io.github.allenbw.chargelog.capture.log.RawLine
import io.github.allenbw.chargelog.measure.CurrentScale
import io.github.allenbw.chargelog.measure.GaugeProfile

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
    val socModel: String? = null,
    val totalMemBytes: Long? = null,
    val designCapacityMah: Int? = null,
)

object EndReasons {
    const val UNPLUGGED = "UNPLUGGED"
    const val SERVICE_KILLED = "SERVICE_KILLED"
}

sealed interface CaptureInput {
    /** @param targetLevel the user's charge target, passed in at session open so the
     *  pure machine never reads preferences itself; [SettleDetector] uses it as the hold level.
     *  @param cycleCount the platform's battery cycle count at plug-in, written into the header;
     *  null when the platform does not report one. */
    data class PowerConnected(
        val t: Long,
        val e: Long,
        val targetLevel: Int = 80,
        val cycleCount: Int? = null,
    ) : CaptureInput
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
class SessionStateMachine(profile: SamplerProfile) {

    companion object {
        /** Gate tick while settled: samples are gauge-driven, so only > 5 min of silence is a gap. */
        const val EVENT_GAP_TICK_MS = 100_000L
    }

    var recording = false
        private set

    private var profile: SamplerProfile = profile
    private var pendingGauge: GaugeProfile? = null
    private val gate = SampleGate()
    private var settle = SettleDetector(scale = profile.currentScale)
    private var targetLevel = 80
    private var gateTickMs = profile.tickMs

    /**
     * Record under [gauge] from the NEXT session on — the scale probe's one writer.
     *
     * Deferred rather than immediate, deliberately. The header of the session in progress went out
     * at plug-in and cannot be rewritten, and swapping the settle rule's scale underneath a
     * half-finished settle decision would be a second wrong answer on top of the first. The
     * session that LEARNED it is corrected instead by the `gauge_scale` event the recorder logs
     * into it; every session after this opens under the refined profile and needs no correction.
     */
    fun refineGauge(gauge: GaugeProfile) {
        if (gauge.id != profile.gaugeProfileId) pendingGauge = gauge
    }

    fun on(input: CaptureInput): List<CaptureEffect> = when (input) {
        is CaptureInput.PowerConnected -> if (recording) emptyList() else {
            recording = true
            pendingGauge?.let { g ->
                profile = SamplerProfiles.withGauge(profile, g)
                settle = SettleDetector(scale = g.currentScale)
                pendingGauge = null
            }
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
                        socModel = profile.socModel,
                        totalMemBytes = profile.totalMemBytes,
                        designCapacityMah = profile.designCapacityMah,
                        cycleCount = input.cycleCount,
                    )
                ),
                CaptureEffect.Append(
                    RawLine.Event(t = input.t, e = input.e, kind = EventKinds.SESSION_START)
                ),
                CaptureEffect.Append(
                    RawLine.Event(input.t, input.e, EventKinds.CADENCE, "tickMs=${profile.tickMs},policy=tick")
                ),
                CaptureEffect.AcquireWakeLock,
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
