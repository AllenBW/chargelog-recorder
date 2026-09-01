// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RawLogWriterTest {

    @get:Rule val tmp = TemporaryFolder()

    private val header = RawLine.Header(
        schema = 1, samplerProfileId = "p1", deviceModel = "test", osRelease = "16",
        appVersion = "0.1.0", tickMs = 1000, sessionStartWallClockMs = 42,
    )

    @Test
    fun `open writes header as first line and names file by session start`() {
        val w = RawLogWriter(tmp.root)
        val f = w.open(header)
        w.close()
        assertEquals("session-42.ndjson", f.name)
        val lines = f.readLines()
        assertEquals(1, lines.size)
        assertEquals(header, NdjsonCodec.decode(lines[0]))
    }

    @Test
    fun `appended lines are durable without close`() {
        val w = RawLogWriter(tmp.root)
        val f = w.open(header)
        w.append(RawLine.Sample(t = 1, e = 2, level = 50))
        // no close() — flush-per-write means a crash loses at most nothing
        assertEquals(2, f.readLines().size)
        w.close()
    }

    @Test
    fun `close makes writer reusable for a new session`() {
        val w = RawLogWriter(tmp.root)
        w.open(header); w.close()
        assertFalse(w.isOpen)
        val f2 = w.open(header.copy(sessionStartWallClockMs = 43))
        assertEquals("session-43.ndjson", f2.name)
        w.close()
    }

    @Test(expected = IllegalStateException::class)
    fun `append without open throws`() {
        RawLogWriter(tmp.root).append(RawLine.Sample(t = 1, e = 2))
    }

    @Test
    fun `close syncs and the file is readable and complete afterwards`() {
        val w = RawLogWriter(tmp.root)
        val f = w.open(header)
        w.append(RawLine.Sample(t = 1, e = 2, level = 50))
        w.append(RawLine.Event(t = 3, e = 4, kind = EventKinds.SESSION_END))
        w.close()
        assertEquals(3, f.readLines().size)
        assertFalse(w.isOpen)
    }

    @Test
    fun `event log appends across instances`() {
        EventLog(tmp.root).append(RawLine.Event(t = 1, e = 1, kind = EventKinds.BOOT))
        EventLog(tmp.root).append(RawLine.Event(t = 2, e = 2, kind = EventKinds.SERVICE_START))
        val lines = java.io.File(tmp.root, "events.ndjson").readLines()
        assertEquals(2, lines.size)
        assertTrue(NdjsonCodec.decode(lines[1]) is RawLine.Event)
    }
}
