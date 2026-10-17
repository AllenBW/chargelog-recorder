// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import io.github.allenbw.chargelog.capture.log.DeviceKinds
import io.github.allenbw.chargelog.capture.log.EventKinds
import io.github.allenbw.chargelog.capture.log.NdjsonCodec
import io.github.allenbw.chargelog.capture.log.RawLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImportedRootTest {
    @get:Rule val tmp = TemporaryFolder()

    private val synced get() = File(tmp.root, "synced")
    private val imported get() = File(tmp.root, "imported")

    private fun dirs(withImports: Boolean = true) = LogDirs(
        File(tmp.root, "rawlog"), synced, localDeviceId = "local",
        incoming = File(tmp.root, "incoming"),
        importedRoot = if (withImports) imported else null,
    )

    private fun session(deviceId: String?, start: Long = 5000L) = SessionEntity(
        id = start, startedAtMs = start, endedAtMs = null, endReason = null, samplerProfileId = "p", schemaVersion = 2,
        startLevel = null, endLevel = null, startChargeCounterRaw = null, endChargeCounterRaw = null,
        sourceFile = "session-$start.ndjson", deviceKind = DeviceKinds.WATCH, deviceId = deviceId,
    )

    private fun body(start: Long, deviceId: String) = listOf(
        RawLine.Header(
            schema = 2, samplerProfileId = "w1", deviceModel = "Pixel 9", osRelease = "16", appVersion = "0.5.0",
            tickMs = 1000, sessionStartWallClockMs = start, deviceKind = DeviceKinds.PHONE, deviceId = deviceId,
        ),
        RawLine.Sample(t = start + 1000, e = 20, level = 60),
        RawLine.Event(t = start + 2000, e = 30, kind = EventKinds.SESSION_END),
    ).joinToString("") { NdjsonCodec.encode(it) + "\n" }

    private fun place(root: File, deviceId: String, start: Long): File =
        File(File(root, deviceId).apply { mkdirs() }, "session-$start.ndjson").apply { writeText(body(start, deviceId)) }

    @Test
    fun `all lists imported subdirectories after synced ones`() {
        File(synced, "w1").mkdirs()
        File(imported, "p2").mkdirs()
        File(imported, ".marker").writeText("x")
        val d = dirs()
        assertEquals(listOf(d.phone, File(synced, "w1"), File(imported, "p2")), d.all())
    }

    @Test
    fun `without an imported root nothing changes`() {
        File(imported, "p2").mkdirs()
        val d = dirs(withImports = false)
        assertEquals(listOf(d.phone), d.all())
        assertEquals(File(synced, "p2"), d.dirFor(session("p2")))
    }

    @Test
    fun `a foreign session held only under imported resolves there`() {
        place(imported, "p2", 5000L)
        assertEquals(File(imported, "p2"), dirs().dirFor(session("p2")))
    }

    @Test
    fun `a session with no file anywhere resolves to its synced dir`() {
        assertEquals(File(synced, "p2"), dirs().dirFor(session("p2")))
    }

    @Test
    fun `own sessions resolve to the phone dir whatever the roots hold`() {
        val d = dirs()
        assertEquals(d.phone, d.dirFor(session(null)))
        assertEquals(d.phone, d.dirFor(session("local")))
    }

    @Test
    fun `importedDir refuses a deviceId with path separators`() {
        assertThrows(IllegalArgumentException::class.java) { dirs().importedDir("../x") }
    }

    @Test
    fun `importedDir refuses when no imported root was given`() {
        assertThrows(IllegalStateException::class.java) { dirs(withImports = false).importedDir("p2") }
    }

    @Test
    fun `with an imported root a foreign log lands under imported, not synced`() {
        val dao = FakeCaptureDao()
        val r = runBlocking { Replay.ingestExternal(body(5000L, "p2").byteInputStream(), dirs(), dao) }
        assertTrue(r is IngestResult.Imported)
        assertTrue(File(imported, "p2/session-5000.ndjson").exists())
        assertFalse(File(synced, "p2").exists())
        assertEquals("p2", dao.sessions.getValue(5000L).deviceId)
    }

    @Test
    fun `reconcile projects a session file found under imported`() {
        place(imported, "p2", 5000L)
        val dao = FakeCaptureDao()
        val count = runBlocking { Replay.reconcile(dirs(), dao) }
        assertEquals(1, count)
        assertEquals("p2", dao.sessions.getValue(5000L).deviceId)
    }

    @Test
    fun `a second reconcile does not reparse a finished imported session`() {
        place(imported, "p2", 5000L)
        val dao = FakeCaptureDao()
        runBlocking { Replay.reconcile(dirs(), dao) }
        assertEquals(0, runBlocking { Replay.reconcile(dirs(), dao) })
    }

    @Test
    fun `an import of a session already synced is refused and writes nothing`() {
        place(synced, "w1", 5000L)
        val dao = FakeCaptureDao()
        val r = runBlocking { Replay.ingestExternal(body(5000L, "w1").byteInputStream(), dirs(), dao) }
        assertEquals(IngestResult.Rejected(IngestReasons.DUPLICATE), r)
        assertFalse(File(imported, "w1").exists())
        assertTrue(dao.sessions.isEmpty())
    }

    @Test
    fun `importing the same log twice is refused the second time`() {
        val dao = FakeCaptureDao()
        val first = runBlocking { Replay.ingestExternal(body(5000L, "p2").byteInputStream(), dirs(), dao) }
        val second = runBlocking { Replay.ingestExternal(body(5000L, "p2").byteInputStream(), dirs(), dao) }
        assertTrue(first is IngestResult.Imported)
        assertEquals(IngestResult.Rejected(IngestReasons.DUPLICATE), second)
        assertEquals(listOf("session-5000.ndjson"), File(imported, "p2").list()!!.toList())
    }

    @Test
    fun `without an imported root a re-import still replaces the synced copy, as before`() {
        place(synced, "w1", 5000L)
        val r = runBlocking { Replay.ingestExternal(body(5000L, "w1").byteInputStream(), dirs(withImports = false), FakeCaptureDao()) }
        assertTrue(r is IngestResult.Imported)
    }

    @Test
    fun `deleting an imported session deletes its imported file and nothing synced`() {
        val keep = place(synced, "p2", 4000L)
        val gone = place(imported, "p2", 5000L)
        val dao = FakeCaptureDao()
        runBlocking { Replay.reconcile(dirs(), dao) }
        assertTrue(runBlocking { Replay.deleteSessionCompletely(dirs(), dao, dao.sessions.getValue(5000L)) })
        assertFalse(gone.exists())
        assertTrue(keep.exists())
        assertEquals(setOf(4000L), dao.sessions.keys)
    }

    @Test
    fun `deleting a synced session deletes its synced file and nothing imported`() {
        val gone = place(synced, "p2", 4000L)
        val keep = place(imported, "p2", 5000L)
        val dao = FakeCaptureDao()
        runBlocking { Replay.reconcile(dirs(), dao) }
        assertTrue(runBlocking { Replay.deleteSessionCompletely(dirs(), dao, dao.sessions.getValue(4000L)) })
        assertFalse(gone.exists())
        assertTrue(keep.exists())
        assertEquals(0, runBlocking { Replay.reconcile(dirs(), dao) })
    }

    @Test
    fun `an empty synced stub does not count as held, so the import is accepted`() {
        File(File(synced, "w1").apply { mkdirs() }, "session-5000.ndjson").writeText("")
        val r = runBlocking { Replay.ingestExternal(body(5000L, "w1").byteInputStream(), dirs(), FakeCaptureDao()) }
        assertTrue(r is IngestResult.Imported)
        assertTrue(File(imported, "w1/session-5000.ndjson").exists())
    }

    @Test
    fun `a held file with no parseable header does not count as held`() {
        File(File(imported, "w1").apply { mkdirs() }, "session-5000.ndjson").writeText("not a header\n")
        val r = runBlocking { Replay.ingestExternal(body(5000L, "w1").byteInputStream(), dirs(), FakeCaptureDao()) }
        assertTrue(r is IngestResult.Imported)
        assertEquals(body(5000L, "w1"), File(imported, "w1/session-5000.ndjson").readText())
    }

    @Test
    fun `importedCopy names the file a foreign session would have under imported`() {
        val d = dirs()
        assertEquals(File(imported, "p2/session-5000.ndjson"), d.importedCopy(session("p2")))
        assertNull(d.importedCopy(session(null)))
        assertNull(d.importedCopy(session("local")))
        assertNull(dirs(withImports = false).importedCopy(session("p2")))
    }

    @Test
    fun `deleteImportedCopy without an imported root does nothing and says so`() {
        val syn = place(synced, "p2", 5000L)
        val dao = FakeCaptureDao()
        runBlocking { Replay.reconcile(dirs(withImports = false), dao) }
        val row = dao.sessions.getValue(5000L)
        assertFalse(runBlocking { Replay.deleteImportedCopy(dirs(withImports = false), dao, row) })
        assertTrue(syn.exists())
        assertEquals(row, dao.sessions[5000L])
    }

    @Test
    fun `deleteImportedCopy never drops the row of a session held only as synced`() {
        val syn = place(synced, "p2", 5000L)
        val dao = FakeCaptureDao()
        runBlocking { Replay.reconcile(dirs(), dao) }
        val row = dao.sessions.getValue(5000L)
        assertTrue(runBlocking { Replay.deleteImportedCopy(dirs(), dao, row) })
        assertTrue(syn.exists())
        assertEquals(row, dao.sessions[5000L])
    }


    @Test
    fun `copiesOf names the synced copy first, then the import, and only the phone file for own rows`() {
        val d = dirs()
        assertEquals(listOf(File(synced, "p2/session-5000.ndjson"), File(imported, "p2/session-5000.ndjson")), d.copiesOf(session("p2")))
        assertEquals(listOf(File(synced, "p2/session-5000.ndjson")), dirs(withImports = false).copiesOf(session("p2")))
        assertEquals(listOf(File(File(tmp.root, "rawlog"), "session-5000.ndjson")), d.copiesOf(session(null)))
        assertEquals(listOf(File(File(tmp.root, "rawlog"), "session-5000.ndjson")), d.copiesOf(session("local")))
    }

    @Test
    fun `a row whose device id is not a directory name maps to no file and its delete drops only the row`() {
        val bad = session("../evil")
        val d = dirs()
        assertEquals(emptyList<File>(), d.copiesOf(bad))
        assertNull(d.importedCopy(bad))
        val dao = FakeCaptureDao()
        dao.sessions[bad.id] = bad
        assertTrue(runBlocking { Replay.deleteSessionCompletely(d, dao, bad) })
        assertFalse(dao.sessions.containsKey(bad.id))
        assertFalse(runBlocking { Replay.deleteSyncedCopy(d, dao, bad) })
        assertFalse(runBlocking { Replay.deleteImportedCopy(d, dao, bad) })
    }

    @Test
    fun `a header naming an invalid device id is never projected, and the refusal is logged`() {
        val d = dirs()
        File(File(synced, "w1").apply { mkdirs() }, "session-5000.ndjson").writeText(body(5000L, "../evil"))
        val dao = FakeCaptureDao()
        assertEquals(0, runBlocking { Replay.reconcile(d, dao) })
        assertTrue(dao.sessions.isEmpty())
        val events = File(File(tmp.root, "rawlog"), "events.ndjson").readText()
        assertTrue(events, EventKinds.INGEST_INVALID_ID in events)
        assertEquals(0, runBlocking { Replay.projectAll(d, dao) })
    }

    @Test
    fun `deleteSyncedCopy on this device's own row does nothing and says so`() {
        val d = dirs()
        val own = File(File(tmp.root, "rawlog").apply { mkdirs() }, "session-5000.ndjson").apply { writeText(body(5000L, "local")) }
        val dao = FakeCaptureDao()
        runBlocking { Replay.reconcile(d, dao) }
        val row = dao.sessions.getValue(5000L)
        assertFalse(runBlocking { Replay.deleteSyncedCopy(d, dao, row) })
        assertFalse(runBlocking { Replay.deleteSyncedCopy(d, dao, row.copy(deviceId = null)) })
        assertTrue(own.exists())
        assertEquals(row, dao.sessions[5000L])
    }

    @Test
    fun `a held file whose last line is torn still counts as held, and only its header is read`() {
        val torn = body(5000L, "w1").trimEnd('\n').dropLast(7)
        File(File(synced, "w1").apply { mkdirs() }, "session-5000.ndjson").writeText(torn)
        val r = runBlocking { Replay.ingestExternal(body(5000L, "w1").byteInputStream(), dirs(), FakeCaptureDao()) }
        assertEquals(IngestResult.Rejected(IngestReasons.DUPLICATE), r)
        assertFalse(File(imported, "w1/session-5000.ndjson").exists())
    }

    @Test
    fun `a file whose header comes after a garbled first line still counts as held`() {
        File(File(imported, "w1").apply { mkdirs() }, "session-5000.ndjson").writeText("garbage\n" + body(5000L, "w1"))
        val r = runBlocking { Replay.ingestExternal(body(5000L, "w1").byteInputStream(), dirs(), FakeCaptureDao()) }
        assertEquals(IngestResult.Rejected(IngestReasons.DUPLICATE), r)
    }
}
