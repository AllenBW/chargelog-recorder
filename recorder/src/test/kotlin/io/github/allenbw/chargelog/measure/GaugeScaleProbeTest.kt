// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.measure

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
        for (measured in listOf(GaugeProfiles.PHONE, GaugeProfiles.QBG, GaugeProfiles.SEC, GaugeProfiles.UNKNOWN_MA)) {
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
}
