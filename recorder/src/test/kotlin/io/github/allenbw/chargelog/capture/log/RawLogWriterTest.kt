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
        val lines = java.io.File(tmp.root, EventLog.FILE_NAME).readLines()
        assertEquals(2, lines.size)
        assertTrue(NdjsonCodec.decode(lines[1]) is RawLine.Event)
    }

    @Test
    fun `the event log's file name is exported and is what append writes`() {
        assertEquals("events.ndjson", EventLog.FILE_NAME)
        EventLog(tmp.root).append(RawLine.Event(t = 1, e = 1, kind = EventKinds.BOOT))
        assertTrue(java.io.File(tmp.root, EventLog.FILE_NAME).isFile)
    }

    /** Delete-all's out-of-session half. The plug/unplug markers are a record of when the owner is
     *  near a charger, so "remove every log file from this device" has to reach them. */
    @Test
    fun `clear deletes the event log`() {
        val log = EventLog(tmp.root)
        log.append(RawLine.Event(t = 1, e = 1, kind = EventKinds.BOOT))
        assertTrue(java.io.File(tmp.root, EventLog.FILE_NAME).isFile)
        log.clear()
        assertFalse(java.io.File(tmp.root, EventLog.FILE_NAME).exists())
    }

    @Test
    fun `clear also removes a compaction temp file left by a crash`() {
        java.io.File(tmp.root, "${EventLog.FILE_NAME}.tmp").writeText("{\"y\":\"e\"}\n")
        EventLog(tmp.root).clear()
        assertFalse(java.io.File(tmp.root, "${EventLog.FILE_NAME}.tmp").exists())
    }

    @Test
    fun `an append past the cap compacts to the newest half and stays parseable`() {
        val log = EventLog(tmp.root, maxBytes = 512)
        repeat(60) { log.append(RawLine.Event(t = it.toLong(), e = it.toLong(), kind = EventKinds.BOOT)) }
        val lines = java.io.File(tmp.root, EventLog.FILE_NAME).readLines().filter { it.isNotBlank() }
        assertTrue("compaction should have run", lines.size < 60)
        assertTrue("every surviving line still decodes", lines.all { NdjsonCodec.decode(it) is RawLine.Event })
        assertEquals(59L, (NdjsonCodec.decode(lines.last()) as RawLine.Event).t)
    }

    @Test
    fun `an empty directory clears without throwing`() {
        EventLog(tmp.root).clear()
        assertFalse(java.io.File(tmp.root, EventLog.FILE_NAME).exists())
    }

    @Test
    fun `close leaves the writer reusable even when the underlying file is gone`() {
        val w = RawLogWriter(tmp.root)
        val f = w.open(header)
        w.append(RawLine.Sample(t = 1, e = 2, level = 50))
        f.delete()
        w.close()
        assertFalse("close must always clear the writer", w.isOpen)
        val f2 = w.open(header.copy(sessionStartWallClockMs = 99))
        assertEquals("session-99.ndjson", f2.name)
        w.close()
    }
}
