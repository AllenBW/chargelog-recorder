// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.measure

import io.github.allenbw.chargelog.capture.BatteryStatus
import io.github.allenbw.chargelog.capture.log.RawLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GaugeScaleProbeTest {

    private fun run(probe: GaugeScaleProbe, raw: Long, n: Int = GaugeScaleProbe.SAMPLES): GaugeProfile? {
        var answer: GaugeProfile? = null
        repeat(n) { answer = probe.offer(raw) ?: answer }
        return answer
    }

    @Test fun `a milliamp watch is detected once the window fills`() {
        val probe = GaugeScaleProbe(GaugeProfiles.UNKNOWN)
        repeat(GaugeScaleProbe.SAMPLES - 1) { assertNull(probe.offer(420)) }
        assertSame(GaugeProfiles.UNKNOWN_MA, probe.offer(420))
        assertNull(probe.offer(420))
        assertFalse(probe.open)
    }

    @Test fun `a microamp watch is left alone`() {
        val probe = GaugeScaleProbe(GaugeProfiles.UNKNOWN)
        assertNull(run(probe, 420_000))
        assertFalse(probe.open)
    }

    @Test fun `zeros are not evidence and do not close the window`() {
        val probe = GaugeScaleProbe(GaugeProfiles.UNKNOWN)
        repeat(GaugeScaleProbe.SAMPLES * 3) { assertNull(probe.offer(0)) }
        assertTrue(probe.open)
        assertSame(GaugeProfiles.UNKNOWN_MA, run(probe, 300))
    }

    @Test fun `the plug-in ramp cannot fake a milliamp gauge`() {
        val probe = GaugeScaleProbe(GaugeProfiles.UNKNOWN)
        var answer: GaugeProfile? = null
        listOf(100L, 400, 900, 1_500, 2_200, 3_000, 4_000).forEach { answer = probe.offer(it) ?: answer }
        repeat(8) { answer = probe.offer(1_800_000) ?: answer }
        assertNull(answer)
        assertFalse(probe.open)
    }

    @Test fun `a measured gauge is never second-guessed`() {
        for (measured in listOf(GaugeProfiles.QBG, GaugeProfiles.SEC, GaugeProfiles.UNKNOWN_MA, GaugeProfiles.PHONE_MA)) {
            val probe = GaugeScaleProbe(measured)
            assertFalse(measured.id, probe.open)
            assertNull(measured.id, run(probe, 420))
        }
    }

    @Test fun `a null current is skipped, not counted`() {
        val probe = GaugeScaleProbe(GaugeProfiles.UNKNOWN)
        repeat(GaugeScaleProbe.SAMPLES * 2) { assertNull(probe.offer(null)) }
        assertTrue(probe.open)
    }

    private fun phoneSample(i: Int, currentRaw: Long, level: Int, status: Int = BatteryStatus.CHARGING) =
        RawLine.Sample(t = i * 1_000L, e = i * 1_000L, currentRaw = currentRaw, level = level, scale = 100, status = status)

    @Test fun `a milliamp phone is refined to its own table entry once its charge proves it`() {
        val probe = GaugeScaleProbe(GaugeProfiles.PHONE)
        assertTrue(probe.open)
        val answers = (0 until 400).mapNotNull { probe.offer(phoneSample(it, 2_000L, 40 + it / 72)) }
        assertEquals(listOf(GaugeProfiles.PHONE_MA), answers)
        assertEquals("gauge-phone-ma", answers.single().id)
        assertFalse(probe.open)
    }

    @Test fun `a microamp phone is left alone and the probe closes`() {
        val probe = GaugeScaleProbe(GaugeProfiles.PHONE)
        assertTrue((0 until 60).none { probe.offer(phoneSample(it, 2_000_000L, 40)) != null })
        assertFalse(probe.open)
    }

    @Test fun `a full phone's microamp trickle never refines it, and the probe stays open`() {
        val probe = GaugeScaleProbe(GaugeProfiles.PHONE)
        assertTrue((0 until 2_000).none { probe.offer(phoneSample(it, 2_865L, 100, BatteryStatus.FULL)) != null })
        assertTrue((0 until 2_000).none { probe.offer(phoneSample(it, 1_563L, 100)) != null })
        assertTrue(probe.open)
    }

    @Test fun `a phone is never judged from its current alone`() {
        val probe = GaugeScaleProbe(GaugeProfiles.PHONE)
        assertNull(run(probe, 420, n = GaugeScaleProbe.SAMPLES * 4))
        assertTrue(probe.open)
    }

    @Test fun `a watch probe answers a whole sample exactly as it answers its current`() {
        val probe = GaugeScaleProbe(GaugeProfiles.UNKNOWN)
        val answers = (0 until GaugeScaleProbe.SAMPLES).mapNotNull { probe.offer(phoneSample(it, 420L, 100, BatteryStatus.FULL)) }
        assertEquals(listOf(GaugeProfiles.UNKNOWN_MA), answers)
    }
}
