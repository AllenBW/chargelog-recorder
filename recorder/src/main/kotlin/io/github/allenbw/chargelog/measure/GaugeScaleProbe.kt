// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.measure

import io.github.allenbw.chargelog.capture.log.RawLine

/**
 * The first-samples half of [GaugeProfiles.plausibility]: collects a session's opening charging
 * currents and answers, once, with the refined profile — or never, which is also an answer.
 *
 * [GaugeProfiles.plausibility] had no production caller until the 2026-09-14 audit found it. It
 * was fully unit-tested and fully dead, so `gauge-unknown-ma` could not reach a header, and a
 * watch that is neither a Pixel nor a Samsung — which the bundled table can only call
 * [GaugeProfiles.UNKNOWN], µA — recorded every current a thousand times low AT THE SOURCE. No
 * read-side fix repairs that: the raw number in the log is the only number there is.
 *
 * Why a window and not the first sample. A cell does not reach its charging current instantly —
 * the watch's own ramp runs about seven seconds from plug-in — so a single early reading is small
 * on ANY gauge and would call every µA watch a mA one. [SAMPLES] at the recorder's tick covers
 * that ramp and then some, and [GaugeProfiles.plausibility] takes the median rather than the mean,
 * so the ramp's own low readings cannot drag the verdict even if the window is mostly ramp.
 *
 * Zeros are not collected at all. A dock cycling power at 100% reports no current, and a window
 * full of zeros is not evidence of a scale — it is evidence of nothing. Skipping them means a
 * session that begins full simply never fills its window, and the probe is still open later in
 * that same session if real current starts flowing.
 *
 * One probe per session, so a session that could not answer never stops the next one from trying.
 * A session that DID answer has already been persisted by then, so the next one starts from the
 * refined profile and never probes at all.
 *
 * A [GaugeProfiles.PHONE] is probed too, but not by its current alone: a full phone's µA trickle
 * reads like mA, and the answer is persisted, so a phone is refined to [GaugeProfiles.PHONE_MA]
 * only when [PhoneScaleEvidence] — the median AND the level's climb — agrees. That needs the whole
 * sample, so a phone answers only through the [offer] overload that takes one.
 */
class GaugeScaleProbe(
    private val selected: GaugeProfile,
    private val samples: Int = SAMPLES,
) {

    companion object {
        /** Readings in the window. At the recorder's 1 s tick that is fifteen seconds — twice the
         *  watch's measured plug-in ramp, so the median sits past it. */
        const val SAMPLES = 15
    }

    private val magnitudes = ArrayList<Long>(samples)
    private val phone = PhoneScaleEvidence()
    private var answered = false

    /** Whether this probe can still say anything. False for a measured gauge (a probe never
     *  second-guesses one) and after it has answered. */
    val open: Boolean get() = !answered && (selected === GaugeProfiles.UNKNOWN || selected === GaugeProfiles.PHONE)

    /**
     * Offers one sample's raw current. Returns the refined profile the once the window fills and
     * the evidence says mA, and null every other time — including when the window fills and says
     * µA, which is a verdict of "no change" rather than a new profile to record.
     *
     * Identity, not equality, on [GaugeProfiles.UNKNOWN] — the same rule
     * [GaugeProfiles.plausibility] itself applies: a host that hands over its own equal-looking
     * profile means a gauge it characterized, and a characterized gauge outranks a heuristic.
     */
    fun offer(currentRaw: Long?): GaugeProfile? {
        if (selected !== GaugeProfiles.UNKNOWN) return null
        if (!open || currentRaw == null || currentRaw == 0L) return null
        magnitudes += currentRaw
        if (magnitudes.size < samples) return null
        answered = true
        return GaugeProfiles.plausibility(magnitudes, selected).takeIf { it !== selected }
    }

    /**
     * Offers one whole sample — what the recording service calls. A watch's answer is exactly
     * [offer] of the sample's current; a phone's is [PhoneScaleEvidence]'s, which also reads the
     * level, the status and the time. Returns [GaugeProfiles.PHONE_MA] once a phone's evidence
     * proves mA, and null every other time, including a µA verdict.
     */
    fun offer(sample: RawLine.Sample): GaugeProfile? {
        if (selected !== GaugeProfiles.PHONE) return offer(sample.currentRaw)
        if (!open) return null
        val level = Units.levelPct(sample.level, sample.scale)
        val milliAmps = phone.offer(sample.e, sample.currentRaw, level, sample.status) ?: return null
        answered = true
        return GaugeProfiles.PHONE_MA.takeIf { milliAmps }
    }
}
