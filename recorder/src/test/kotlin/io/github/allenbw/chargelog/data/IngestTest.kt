// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import io.github.allenbw.chargelog.capture.log.DeviceKinds
import io.github.allenbw.chargelog.capture.log.EventKinds
import io.github.allenbw.chargelog.capture.log.NdjsonCodec
import io.github.allenbw.chargelog.capture.log.RawLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream

class IngestTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dirs() = LogDirs(
        File(tmp.root, "rawlog"), File(tmp.root, "synced"), localDeviceId = "local",
        incoming = File(tmp.root, "incoming"),
    )

    private fun text(vararg lines: RawLine) = lines.joinToString("") { NdjsonCodec.encode(it) + "\n" }

    private fun header(start: Long, deviceId: String?, kind: String? = DeviceKinds.WATCH) = RawLine.Header(
        schema = 2, samplerProfileId = "w1", deviceModel = "Pixel Watch 3", osRelease = "16", appVersion = "0.2.0",
        tickMs = 1000, sessionStartWallClockMs = start, deviceKind = kind, deviceId = deviceId,
    )

    @Test
    fun `a foreign watch log lands in its synced dir and is projected`() {
        val dao = FakeCaptureDao()
        val body = text(header(5000L, "w1"), RawLine.Sample(t = 6000, e = 20, level = 60),
            RawLine.Event(t = 7000, e = 30, kind = EventKinds.SESSION_END))

        val r = runBlocking { Replay.ingestExternal(body.byteInputStream(), dirs(), dao) }

        assertTrue(r is IngestResult.Imported)
        val f = File(tmp.root, "synced/w1/session-5000.ndjson")
        assertEquals(body, f.readText())
        assertEquals("w1", dao.sessions.getValue(5000L).deviceId)
        assertEquals("session-5000.ndjson", dao.sessions.getValue(5000L).sourceFile)
        assertEquals(1, dao.samplesBySession.getValue(5000L).size)
        assertEquals(emptyList<File>(), File(tmp.root, "incoming").listFiles()?.toList() ?: emptyList<File>())
    }

    @Test
    fun `a legacy header without a deviceId is rejected`() {
        val r = runBlocking { Replay.ingestExternal(text(header(5000L, null, kind = null)).byteInputStream(), dirs(), FakeCaptureDao()) }
        assertEquals(IngestResult.Rejected(IngestReasons.MISSING_DEVICE_IDENTITY), r)
    }

    @Test
    fun `a deviceId containing path separators is rejected before any file is written`() {
        val dao = FakeCaptureDao()
        val r1 = runBlocking { Replay.ingestExternal(text(header(5000L, "../../evil")).byteInputStream(), dirs(), dao) }
        val r2 = runBlocking { Replay.ingestExternal(text(header(6000L, "a/b")).byteInputStream(), dirs(), dao) }
        assertEquals(IngestResult.Rejected(IngestReasons.INVALID_DEVICE_ID), r1)
        assertEquals(IngestResult.Rejected(IngestReasons.INVALID_DEVICE_ID), r2)
        assertEquals(emptyList<String>(), File(tmp.root, "synced").list()?.toList() ?: emptyList<String>())
    }

    @Test
    fun `this device's own id is rejected`() {
        val r = runBlocking { Replay.ingestExternal(text(header(5000L, "local")).byteInputStream(), dirs(), FakeCaptureDao()) }
        assertEquals(IngestResult.Rejected(IngestReasons.LOCAL_DEVICE), r)
    }

    @Test
    fun `an id already owned by another device is rejected`() {
        val dao = FakeCaptureDao()
        runBlocking { Replay.ingestExternal(text(header(5000L, "w1")).byteInputStream(), dirs(), dao) }
        val r = runBlocking { Replay.ingestExternal(text(header(5000L, "w2")).byteInputStream(), dirs(), dao) }
        assertEquals(IngestResult.Rejected(IngestReasons.CONFLICT), r)
        assertEquals(false, File(tmp.root, "synced/w2/session-5000.ndjson").exists())
    }

    private class Generated(private val size: Long, private val at: (Long) -> Byte) : InputStream() {
        var served = 0L
            private set

        override fun read(): Int = if (served >= size) -1 else at(served++).toInt()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= size) return -1
            val n = minOf(len.toLong(), size - served).toInt()
            for (i in 0 until n) b[off + i] = at(served + i)
            served += n
            return n
        }
    }

    private fun incomingLeft() = File(tmp.root, "incoming").list()?.toList() ?: emptyList<String>()

    @Test
    fun `a stream with no newline is refused while it is copied, and nothing is left in incoming`() {
        val stream = Generated(64L shl 20) { 'a'.code.toByte() }
        val r = runBlocking { Replay.ingestExternal(stream, dirs(), FakeCaptureDao()) }
        assertEquals(IngestResult.Rejected(IngestReasons.NO_HEADER), r)
        assertTrue("read ${stream.served} bytes of a line no log has", stream.served <= IngestLimits.MAX_LINE_BYTES + (64 shl 10))
        assertEquals(emptyList<String>(), incomingLeft())
    }

    @Test
    fun `a stream over the size cap is refused as too large, and nothing is left in incoming`() {
        val newline = '\n'.code.toByte()
        val filler = 'a'.code.toByte()
        val stream = Generated(IngestLimits.MAX_BYTES + 1) { i -> if (i % 64 == 63L) newline else filler }
        val r = runBlocking { Replay.ingestExternal(stream, dirs(), FakeCaptureDao()) }
        assertEquals(IngestResult.Rejected(IngestReasons.TOO_LARGE), r)
        assertTrue("read ${stream.served} bytes", stream.served <= IngestLimits.MAX_BYTES + (64 shl 10))
        assertEquals(emptyList<String>(), incomingLeft())
        assertEquals(emptyList<String>(), File(tmp.root, "synced").list()?.toList() ?: emptyList<String>())
    }

    @Test
    fun `a log past the sample cap is refused as too large, and nothing is left behind`() {
        val dao = FakeCaptureDao()
        val log = tmp.newFile("past-the-cap.ndjson")
        log.bufferedWriter().use { w ->
            w.write(NdjsonCodec.encode(header(5000L, "w1")) + "\n")
            for (i in 0..Replay.MAX_SAMPLES) w.write("{\"y\":\"s\",\"t\":${6000L + i},\"e\":$i}\n")
        }
        assertTrue(log.length() < IngestLimits.MAX_BYTES)
        val r = runBlocking { Replay.ingestExternal(log.inputStream(), dirs(), dao) }
        assertEquals(IngestResult.Rejected(IngestReasons.TOO_LARGE), r)
        assertEquals(emptyList<String>(), incomingLeft())
        assertEquals(emptyList<String>(), File(tmp.root, "synced").list()?.toList() ?: emptyList<String>())
        assertEquals(emptyMap<Long, SessionEntity>(), dao.sessions)
    }

    private val corpus = File(
        if (File("src/testFixtures").isDirectory) File(".") else File("recorder/recorder"),
        "src/testFixtures/resources/corpus",
    )

    private fun widestCorpusLine(): Long = corpus.walkTopDown()
        .filter { it.isFile && it.extension == "ndjson" }
        .flatMap { it.readLines().asSequence() }
        .maxOf { it.toByteArray().size + 1L }

    @Test
    fun `the caps leave room for two days at one line a second, every line as wide as the widest recorded`() {
        val widestInDownstreamCorpus = 506L
        val widest = maxOf(widestCorpusLine(), widestInDownstreamCorpus)
        val twoDaysAtOneHertz = 48L * 3600
        assertTrue(
            "a $widest-byte line at one a second for two days, with half again on top, needs " +
                "${widest * twoDaysAtOneHertz * 3 / 2} bytes",
            IngestLimits.MAX_BYTES >= widest * twoDaysAtOneHertz * 3 / 2,
        )
        assertTrue(IngestLimits.MAX_LINE_BYTES >= widest * 100)
    }

    @Test
    fun `a log that ends without a newline still imports`() {
        val dao = FakeCaptureDao()
        val body = text(header(5000L, "w1"), RawLine.Sample(t = 6000, e = 20, level = 60)).trimEnd('\n')
        val r = runBlocking { Replay.ingestExternal(body.byteInputStream(), dirs(), dao) }
        assertTrue(r is IngestResult.Imported)
        assertEquals(1, dao.samplesBySession.getValue(5000L).size)
    }

    @Test
    fun `garbage is rejected with NO_HEADER and leaves nothing behind`() {
        val r = runBlocking { Replay.ingestExternal("not ndjson\n".byteInputStream(), dirs(), FakeCaptureDao()) }
        assertEquals(IngestResult.Rejected(IngestReasons.NO_HEADER), r)
        assertEquals(emptyList<String>(), File(tmp.root, "synced").list()?.toList() ?: emptyList<String>())
        assertEquals(emptyList<String>(), File(tmp.root, "incoming").list()?.toList())
    }
}
