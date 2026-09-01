// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.capture.log.RawLine
import io.github.allenbw.chargelog.measure.CurrentScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettleDetectorTest {

    // 4.0 V nominal: watts = |µA| * mV / 1e9 → 2_000_000 µA ≈ 8 W, 200_000 µA ≈ 0.8 W (10 %), 700_000 ≈ 2.8 W (35 %).
    private fun s(e: Long, level: Int, status: Int, ua: Long) =
        RawLine.Sample(t = e, e = e, currentRaw = ua, voltageRaw = 4000, level = level, status = status)

    private fun drive(d: SettleDetector, samples: List<RawLine.Sample>, target: Int = 80) =
        samples.mapNotNull { d.offer(it, target) }

    @Test
    fun `FULL for two minutes settles once`() {
        val d = SettleDetector()
        val peak = (0..60).map { s(it * 1000L, 50 + it / 3, BatteryStatus.CHARGING, 2_000_000) }
        val full = (61..200).map { s(it * 1000L, 100, BatteryStatus.FULL, 50_000) }
        val t = drive(d, peak + full)
        assertEquals(listOf(SettleDetector.Transition.SETTLED), t)
        assertEquals(true, d.settled)
    }

    @Test
    fun `NOT_CHARGING at the target settles, at a low level it does not`() {
        val atTarget = SettleDetector()
        val a = (0..30).map { s(it * 1000L, 79, BatteryStatus.CHARGING, 2_000_000) } +
            (31..200).map { s(it * 1000L, 80, BatteryStatus.NOT_CHARGING, 0) }
        assertEquals(listOf(SettleDetector.Transition.SETTLED), drive(atTarget, a))

        val thermalPause = SettleDetector()
        val b = (0..30).map { s(it * 1000L, 55, BatteryStatus.CHARGING, 2_000_000) } +
            (31..400).map { s(it * 1000L, 55, BatteryStatus.NOT_CHARGING, 0) }
        assertEquals(emptyList<SettleDetector.Transition>(), drive(thermalPause, b))
    }

    @Test
    fun `level pinned at the max above 95 with trickle current settles`() {
        val d = SettleDetector()
        val samples = (0..30).map { s(it * 1000L, 97, BatteryStatus.CHARGING, 2_000_000) } +
            (31..200).map { s(it * 1000L, 98, BatteryStatus.CHARGING, 200_000) }
        assertEquals(listOf(SettleDetector.Transition.SETTLED), drive(d, samples))
    }

    @Test
    fun `a trickle dip below the hold level is not a settle`() {
        val d = SettleDetector()
        val samples = (0..30).map { s(it * 1000L, 60, BatteryStatus.CHARGING, 2_000_000) } +
            (31..200).map { s(it * 1000L, 60, BatteryStatus.CHARGING, 200_000) }
        assertNull(drive(d, samples).firstOrNull())
    }

    @Test
    fun `resume when charging returns with real current below the max, then settle again`() {
        val d = SettleDetector()
        val up = (0..30).map { s(it * 1000L, 99, BatteryStatus.CHARGING, 2_000_000) }
        val full = (31..200).map { s(it * 1000L, 100, BatteryStatus.FULL, 50_000) }
        val bounce = (201..260).map { s(it * 1000L, 99, BatteryStatus.CHARGING, 700_000) }
        val fullAgain = (261..420).map { s(it * 1000L, 100, BatteryStatus.FULL, 50_000) }
        val t = drive(d, up + full + bounce + fullAgain)
        assertEquals(
            listOf(SettleDetector.Transition.SETTLED, SettleDetector.Transition.RESUMED, SettleDetector.Transition.SETTLED),
            t,
        )
    }

    @Test
    fun `hold timer restarts when a non-holding sample interrupts`() {
        val d = SettleDetector()
        val samples = (0..30).map { s(it * 1000L, 80, BatteryStatus.CHARGING, 2_000_000) } +
            (31..100).map { s(it * 1000L, 100, BatteryStatus.FULL, 50_000) } +      // 70 s of FULL
            listOf(s(101_000L, 99, BatteryStatus.CHARGING, 2_000_000)) +           // interrupt
            (102..170).map { s(it * 1000L, 100, BatteryStatus.FULL, 50_000) }       // 69 s more — not 120 contiguous
        assertEquals(emptyList<SettleDetector.Transition>(), drive(d, samples))
    }

    @Test fun `resume threshold is measured in scaled watts`() {
        val d = SettleDetector(scale = CurrentScale.MILLI_AMP)
        // peak 350 mA; hold at 4 mA FULL → settled; a 200 mA CHARGING sample below the max resumes (> 30 % of peak).
        d.offer(RawLine.Sample(t = 0, e = 0, currentRaw = 350, voltageRaw = 4000, level = 98, status = BatteryStatus.CHARGING), 100)
        var t: SettleDetector.Transition? = null
        for (i in 0..130) t = d.offer(RawLine.Sample(t = 0, e = 1_000L + i * 1_000, currentRaw = 4, voltageRaw = 4000, level = 100, status = BatteryStatus.FULL), 100) ?: t
        assertEquals(SettleDetector.Transition.SETTLED, t)
        assertEquals(SettleDetector.Transition.RESUMED,
            d.offer(RawLine.Sample(t = 0, e = 200_000, currentRaw = 200, voltageRaw = 4000, level = 99, status = BatteryStatus.CHARGING), 100))
    }
}
