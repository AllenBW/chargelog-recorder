// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.capture.log.EventKinds
import io.github.allenbw.chargelog.capture.log.RawLine
import io.github.allenbw.chargelog.capture.log.RawLogWriter
import io.github.allenbw.chargelog.measure.GaugeProfile
import io.github.allenbw.chargelog.measure.GaugeProfiles
import io.github.allenbw.chargelog.measure.GaugeScaleProbe
import java.io.File

internal interface EffectPorts {
    fun appendEvent(event: RawLine.Event)
    fun acquireWakeLock()
    fun releaseWakeLock()
    fun onSampling(mode: SamplingMode)
    fun onClosed(file: File)
    fun fallbackGauge(): GaugeProfile
    fun onGaugeLearned(profile: GaugeProfile)
    fun beat()
}

internal class EffectRunner(
    private val machine: SessionStateMachine,
    private val log: RawLogWriter,
    private val ports: EffectPorts,
    private val onFailure: (CaptureEffect?, Exception) -> Unit,
) {
    private var probe: GaugeScaleProbe? = null

    fun handle(input: CaptureInput): Boolean {
        val effects = attempt(null) { machine.on(input) } ?: return false
        val clean = effects.count { fx -> attempt(fx) { apply(fx) } == null } == 0
        if (clean && input is CaptureInput.Tick) ports.beat()
        return clean
    }

    // TRAP docs/recording-service-threading.md — one effect's failure never skips the rest or ends the pump
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T : Any> attempt(fx: CaptureEffect?, block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            onFailure(fx, e)
            null
        }

    private fun apply(fx: CaptureEffect) = when (fx) {
        is CaptureEffect.OpenLog -> open(fx.header)
        is CaptureEffect.Append -> append(fx.line)
        is CaptureEffect.CloseLog -> close()
        is CaptureEffect.LogEvent -> ports.appendEvent(fx.event)
        is CaptureEffect.SetSampling -> sampling(fx.mode)
        CaptureEffect.AcquireWakeLock -> ports.acquireWakeLock()
        CaptureEffect.ReleaseWakeLock -> ports.releaseWakeLock()
    }

    private fun open(header: RawLine.Header) {
        if (log.isOpen) close()
        log.open(header)
        LiveFeed.onOpen(header.sessionStartWallClockMs)
        val gauge = GaugeProfiles.byId(header.gaugeProfileId) ?: ports.fallbackGauge()
        LiveFeed.onScale(gauge.currentScale)
        probe = GaugeScaleProbe(gauge)
    }

    private fun append(line: RawLine) {
        log.append(line)
        if (line is RawLine.Sample) {
            LiveFeed.onSample(line)
            learn(line)
        }
    }

    private fun learn(sample: RawLine.Sample) {
        val refined = probe?.offer(sample) ?: return
        machine.refineGauge(refined)
        LiveFeed.onScale(refined.currentScale)
        try {
            log.append(RawLine.Event(
                sample.t, sample.e, EventKinds.GAUGE_SCALE, EventKinds.GAUGE_SCALE_DETAIL_KEY + refined.id,
            ))
        } finally {
            ports.onGaugeLearned(refined)
        }
    }

    private fun close() {
        val closed = log.currentFile
        probe = null
        try {
            log.close()
        } finally {
            LiveFeed.onClose()
        }
        closed?.let(ports::onClosed)
    }

    private fun sampling(mode: SamplingMode) {
        ports.onSampling(mode)
        LiveFeed.onSampling(mode == SamplingMode.EVENT)
    }
}
