// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ForegroundPosterTest {

    private var now = 100_000L
    private val calls = mutableListOf<String>()
    private val poster = ForegroundPoster(
        build = { c: HostContent -> "${c.dedupeKey}@${c.notificationId}" },
        notify = { id, n -> calls += "notify $id $n" },
        promote = { id, n -> calls += "promote $id $n" },
        cancel = { id -> calls += "cancel $id" },
        clock = { now },
    )

    private fun rec(key: String) = HostContent(key, NotificationChannels.RECORDING, null)
    private fun idle(key: String) = HostContent(key, NotificationChannels.IDLE, null)

    @Test fun `the first post updates the primary id in place`() {
        poster.post(rec("a"))
        assertEquals(listOf("notify 1 a@1"), calls)
    }

    @Test fun `an unchanged key is dropped however long it has been`() {
        poster.post(rec("a"))
        now += 60_000L
        poster.post(rec("a"))
        assertEquals(1, calls.size)
    }

    @Test fun `a new key inside the gate is dropped, and passes once the gate has run`() {
        poster.post(rec("a"))
        now += ForegroundPoster.GATE_MS - 1
        poster.post(rec("b"))
        assertEquals(1, calls.size)
        now += 1
        poster.post(rec("b"))
        assertEquals("notify 1 b@1", calls.last())
    }

    @Test fun `a forced post passes the gate`() {
        poster.post(rec("a"))
        poster.post(rec("a"), force = true)
        assertEquals(2, calls.size)
    }

    @Test fun `a late unforced post cannot replace a forced transition inside the gate`() {
        poster.post(rec("a"))
        now += 60_000L
        poster.post(idle("idle"), force = true)
        now += 1_000L
        poster.post(rec("No reading for 12m"))
        assertEquals("cancel 1", calls.last())
        assertEquals(3, calls.size)
    }

    @Test fun `a channel change moves to the other id in the foreground, then cancels the old one`() {
        poster.post(rec("a"))
        now += 60_000L
        poster.post(idle("i"), force = true)
        assertEquals(listOf("notify 1 a@1", "promote 8 i@8", "cancel 1"), calls)
        now += 60_000L
        poster.post(rec("b"), force = true)
        assertEquals(listOf("promote 1 b@1", "cancel 8"), calls.takeLast(2))
    }

    @Test fun `the same channel never re-enters the foreground`() {
        repeat(5) { i ->
            poster.post(rec("k$i"))
            now += 60_000L
        }
        assertEquals(5, calls.count { it.startsWith("notify 1 ") })
    }

    @Test fun `start promotes on the primary id and records its channel`() {
        poster.post(rec("a"))
        now += 60_000L
        poster.post(idle("i"), force = true)
        calls.clear()
        poster.start(idle("boot"))
        assertEquals(listOf("promote 1 boot@1"), calls)
    }

    @Test fun `the first recording post after a start swaps ids, as any channel change does`() {
        poster.start(idle("boot"))
        poster.post(rec("r"), force = true)
        assertEquals(listOf("promote 1 boot@1", "promote 8 r@8", "cancel 1"), calls)
    }

    @Test fun `a post that lands while another is building waits, so the shade never holds two`() {
        val shade = ConcurrentHashMap<Int, String>()
        val building = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = ForegroundPoster(
            build = { c: HostContent ->
                if (c.dedupeKey == "tick") { building.countDown(); release.await(5, TimeUnit.SECONDS) }
                c.dedupeKey
            },
            notify = { id, n -> shade[id] = n },
            promote = { id, n -> shade[id] = n },
            cancel = { id -> shade.remove(id) },
            clock = { now },
        )
        slow.start(rec("boot"))
        now += 60_000L
        val tick = Thread { slow.post(rec("tick"), force = true) }.apply { start() }
        assertTrue(building.await(5, TimeUnit.SECONDS))
        val unplug = Thread { slow.post(idle("idle"), force = true) }.apply { start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (unplug.state != Thread.State.BLOCKED && unplug.state != Thread.State.TERMINATED) {
            assertTrue("the unplug post neither ran nor waited", System.nanoTime() < deadline)
            Thread.yield()
        }
        release.countDown()
        tick.join(5_000L)
        unplug.join(5_000L)
        assertEquals(mapOf(NotificationIds.ALTERNATE to "idle"), shade.toMap())
    }
}
