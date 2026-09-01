// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.capture.log.RawLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiveFeedTest {

    // LiveFeed is a singleton object, so tests must not order-couple.
    @Before
    fun setUp() = LiveFeed.reset()

    private fun sample(e: Long, current: Long? = 500_000, voltage: Int? = 5_000, level: Int? = 50) =
        RawLine.Sample(t = e + 7_000, e = e, currentRaw = current, voltageRaw = voltage, level = level)

    @Test
    fun `reset clears state to null`() {
        assertNull(LiveFeed.state.value)
    }

    @Test
    fun `open then two samples produce a recording snapshot`() {
        LiveFeed.onOpen(1000)
        LiveFeed.onSample(sample(e = 1000))
        LiveFeed.onSample(sample(e = 2000))

        val snap = LiveFeed.state.value!!
        assertTrue(snap.recording)
        assertEquals(1000L, snap.sessionStartMs)
        assertEquals(2, snap.recentWatts.size)
        assertEquals(2, snap.recentLevels.size)
    }

    @Test
    fun `open with no prior state publishes a seeded snapshot with no sample`() {
        LiveFeed.onOpen(1000)

        val snap = LiveFeed.state.value!!
        assertNull(snap.sample)
        assertTrue(snap.recording)
        assertEquals(1000L, snap.sessionStartMs)
        assertTrue(snap.recentWatts.isEmpty())
        assertTrue(snap.recentLevels.isEmpty())
    }

    @Test
    fun `open after a prior session's close never republishes the stale sample`() {
        LiveFeed.onOpen(0)
        LiveFeed.onSample(sample(e = 1000))
        LiveFeed.onClose()

        LiveFeed.onOpen(5000)

        val snap = LiveFeed.state.value!!
        assertNull(snap.sample)
        assertTrue(snap.recording)
        assertEquals(5000L, snap.sessionStartMs)
    }

    @Test
    fun `ring caps at 600 dropping the oldest`() {
        LiveFeed.onOpen(0)
        repeat(700) { i -> LiveFeed.onSample(sample(e = i.toLong())) }

        val snap = LiveFeed.state.value!!
        assertEquals(600, snap.recentWatts.size)
        assertEquals(600, snap.recentLevels.size)
        // samples e=0..99 pushed out; e=100 is now the oldest retained
        assertEquals(100L, snap.recentLevels.first().first)
        assertEquals(699L, snap.recentLevels.last().first)
    }

    @Test
    fun `close marks not recording but retains the last sample`() {
        LiveFeed.onOpen(0)
        LiveFeed.onSample(sample(e = 1000))
        LiveFeed.onClose()

        val snap = LiveFeed.state.value!!
        assertFalse(snap.recording)
        assertEquals(sample(e = 1000), snap.sample)
        assertNull(snap.sessionStartMs)
    }

    @Test
    fun `history grows one entry per sample and survives past the ring cap`() {
        LiveFeed.onOpen(0L)
        repeat(700) { LiveFeed.onSample(sample(e = it * 1_000L)) }

        val snap = LiveFeed.state.value!!
        assertEquals(700, snap.history.size)   // ring caps recentWatts at 600; history must not
        assertEquals(600, snap.recentWatts.size)
    }

    @Test
    fun `history clears on open`() {
        LiveFeed.onOpen(0)
        LiveFeed.onSample(sample(e = 1000))
        LiveFeed.onSample(sample(e = 2000))
        LiveFeed.onClose()

        LiveFeed.onOpen(5000)
        assertTrue(LiveFeed.state.value!!.history.isEmpty())
    }

    @Test
    fun `history clears on reset`() {
        LiveFeed.onOpen(0)
        LiveFeed.onSample(sample(e = 1000))
        LiveFeed.onSample(sample(e = 2000))

        LiveFeed.reset()
        // onSample doesn't need a prior onOpen — it publishes a Snapshot unconditionally — so
        // this exercises reset()'s own clearing directly, without routing through onOpen's.
        // If reset() failed to clear history, this would read 3 (2 stale + 1 new) instead of 1.
        LiveFeed.onSample(sample(e = 6000))
        assertEquals(1, LiveFeed.state.value!!.history.size)
    }

    @Test
    fun `snapshot history is an immutable copy`() {
        LiveFeed.onOpen(0)
        LiveFeed.onSample(sample(e = 1000))
        val captured = LiveFeed.state.value!!.history

        LiveFeed.onSample(sample(e = 2000))
        LiveFeed.onSample(sample(e = 3000))

        assertEquals(1, captured.size)
        assertEquals(3, LiveFeed.state.value!!.history.size)
    }
}
