// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietWatchTest {

    private var now = 10_000_000L
    private val actions = mutableListOf<String>()
    private val watch = QuietWatch(
        clock = { now },
        repost = { actions += "repost" },
        rearm = { actions += "rearm" },
    )

    private var recording = true
    private var mode = SamplingMode.TICK
    private var tickerAlive = true

    private fun pass(ms: Long) {
        now += ms
    }

    private fun tickFor(ms: Long) {
        repeat((ms / 1_000L).toInt()) {
            pass(1_000L)
            watch.beat()
        }
    }

    private fun check() = watch.check(recording, mode, tickerAlive)

    @Test fun `ticks still landing need nothing`() {
        tickFor(5 * 60_000L)
        check()
        assertEquals(emptyList<String>(), actions)
    }

    @Test fun `a ticker that stops beating is re-posted once it has been quiet a minute, not before`() {
        tickFor(10_000L)
        pass(QuietWatch.PERIOD_MS - 1)
        check()
        assertEquals(emptyList<String>(), actions)
        pass(1)
        check()
        assertEquals(listOf("repost"), actions)
    }

    @Test fun `a settled hold is never quiet, however long the broadcasts stay away`() {
        tickFor(60_000L)
        mode = SamplingMode.EVENT
        tickerAlive = false
        pass(3 * 3_600_000L)
        check()
        assertEquals(emptyList<String>(), actions)
    }

    @Test fun `a resume whose ticker never started is re-posted and re-armed`() {
        tickFor(60_000L)
        mode = SamplingMode.EVENT
        tickerAlive = false
        pass(3 * 3_600_000L)
        watch.beat()
        mode = SamplingMode.TICK
        check()
        assertEquals(emptyList<String>(), actions)
        pass(QuietWatch.PERIOD_MS)
        check()
        assertEquals(listOf("repost", "rearm"), actions)
    }

    @Test fun `a resume whose ticker did start stays quiet while it beats`() {
        tickFor(60_000L)
        mode = SamplingMode.EVENT
        tickerAlive = false
        pass(3 * 3_600_000L)
        mode = SamplingMode.TICK
        tickerAlive = true
        watch.beat()
        tickFor(5 * 60_000L)
        check()
        assertEquals(emptyList<String>(), actions)
    }

    @Test fun `a ticker that is alive but hung is re-posted, never re-armed`() {
        tickFor(10_000L)
        pass(5 * 60_000L)
        check()
        assertEquals(listOf("repost"), actions)
    }

    @Test fun `no open session, nothing at all`() {
        recording = false
        tickerAlive = false
        pass(3 * 3_600_000L)
        check()
        assertEquals(emptyList<String>(), actions)
    }

    @Test fun `a re-armed ticker gets its minute before the next verdict`() {
        tickerAlive = false
        pass(QuietWatch.PERIOD_MS)
        check()
        watch.beat()
        tickerAlive = true
        pass(QuietWatch.PERIOD_MS - 1)
        check()
        assertEquals(listOf("repost", "rearm"), actions)
    }

    @Test fun `the period is slack for at least three of the slowest ticks`() {
        assertTrue(QuietWatch.PERIOD_MS >= 3 * 5_000L)
    }
}
