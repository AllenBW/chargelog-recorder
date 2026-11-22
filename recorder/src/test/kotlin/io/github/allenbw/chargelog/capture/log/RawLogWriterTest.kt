// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

class RawLogWriterTest {

    @get:Rule val tmp = TemporaryFolder()

    private class Faulty(out: OutputStream, var failing: Boolean = false) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
        }

        override fun flush() {
            if (failing) throw IOException("No space left on device")
            super.flush()
        }

        override fun close() {
            if (failing) throw IOException("I/O error")
            super.close()
        }
    }

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
    fun `open while a log is open throws, and the open log is kept`() {
        val w = RawLogWriter(tmp.root)
        val f = w.open(header)
        assertThrows(IllegalStateException::class.java) { w.open(header.copy(sessionStartWallClockMs = 45)) }
        assertEquals(f, w.currentFile)
        assertFalse(File(tmp.root, "session-45.ndjson").exists())
        w.append(RawLine.Sample(t = 1, e = 2, level = 50))
        w.close()
        assertEquals(2, f.readLines().size)
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

    @Test
    fun `close leaves the writer closed and reusable when the flush and close themselves fail`() {
        val streams = mutableListOf<Faulty>()
        val w = RawLogWriter(tmp.root) { f -> Faulty(FileOutputStream(f, true)).also { streams += it } }
        w.open(header)
        w.append(RawLine.Sample(t = 1, e = 2, level = 50))
        streams.single().failing = true
        w.close()
        assertFalse("a failed flush stranded an open writer", w.isOpen)
        assertNull(w.currentFile)
        val f2 = w.open(header.copy(sessionStartWallClockMs = 43))
        assertEquals("session-43.ndjson", f2.name)
        assertEquals(header.copy(sessionStartWallClockMs = 43), NdjsonCodec.decode(f2.readLines().single()))
        w.close()
    }

    @Test
    fun `a header that cannot be written leaves the writer closed, so the next open is not refused`() {
        var failing = true
        val w = RawLogWriter(tmp.root) { f -> Faulty(FileOutputStream(f, true), failing) }
        assertThrows(IOException::class.java) { w.open(header) }
        assertFalse("a failed open stranded an open writer", w.isOpen)
        assertNull(w.currentFile)
        assertThrows(IllegalStateException::class.java) { w.append(RawLine.Sample(t = 1, e = 2)) }
        failing = false
        val f2 = w.open(header.copy(sessionStartWallClockMs = 44))
        assertEquals(header.copy(sessionStartWallClockMs = 44), NdjsonCodec.decode(f2.readLines().single()))
        w.close()
    }
}
