// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import io.github.allenbw.chargelog.capture.log.DeviceKinds
import io.github.allenbw.chargelog.capture.log.EventKinds
import io.github.allenbw.chargelog.capture.log.NdjsonCodec
import io.github.allenbw.chargelog.capture.log.RawLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReplayReconcileTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun dirs() = LogDirs(tmp.root, File(tmp.root, "synced"), localDeviceId = "local")

    private fun header(sessionStartWallClockMs: Long, deviceId: String? = null) = RawLine.Header(
        schema = 2, samplerProfileId = "p1", deviceModel = "test", osRelease = "16",
        appVersion = "0.1.0", tickMs = 1000, sessionStartWallClockMs = sessionStartWallClockMs,
        deviceKind = if (deviceId == null) null else DeviceKinds.WATCH, deviceId = deviceId,
    )

    private fun sample(t: Long, e: Long, level: Int? = null) = RawLine.Sample(t = t, e = e, level = level)

    private fun event(t: Long, e: Long, kind: String) = RawLine.Event(t = t, e = e, kind = kind)

    private fun writeSession(vararg lines: RawLine, dir: File = tmp.root): File {
        val h = lines.first() as RawLine.Header
        val f = File(dir, "session-${h.sessionStartWallClockMs}.ndjson")
        f.writeText(lines.joinToString("") { NdjsonCodec.encode(it) + "\n" })
        return f
    }

    @Test
    fun `fresh dao plus two session files reconciles both`() {
        writeSession(
            header(1000L),
            sample(t = 2000L, e = 20L, level = 50),
            event(t = 3000L, e = 30L, kind = EventKinds.SESSION_END),
        )
        writeSession(
            header(2000L),
            sample(t = 3000L, e = 20L, level = 60),
            event(t = 4000L, e = 30L, kind = EventKinds.SESSION_END),
        )
        val dao = FakeCaptureDao()

        val count = runBlocking { Replay.reconcile(dirs(), dao) }

        assertEquals(2, count)
        assertEquals(2, dao.sessions.size)
        assertEquals(2, dao.upsertSessionCalls)
    }

    @Test
    fun `second run with no changes upserts nothing`() {
        writeSession(
            header(1000L),
            sample(t = 2000L, e = 20L, level = 50),
            event(t = 3000L, e = 30L, kind = EventKinds.SESSION_END),
        )
        val dao = FakeCaptureDao()
        runBlocking { Replay.reconcile(dirs(), dao) }
        val callsAfterFirst = dao.upsertSessionCalls

        val count = runBlocking { Replay.reconcile(dirs(), dao) }

        assertEquals(0, count)
        assertEquals(callsAfterFirst, dao.upsertSessionCalls)
    }

    @Test
    fun `truncated row is re-parsed once the log gains a terminal event`() {
        val f = writeSession(header(1000L), sample(t = 2000L, e = 20L, level = 50))
        val dao = FakeCaptureDao()
        dao.sessions[1000L] = SessionEntity(
            id = 1000L, startedAtMs = 1000L, endedAtMs = null,
            endReason = EndReasonsForReplay.TRUNCATED, samplerProfileId = "p1", schemaVersion = 1,
            startLevel = 50, endLevel = 50, startChargeCounterRaw = null, endChargeCounterRaw = null,
            sourceFile = f.name,
        )

        val count = runBlocking { Replay.reconcile(dirs(), dao) }

        assertEquals(1, count)
    }

    @Test
    fun `clean row is skipped`() {
        val f = writeSession(
            header(1000L),
            sample(t = 2000L, e = 20L, level = 50),
            event(t = 3000L, e = 30L, kind = EventKinds.SESSION_END),
        )
        val dao = FakeCaptureDao()
        dao.sessions[1000L] = SessionEntity(
            id = 1000L, startedAtMs = 1000L, endedAtMs = 3000L,
            endReason = EndReasonsForReplay.CLEAN, samplerProfileId = "p1", schemaVersion = 1,
            startLevel = 50, endLevel = 50, startChargeCounterRaw = null, endChargeCounterRaw = null,
            sourceFile = f.name,
        )

        val count = runBlocking { Replay.reconcile(dirs(), dao) }

        assertEquals(0, count)
        assertEquals(0, dao.upsertSessionCalls)
    }

    @Test
    fun `non-session files in the directory are ignored`() {
        writeSession(
            header(1000L),
            sample(t = 2000L, e = 20L, level = 50),
            event(t = 3000L, e = 30L, kind = EventKinds.SESSION_END),
        )
        File(tmp.root, "notes.txt").writeText("not a session log")
        File(tmp.root, "session-1000.ndjson.bak").writeText("stray backup")
        val dao = FakeCaptureDao()

        val count = runBlocking { Replay.reconcile(dirs(), dao) }

        assertEquals(1, count)
    }

    @Test
    fun `a directory matching the session file name pattern is skipped without crashing`() {
        writeSession(
            header(1000L),
            sample(t = 2000L, e = 20L, level = 50),
            event(t = 3000L, e = 30L, kind = EventKinds.SESSION_END),
        )
        File(tmp.root, "session-2000.ndjson").mkdir()
        val dao = FakeCaptureDao()

        val count = runBlocking { Replay.reconcile(dirs(), dao) }

        assertEquals(1, count)
        assertEquals(1, dao.sessions.size)
    }

    // --- deleteSessionCompletely ---

    @Test
    fun `deleteSessionCompletely deletes the raw file and the db rows on success`() {
        val f = writeSession(
            header(1000L),
            sample(t = 2000L, e = 20L, level = 50),
            event(t = 3000L, e = 30L, kind = EventKinds.SESSION_END),
        )
        val dao = FakeCaptureDao()
        runBlocking { Replay.reconcile(dirs(), dao) }
        val session = dao.sessions.getValue(1000L)

        val result = runBlocking { Replay.deleteSessionCompletely(dirs(), dao, session) }

        assertEquals(true, result)
        assertEquals(false, f.exists())
        assertEquals(false, dao.sessions.containsKey(1000L))
        assertEquals(listOf(1000L), dao.deleteSamplesForCalls)
        assertEquals(listOf(1000L), dao.deleteSessionRowCalls)
    }

    @Test
    fun `deleteSessionCompletely returns false and leaves rows untouched when the file can't be deleted`() {
        // A non-empty directory in place of the file makes File#delete() fail.
        val dirAsFile = File(tmp.root, "session-1000.ndjson")
        dirAsFile.mkdir()
        File(dirAsFile, "child").writeText("stray")
        val dao = FakeCaptureDao()
        val session = SessionEntity(
            id = 1000L, startedAtMs = 1000L, endedAtMs = 3000L,
            endReason = EndReasonsForReplay.CLEAN, samplerProfileId = "p1", schemaVersion = 1,
            startLevel = 50, endLevel = 50, startChargeCounterRaw = null, endChargeCounterRaw = null,
            sourceFile = dirAsFile.name,
        )
        dao.sessions[1000L] = session

        val result = runBlocking { Replay.deleteSessionCompletely(dirs(), dao, session) }

        assertEquals(false, result)
        assertEquals(true, dirAsFile.exists())
        assertEquals(true, dao.sessions.containsKey(1000L))
        assertEquals(emptyList<Long>(), dao.deleteSessionRowCalls)
        assertEquals(emptyList<Long>(), dao.deleteSamplesForCalls)
    }

    @Test
    fun `deleteSessionCompletely treats an already-missing file as success and still deletes rows`() {
        val dao = FakeCaptureDao()
        val session = SessionEntity(
            id = 1000L, startedAtMs = 1000L, endedAtMs = 3000L,
            endReason = EndReasonsForReplay.CLEAN, samplerProfileId = "p1", schemaVersion = 1,
            startLevel = 50, endLevel = 50, startChargeCounterRaw = null, endChargeCounterRaw = null,
            sourceFile = "session-missing.ndjson",
        )
        dao.sessions[1000L] = session

        val result = runBlocking { Replay.deleteSessionCompletely(dirs(), dao, session) }

        assertEquals(true, result)
        assertEquals(false, dao.sessions.containsKey(1000L))
        assertEquals(listOf(1000L), dao.deleteSessionRowCalls)
    }

    // --- multi-directory / cross-device ---

    @Test
    fun `reconcile projects the phone dir and every synced device dir`() {
        writeSession(header(1000L), sample(2000L, 20L, 50), event(3000L, 30L, EventKinds.SESSION_END))
        val w1 = File(tmp.root, "synced/w1").apply { mkdirs() }
        writeSession(header(5000L, deviceId = "w1"), sample(6000L, 20L, 60), event(7000L, 30L, EventKinds.SESSION_END), dir = w1)
        val dao = FakeCaptureDao()

        val count = runBlocking { Replay.reconcile(dirs(), dao) }

        assertEquals(2, count)
        assertEquals(DeviceKinds.WATCH, dao.sessions.getValue(5000L).deviceKind)
        assertEquals("w1", dao.sessions.getValue(5000L).deviceId)
    }

    @Test
    fun `an id collision across devices is rejected, logged, and never merged`() {
        writeSession(header(1000L), sample(2000L, 20L, 50), event(3000L, 30L, EventKinds.SESSION_END))
        val w1 = File(tmp.root, "synced/w1").apply { mkdirs() }
        writeSession(header(1000L, deviceId = "w1"), sample(2000L, 20L, 90), event(3000L, 30L, EventKinds.SESSION_END), dir = w1)
        val dao = FakeCaptureDao()

        val count = runBlocking { Replay.reconcile(dirs(), dao) }

        assertEquals(1, count)
        assertEquals(null, dao.sessions.getValue(1000L).deviceId)      // the phone's row survived untouched
        assertEquals(50, dao.samplesBySession.getValue(1000L).single().level)
        val events = File(tmp.root, "events.ndjson").readLines().map { NdjsonCodec.decode(it) as RawLine.Event }
        assertEquals(EventKinds.INGEST_CONFLICT, events.single().kind)
        assertEquals("id=1000,deviceId=w1,existingDeviceId=null", events.single().detail)
    }

    @Test
    fun `projectAll clears once and parses every directory`() {
        writeSession(header(1000L), sample(2000L, 20L, 50), event(3000L, 30L, EventKinds.SESSION_END))
        val w1 = File(tmp.root, "synced/w1").apply { mkdirs() }
        writeSession(header(5000L, deviceId = "w1"), sample(6000L, 20L, 60), event(7000L, 30L, EventKinds.SESSION_END), dir = w1)
        val dao = FakeCaptureDao()
        // A stale row with no file behind it must not survive a rebuild.
        dao.sessions[42L] = SessionEntity(
            id = 42L, startedAtMs = 42L, endedAtMs = null, endReason = null, samplerProfileId = "p", schemaVersion = 1,
            startLevel = null, endLevel = null, startChargeCounterRaw = null, endChargeCounterRaw = null, sourceFile = "gone.ndjson",
        )

        val count = runBlocking { Replay.projectAll(dirs(), dao) }

        assertEquals(2, count)
        assertEquals(setOf(1000L, 5000L), dao.sessions.keys)
    }

    @Test
    fun `deleteSessionCompletely removes the file from the session's own device directory`() {
        val w1 = File(tmp.root, "synced/w1").apply { mkdirs() }
        val f = writeSession(header(5000L, deviceId = "w1"), sample(6000L, 20L, 60), event(7000L, 30L, EventKinds.SESSION_END), dir = w1)
        val dao = FakeCaptureDao()
        runBlocking { Replay.reconcile(dirs(), dao) }

        val ok = runBlocking { Replay.deleteSessionCompletely(dirs(), dao, dao.sessions.getValue(5000L)) }

        assertEquals(true, ok)
        assertEquals(false, f.exists())
        assertEquals(false, dao.sessions.containsKey(5000L))
    }
}
