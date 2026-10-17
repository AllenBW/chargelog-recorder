// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import io.github.allenbw.chargelog.capture.log.DeviceKinds
import io.github.allenbw.chargelog.capture.log.EventKinds
import io.github.allenbw.chargelog.capture.log.NdjsonCodec
import io.github.allenbw.chargelog.capture.log.RawLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImportedShadowTest {
    @get:Rule val tmp = TemporaryFolder()

    private val synced get() = File(tmp.root, "synced")
    private val imported get() = File(tmp.root, "imported")
    private val name = "session-5000.ndjson"

    private fun dirs() = LogDirs(File(tmp.root, "rawlog"), synced, "local", File(tmp.root, "incoming"), imported)

    private fun body(deviceId: String, levels: List<Int>, ended: Boolean) = buildList {
        add(
            RawLine.Header(
                schema = 2, samplerProfileId = "w1", deviceModel = "Pixel Watch 5", osRelease = "16", appVersion = "0.5.0",
                tickMs = 1000, sessionStartWallClockMs = 5000L, deviceKind = DeviceKinds.WATCH, deviceId = deviceId,
            ),
        )
        levels.forEachIndexed { i, lvl -> add(RawLine.Sample(t = 6000L + i * 1000, e = 20L + i, level = lvl)) }
        if (ended) add(RawLine.Event(t = 99_000L, e = 99, kind = EventKinds.SESSION_END))
    }.joinToString("") { NdjsonCodec.encode(it) + "\n" }

    private fun put(root: File, deviceId: String, levels: List<Int>, ended: Boolean = true): File =
        File(File(root, deviceId).apply { mkdirs() }, name).apply { writeText(body(deviceId, levels, ended)) }

    private fun reconciled(dao: FakeCaptureDao = FakeCaptureDao()) = dao.also { runBlocking { Replay.reconcile(dirs(), it) } }

    private fun levels(dao: FakeCaptureDao) = dao.samplesBySession.getValue(5000L).map { it.level }

    @Test fun `while the import is held, a synced copy of the same session is not projected`() {
        put(imported, "w1", listOf(10, 20))
        put(synced, "w1", listOf(10, 20, 30, 40))
        val dao = reconciled()
        assertEquals(listOf(10, 20), levels(dao))
    }

    @Test fun `a transfer cut off part-way leaves the import and its row exactly as they were`() {
        val imp = put(imported, "w1", listOf(10, 20, 30))
        val dao = reconciled()
        put(synced, "w1", listOf(10), ended = false)
        reconciled(dao)
        assertTrue(imp.exists())
        assertEquals(listOf(10, 20, 30), levels(dao))
        assertEquals(EndReasonsForReplay.CLEAN, dao.sessions.getValue(5000L).endReason)
    }

    @Test fun `after a restart the import is still the copy that projects`() {
        val imp = put(imported, "w1", listOf(10, 20, 30))
        put(synced, "w1", listOf(10, 20, 30, 40, 50))
        val fresh = reconciled(FakeCaptureDao())
        assertTrue(imp.exists())
        assertEquals(listOf(10, 20, 30), levels(fresh))
    }

    @Test fun `Remove deletes only the imported file, and the reconcile after it reveals the synced copy`() {
        val imp = put(imported, "w1", listOf(10, 20))
        val syn = put(synced, "w1", listOf(10, 20, 30))
        val dao = reconciled()
        assertTrue(runBlocking { Replay.deleteImportedCopy(dirs(), dao, dao.sessions.getValue(5000L)) })
        assertFalse(imp.exists())
        assertTrue(syn.exists())
        assertEquals(listOf(10, 20, 30), levels(reconciled(dao)))
    }

    @Test fun `Forget deletes only the synced file and keeps the import's row`() {
        val imp = put(imported, "w1", listOf(10, 20))
        val syn = put(synced, "w1", listOf(10, 20, 30))
        val dao = reconciled()
        assertTrue(runBlocking { Replay.deleteSyncedCopy(dirs(), dao, dao.sessions.getValue(5000L)) })
        assertFalse(syn.exists())
        assertTrue(imp.exists())
        assertEquals(listOf(10, 20), levels(dao))
    }

    @Test fun `deleting a charge removes every copy, so neither resurrects`() {
        val imp = put(imported, "w1", listOf(10, 20))
        val syn = put(synced, "w1", listOf(10, 20, 30))
        val dao = reconciled()
        assertTrue(runBlocking { Replay.deleteSessionCompletely(dirs(), dao, dao.sessions.getValue(5000L)) })
        assertFalse(imp.exists())
        assertFalse(syn.exists())
        assertEquals(0, runBlocking { Replay.reconcile(dirs(), dao) })
    }

    @Test fun `when the import is the copy that cannot go, the row stays and still reads as imported`() {
        File(File(imported, "w1").apply { mkdirs() }, name).apply { mkdirs() }.also { File(it, "child").writeText("x") }
        val syn = put(synced, "w1", listOf(10, 20, 30))
        val dao = FakeCaptureDao()
        val row = stubRow()
        dao.sessions[5000L] = row
        assertFalse(runBlocking { Replay.deleteSessionCompletely(dirs(), dao, row) })
        assertFalse("the deletable copy went", syn.exists())
        assertTrue("the row stays while a copy remains", dao.sessions.containsKey(5000L))
        assertEquals(File(imported, "w1"), dirs().dirFor(row))
    }

    @Test fun `Forget with the synced file already gone still drops its row, so none is orphaned`() {
        val syn = put(synced, "w1", listOf(10, 20))
        val dao = reconciled()
        syn.delete()
        assertTrue(runBlocking { Replay.deleteSyncedCopy(dirs(), dao, dao.sessions.getValue(5000L)) })
        assertFalse(dao.sessions.containsKey(5000L))
    }

    @Test fun `Remove with the imported file already gone still drops its row`() {
        val imp = put(imported, "w1", listOf(10, 20))
        val dao = reconciled()
        imp.delete()
        assertTrue(runBlocking { Replay.deleteImportedCopy(dirs(), dao, dao.sessions.getValue(5000L)) })
        assertFalse(dao.sessions.containsKey(5000L))
    }

    @Test fun `deleting a charge removes the synced copy first, so a stuck one leaves the import whole`() {
        File(File(synced, "w1").apply { mkdirs() }, name).apply { mkdirs() }.also { File(it, "child").writeText("x") }
        val imp = put(imported, "w1", listOf(10, 20))
        val dao = reconciled()
        val row = dao.sessions.getValue(5000L)
        assertFalse(runBlocking { Replay.deleteSessionCompletely(dirs(), dao, row) })
        assertTrue("the import is untouched", imp.exists())
        assertEquals(File(imported, "w1"), dirs().dirFor(row))
        assertEquals(row, dao.sessions[5000L])
    }

    @Test fun `an ingest that meets a reconcile in progress waits for it, then refuses the synced partial it projected`() {
        put(synced, "w1", listOf(10), ended = false)
        val gate = CompletableDeferred<Unit>()
        val dao = GatedDao(gate)
        runBlocking {
            val reconcile = async(Dispatchers.IO) { Replay.reconcile(dirs(), dao) }
            while (!dao.entered) yield()
            val ingest = async(Dispatchers.IO) {
                Replay.ingestExternal(body("w1", listOf(10, 20, 30), ended = true).byteInputStream(), dirs(), dao)
            }
            assertNull("the ingest waits for the lock", withTimeoutOrNull(300) { ingest.await() })
            assertFalse(File(imported, "w1/$name").exists())
            gate.complete(Unit)
            reconcile.await()
            assertEquals(IngestResult.Rejected(IngestReasons.DUPLICATE), ingest.await())
        }
        assertFalse(File(imported, "w1/$name").exists())
        assertEquals(EndReasonsForReplay.TRUNCATED, dao.sessions.getValue(5000L).endReason)
    }

    private class GatedDao(private val gate: CompletableDeferred<Unit>, private val inner: FakeCaptureDao = FakeCaptureDao()) :
        CaptureDao by inner {
        @Volatile var entered = false
        val sessions get() = inner.sessions

        override suspend fun fileStates(): List<FileState> {
            entered = true
            gate.await()
            return inner.fileStates()
        }
    }

    private fun stubRow() = SessionEntity(
        id = 5000L, startedAtMs = 5000L, endedAtMs = 9000L, endReason = EndReasonsForReplay.CLEAN, samplerProfileId = "w1",
        schemaVersion = 2, startLevel = 10, endLevel = 30, startChargeCounterRaw = null, endChargeCounterRaw = null,
        sourceFile = name, deviceKind = DeviceKinds.WATCH, deviceId = "w1",
    )


    @Test fun `a truncated import is never promoted to its clean synced twin, however many reconciles pass`() {
        put(imported, "w1", listOf(10, 20), ended = false)
        put(synced, "w1", listOf(10, 20, 30), ended = true)
        val dao = reconciled()
        repeat(3) { runBlocking { Replay.reconcile(dirs(), dao) } }
        assertEquals(setOf(10, 20), levels(dao).toSet())
        assertEquals(EndReasonsForReplay.TRUNCATED, dao.sessions.getValue(5000L).endReason)
        assertEquals(File(imported, "w1"), dirs().dirFor(dao.sessions.getValue(5000L)))
    }

    @Test fun `a full rebuild projects the import over its synced twin, the same as a reconcile`() {
        put(imported, "w1", listOf(10, 20))
        put(synced, "w1", listOf(10, 20, 30))
        val dao = FakeCaptureDao()
        assertEquals(1, runBlocking { Replay.projectAll(dirs(), dao) })
        assertEquals(listOf(10, 20), levels(dao))
    }

    @Test fun `an import removed behind the app's back leaves the row until the session is re-parsed`() {
        val imp = put(imported, "w1", listOf(10, 20))
        put(synced, "w1", listOf(10, 20, 30))
        val dao = reconciled()
        imp.delete()
        runBlocking { Replay.reconcile(dirs(), dao) }
        assertEquals("the residual the domain doc records", listOf(10, 20), levels(dao))
        assertEquals(File(synced, "w1"), dirs().dirFor(dao.sessions.getValue(5000L)))
    }
}
