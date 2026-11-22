// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
                if (c.dedupeKey == "tick") { building.countDown(); release.await(WAIT_MS, TimeUnit.MILLISECONDS) }
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
        assertTrue(building.await(WAIT_MS, TimeUnit.MILLISECONDS))
        val unplug = Thread { slow.post(idle("idle"), force = true) }.apply { start() }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
        while (unplug.state != Thread.State.BLOCKED && unplug.state != Thread.State.TERMINATED) {
            assertTrue("the unplug post neither ran nor waited", System.nanoTime() < deadline)
            Thread.yield()
        }
        release.countDown()
        tick.join(WAIT_MS)
        unplug.join(WAIT_MS)
        assertEquals(mapOf(NotificationIds.ALTERNATE to "idle"), shade.toMap())
    }

    @Test fun `a post superseded when it takes the lock is dropped, even forced, and one still wanted lands`() {
        poster.post(rec("a"), force = true, superseded = { true })
        assertEquals(emptyList<String>(), calls)
        poster.post(rec("a"), force = true, superseded = { false })
        assertEquals(listOf("notify 1 a@1"), calls)
    }

    @Test fun `a recap waiting on a session's first post reads superseded only once that post has landed`() {
        val shade = ConcurrentHashMap<Int, String>()
        val sessionOpen = AtomicBoolean(false)
        val building = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = ForegroundPoster(
            build = { c: HostContent ->
                if (c.dedupeKey == "tick") { building.countDown(); release.await(WAIT_MS, TimeUnit.MILLISECONDS) }
                c.dedupeKey
            },
            notify = { id, n -> shade[id] = n },
            promote = { id, n -> shade[id] = n },
            cancel = { id -> shade.remove(id) },
            clock = { now },
        )
        slow.start(idle("boot"))
        val tick = Thread { slow.post(rec("tick"), force = true) }.apply { start() }
        assertTrue(building.await(WAIT_MS, TimeUnit.MILLISECONDS))
        val recap = Thread { slow.post(idle("recap"), force = true, superseded = sessionOpen::get) }.apply { start() }
        awaitBlockedOrDone(recap)
        sessionOpen.set(true)
        release.countDown()
        tick.join(WAIT_MS)
        recap.join(WAIT_MS)
        assertEquals(mapOf(NotificationIds.ALTERNATE to "tick"), shade.toMap())
    }

    @Test fun `a closed poster posts nothing more, forced or not`() {
        poster.post(rec("a"))
        poster.close()
        now += 60_000L
        poster.post(idle("late recap"), force = true)
        poster.post(rec("b"))
        assertEquals(listOf("notify 1 a@1"), calls)
    }

    @Test fun `close waits for a post in flight, so nothing lands after it returns`() {
        val events = CopyOnWriteArrayList<String>()
        val building = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = ForegroundPoster(
            build = { c: HostContent ->
                building.countDown()
                release.await(WAIT_MS, TimeUnit.MILLISECONDS)
                c.dedupeKey
            },
            notify = { _, n -> events += "posted $n" },
            promote = { _, n -> events += "posted $n" },
            cancel = { },
            clock = { now },
        )
        val tick = Thread { slow.post(rec("tick"), force = true) }.apply { start() }
        assertTrue(building.await(WAIT_MS, TimeUnit.MILLISECONDS))
        val teardown = Thread { slow.close(); events += "closed" }.apply { start() }
        awaitBlockedOrDone(teardown)
        release.countDown()
        tick.join(WAIT_MS)
        teardown.join(WAIT_MS)
        slow.post(idle("late recap"), force = true)
        assertEquals(listOf("posted tick", "closed"), events.toList())
    }

    private fun awaitBlockedOrDone(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
        while (thread.state != Thread.State.BLOCKED && thread.state != Thread.State.TERMINATED) {
            assertTrue("${thread.name} neither ran nor waited", System.nanoTime() < deadline)
            Thread.yield()
        }
    }

    private companion object {
        const val WAIT_MS = 15_000L
    }
}
