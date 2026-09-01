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
        // The scratch dir lives beside synced/, not inside it, so a rejection
        // leaves synced/ untouched — nothing for LogDirs.all() to mistake for a device.
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

    @Test
    fun `garbage is rejected with NO_HEADER and leaves nothing behind`() {
        val r = runBlocking { Replay.ingestExternal("not ndjson\n".byteInputStream(), dirs(), FakeCaptureDao()) }
        assertEquals(IngestResult.Rejected(IngestReasons.NO_HEADER), r)
        assertEquals(emptyList<String>(), File(tmp.root, "synced").list()?.toList() ?: emptyList<String>()) // no device dirs
        assertEquals(emptyList<String>(), File(tmp.root, "incoming").list()?.toList())             // scratch is empty
    }
}
