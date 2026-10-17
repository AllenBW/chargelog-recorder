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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReplayCollisionTest {

    @get:Rule val tmp = TemporaryFolder()

    private val phone get() = File(tmp.root, "rawlog")
    private val synced get() = File(tmp.root, "synced")
    private val imported get() = File(tmp.root, "imported")

    private fun dirs() = LogDirs(phone, synced, "local", File(tmp.root, "incoming"), imported)

    private fun write(dir: File, deviceId: String?, start: Long = START, level: Int = 50): File {
        dir.mkdirs()
        val lines = listOf(
            RawLine.Header(
                schema = 2, samplerProfileId = "p1", deviceModel = "m-$deviceId", osRelease = "16",
                appVersion = "0.1.0", tickMs = 1000, sessionStartWallClockMs = start,
                deviceKind = if (deviceId == null || deviceId == "local") null else DeviceKinds.WATCH,
                deviceId = deviceId,
            ),
            RawLine.Sample(t = start + 1000, e = 10L, level = level),
            RawLine.Event(t = start + 2000, e = 20L, kind = EventKinds.SESSION_END),
        )
        val f = File(dir, "session-$start.ndjson")
        f.writeText(lines.joinToString("") { NdjsonCodec.encode(it) + "\n" })
        return f
    }

    private fun shown(dao: FakeCaptureDao, id: Long = START) = dao.sessions.getValue(id).deviceId

    private fun reconcile(dao: FakeCaptureDao) = runBlocking { Replay.reconcile(dirs(), dao) }

    private fun rebuild(dao: FakeCaptureDao) = runBlocking { Replay.projectAll(dirs(), dao) }

    private fun hidden(dao: FakeCaptureDao) = runBlocking { Replay.collisionHidden(dirs(), dao) }

    @Test
    fun `a rebuild keeps the charge that was showing when it lists second`() {
        write(File(synced, "b"), "b", level = 70)
        val dao = FakeCaptureDao()
        reconcile(dao)
        write(File(synced, "a"), "a", level = 30)
        reconcile(dao)
        assertEquals("b", shown(dao))

        rebuild(dao)

        assertEquals("b", shown(dao))
        assertEquals(70, dao.samplesBySession.getValue(START).single().level)
    }

    @Test
    fun `a rebuild keeps the charge that was showing when it lists first`() {
        write(File(synced, "a"), "a", level = 30)
        val dao = FakeCaptureDao()
        reconcile(dao)
        write(File(synced, "b"), "b", level = 70)
        reconcile(dao)

        rebuild(dao)

        assertEquals("a", shown(dao))
        assertEquals(30, dao.samplesBySession.getValue(START).single().level)
    }

    @Test
    fun `a rebuild keeps a showing synced charge over this phone's own recording`() {
        write(File(synced, "w1"), "w1")
        val dao = FakeCaptureDao()
        reconcile(dao)
        write(phone, "local")
        reconcile(dao)

        rebuild(dao)

        assertEquals("w1", shown(dao))
    }

    @Test
    fun `a rebuild keeps a showing import over a synced charge`() {
        write(File(imported, "b"), "b")
        val dao = FakeCaptureDao()
        reconcile(dao)
        write(File(synced, "a"), "a")

        rebuild(dao)

        assertEquals("b", shown(dao))
    }

    @Test
    fun `with nothing showing, this phone's own recording wins`() {
        write(File(synced, "a"), "a")
        write(File(imported, "0"), "0")
        write(phone, "local")
        val dao = FakeCaptureDao()

        rebuild(dao)

        assertEquals("local", shown(dao))
    }

    @Test
    fun `with nothing showing, a synced charge wins over an import whatever the ids`() {
        write(File(imported, "a"), "a")
        write(File(synced, "z"), "z")
        val dao = FakeCaptureDao()

        rebuild(dao)

        assertEquals("z", shown(dao))
    }

    @Test
    fun `with nothing showing, the lower device id wins among synced charges`() {
        write(File(synced, "b"), "b")
        write(File(synced, "a"), "a")
        val dao = FakeCaptureDao()

        rebuild(dao)

        assertEquals("a", shown(dao))
    }

    @Test
    fun `reconcile on an empty projection picks what a rebuild picks`() {
        write(File(imported, "a"), "a")
        write(File(synced, "c"), "c")
        write(File(synced, "b"), "b")
        val fresh = FakeCaptureDao()
        val rebuilt = FakeCaptureDao()

        reconcile(fresh)
        rebuild(rebuilt)

        assertEquals("b", shown(fresh))
        assertEquals(shown(rebuilt), shown(fresh))
    }

    @Test
    fun `a rebuild after reconcile changes nothing, and so does a reconcile after a rebuild`() {
        write(File(synced, "b"), "b")
        val dao = FakeCaptureDao()
        reconcile(dao)
        write(File(synced, "a"), "a")
        write(phone, "local")
        reconcile(dao)
        val before = dao.sessions.getValue(START)

        rebuild(dao)
        reconcile(dao)

        assertEquals(before, dao.sessions.getValue(START))
    }

    @Test
    fun `the scan finds exactly the file a collision hides`() {
        write(File(synced, "b"), "b")
        write(phone, "local", start = OTHER)
        val dao = FakeCaptureDao()
        reconcile(dao)
        val loser = write(File(synced, "a"), "a")
        File(synced, "c").mkdirs()
        File(synced, "c/session-$START.ndjson").writeText("not a log\n")
        reconcile(dao)

        assertEquals(listOf(loser), hidden(dao))
    }

    @Test
    fun `nothing is hidden when no ids collide`() {
        write(File(synced, "a"), "a")
        write(File(synced, "b"), "b", start = OTHER)
        val dao = FakeCaptureDao()
        reconcile(dao)

        assertEquals(emptyList<File>(), hidden(dao))
    }

    @Test
    fun `deleting hidden charges removes only the hidden file and keeps the showing charge and its rows`() {
        val winner = write(File(synced, "b"), "b", level = 70)
        val own = write(phone, "local", start = OTHER)
        val dao = FakeCaptureDao()
        reconcile(dao)
        val loser = write(File(synced, "a"), "a", level = 30)
        reconcile(dao)
        val row = dao.sessions.getValue(START)
        val samples = dao.samplesBySession.getValue(START).toList()

        val failed = runBlocking { Replay.deleteCollisionHidden(dirs(), dao) }

        assertEquals(0, failed)
        assertFalse(loser.exists())
        assertTrue(winner.exists())
        assertTrue(own.exists())
        assertEquals(row, dao.sessions.getValue(START))
        assertEquals(samples, dao.samplesBySession.getValue(START))
        assertEquals(setOf(START, OTHER), dao.sessions.keys)
        assertEquals(emptyList<Long>(), dao.deleteSessionRowCalls)
        assertEquals(emptyList<File>(), hidden(dao))
    }

    @Test
    fun `deleting a hidden import also removes the synced copy it shadowed`() {
        write(File(synced, "b"), "b")
        val dao = FakeCaptureDao()
        reconcile(dao)
        val shadowed = write(File(synced, "a"), "a")
        val import = write(File(imported, "a"), "a")
        reconcile(dao)
        assertEquals(listOf(import), hidden(dao))

        runBlocking { Replay.deleteCollisionHidden(dirs(), dao) }

        assertFalse(import.exists())
        assertFalse(shadowed.exists())
        reconcile(dao)
        assertEquals(emptyList<File>(), hidden(dao))
    }

    @Test
    fun `the kept id is spared`() {
        write(File(synced, "w1"), "w1")
        val dao = FakeCaptureDao()
        reconcile(dao)
        val recording = write(phone, "local")
        reconcile(dao)

        runBlocking { Replay.deleteCollisionHidden(dirs(), dao, keepId = START) }

        assertTrue(recording.exists())
        assertEquals(listOf(recording), hidden(dao))
    }

    private companion object {
        const val START = 1_000_000L
        const val OTHER = 2_000_000L
    }
}
