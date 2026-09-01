// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.capture.log.EventKinds
import io.github.allenbw.chargelog.capture.log.RawLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleGateTest {

    private fun sample(e: Long, current: Long? = 1000, level: Int? = 50) =
        RawLine.Sample(t = e + 1_000_000, e = e, currentRaw = current, level = level)

    @Test
    fun `sentinels map MIN_VALUE to null`() {
        assertNull(Sentinels.intOrNull(Int.MIN_VALUE))
        assertNull(Sentinels.longOrNull(Long.MIN_VALUE))
        assertEquals(0, Sentinels.intOrNull(0))
        assertEquals(-5L, Sentinels.longOrNull(-5L))
    }

    @Test
    fun `first sample always persists`() {
        val d = SampleGate().offer(sample(e = 0), tickMs = 1000)
        assertTrue(d is SampleGate.Decision.Persist)
    }

    @Test
    fun `unchanged gauge tuple is skipped, changed persists`() {
        val gate = SampleGate()
        gate.offer(sample(e = 0), tickMs = 1000)
        assertTrue(gate.offer(sample(e = 1000), tickMs = 1000) is SampleGate.Decision.Skip)
        assertTrue(gate.offer(sample(e = 2000, current = 2000), tickMs = 1000) is SampleGate.Decision.Persist)
    }

    @Test
    fun `timestamps alone do not count as change`() {
        val gate = SampleGate()
        gate.offer(sample(e = 0), tickMs = 1000)
        assertTrue(gate.offer(sample(e = 1000), tickMs = 1000) is SampleGate.Decision.Skip)
    }

    @Test
    fun `gap beyond factor times tick emits gap event even when skipping`() {
        val gate = SampleGate(gapFactor = 3)
        gate.offer(sample(e = 0), tickMs = 1000)
        val d = gate.offer(sample(e = 10_000), tickMs = 1000) // 10 s > 3 * 1 s
        val gap = when (d) {
            is SampleGate.Decision.Persist -> d.gap
            is SampleGate.Decision.Skip -> d.gap
        }
        assertNotNull(gap)
        assertEquals(EventKinds.GAP, gap!!.kind)
        assertEquals("elapsedGapMs=10000", gap.detail)
    }

    @Test
    fun `no gap within factor times tick`() {
        val gate = SampleGate(gapFactor = 3)
        gate.offer(sample(e = 0), tickMs = 1000)
        val d = gate.offer(sample(e = 2500), tickMs = 1000)
        val gap = when (d) {
            is SampleGate.Decision.Persist -> d.gap
            is SampleGate.Decision.Skip -> d.gap
        }
        assertNull(gap)
    }

    @Test
    fun `reset forgets history`() {
        val gate = SampleGate()
        gate.offer(sample(e = 0), tickMs = 1000)
        gate.reset()
        assertTrue(gate.offer(sample(e = 1000), tickMs = 1000) is SampleGate.Decision.Persist)
    }
}
