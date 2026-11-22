// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import io.github.allenbw.chargelog.capture.log.EventKinds
import io.github.allenbw.chargelog.capture.log.NdjsonCodec
import io.github.allenbw.chargelog.capture.log.RawLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileWriter

class ReplaySampleCapTest {

    @get:Rule val tmp = TemporaryFolder()

    private val header = RawLine.Header(
        schema = 2, samplerProfileId = "p1", deviceModel = "test", osRelease = "16",
        appVersion = "0.1.0", tickMs = 1000, sessionStartWallClockMs = START,
    )

    private fun sampleLine(i: Int) = "{\"y\":\"s\",\"t\":${START + i},\"e\":$i}\n"

    private fun minimalSamples(file: File, count: Int, from: Int = 0) = file.bufferedWriter().use { w ->
        w.write(NdjsonCodec.encode(header) + "\n")
        for (i in from until from + count) w.write(sampleLine(i))
    }

    private fun appendSamples(file: File, indices: IntRange) =
        FileWriter(file, true).buffered().use { w -> indices.forEach { w.write(sampleLine(it)) } }

    @Test fun `a log of minimal sample lines past the cap is refused, not read whole`() {
        val f = File(tmp.root, "session-$START.ndjson").also { minimalSamples(it, Replay.MAX_SAMPLES + 1) }
        val refused = assertThrows(Replay.TooManySamples::class.java) { Replay.parse(f) }
        assertEquals(f, refused.file)
    }

    @Test fun `a log that grows past the cap keeps its row, is noted once, and is not read again`() {
        val dirs = LogDirs(tmp.root, File(tmp.root, "synced"), localDeviceId = "local")
        val dao = FakeCaptureDao()
        val f = File(tmp.root, "session-$START.ndjson").also { minimalSamples(it, EARLY) }
        runBlocking { Replay.reconcile(dirs, dao) }
        val early = dao.sessions.getValue(START)
        assertEquals(EndReasonsForReplay.TRUNCATED, early.endReason)

        val earlyRows = runBlocking { dao.samples(START) }
        assertEquals(EARLY, earlyRows.size)

        appendSamples(f, EARLY..Replay.MAX_SAMPLES)
        assertEquals(0, runBlocking { Replay.reconcile(dirs, dao) })
        assertEquals("the row it had was replaced", early, dao.sessions.getValue(START))
        assertEquals(earlyRows, runBlocking { dao.samples(START) })
        assertEquals("the refusal was not noted once", 1, refusalsNoted())

        rewriteUnderCap(f, paddedTo = f.length())
        assertEquals(REWRITTEN, Replay.parse(f)!!.samples.size)
        assertEquals("a log refused for its size was read again", 0, runBlocking { Replay.reconcile(dirs, dao) })
        assertEquals("a log refused for its size was read again", early, dao.sessions.getValue(START))
        assertEquals("a log refused for its size was read again", earlyRows, runBlocking { dao.samples(START) })
        assertEquals("the refusal was noted again", 1, refusalsNoted())
    }

    @Test fun `a log refused for its size is read again once it gets shorter`() {
        val dirs = LogDirs(tmp.root, File(tmp.root, "synced"), localDeviceId = "local")
        val dao = FakeCaptureDao()
        val f = File(tmp.root, "session-$START.ndjson").also { minimalSamples(it, Replay.MAX_SAMPLES + 1) }
        assertEquals(0, runBlocking { Replay.reconcile(dirs, dao) })
        assertEquals(1, refusalsNoted())
        val refusedLength = f.length()

        minimalSamples(f, REWRITTEN, from = EARLY)
        assertTrue("the rewritten log is not shorter", f.length() < refusedLength)
        assertEquals("a log that got shorter was not read again", 1, runBlocking { Replay.reconcile(dirs, dao) })
        assertEquals(START + EARLY + REWRITTEN - 1, dao.sessions.getValue(START).endedAtMs)
        val rows = runBlocking { dao.samples(START) }
        assertEquals((EARLY until EARLY + REWRITTEN).map { it.toLong() }, rows.map { it.elapsedRealtimeMs })
        assertEquals("the refusal was noted again", 1, refusalsNoted())
    }

    private fun rewriteUnderCap(file: File, paddedTo: Long) {
        minimalSamples(file, REWRITTEN, from = EARLY)
        val blankLines = "\n".repeat(BLANK_CHUNK)
        FileWriter(file, true).buffered().use { w ->
            var short = paddedTo - file.length()
            while (short > 0) {
                val n = minOf(short, BLANK_CHUNK.toLong()).toInt()
                w.write(blankLines, 0, n)
                short -= n
            }
        }
        assertEquals(paddedTo, file.length())
    }

    private fun refusalsNoted(): Int =
        File(tmp.root, "events.ndjson").takeIf { it.exists() }?.readLines().orEmpty()
            .count { EventKinds.INGEST_TOO_MANY_SAMPLES in it }

    @Test fun `a log at the cap is read whole`() {
        val f = File(tmp.root, "session-$START.ndjson").also { minimalSamples(it, Replay.MAX_SAMPLES) }
        val p = Replay.parse(f)!!
        assertEquals(Replay.MAX_SAMPLES, p.samples.size)
        assertEquals(START + Replay.MAX_SAMPLES - 1, p.session.endedAtMs)
    }

    @Test fun `the cap is four days at one sample a second`() {
        assertEquals(4 * 24 * 60 * 60, Replay.MAX_SAMPLES)
    }

    @Test fun `samples written before the header keep their place and take the header's id`() {
        val f = File(tmp.root, "session-$START.ndjson")
        f.writeText(
            listOf(
                RawLine.Sample(t = START + 1, e = 1, level = 20),
                header,
                RawLine.Sample(t = START + 2, e = 2, level = 21),
            ).joinToString("") { NdjsonCodec.encode(it) + "\n" },
        )
        val p = Replay.parse(f)!!
        assertEquals(listOf(1L, 2L), p.samples.map { it.elapsedRealtimeMs })
        assertEquals(listOf(START, START), p.samples.map { it.sessionId })
        assertEquals(20, p.session.startLevel)
        assertEquals(21, p.session.endLevel)
    }

    private companion object {
        const val START = 1_790_000_000_000L
        const val EARLY = 10
        const val REWRITTEN = 3
        const val BLANK_CHUNK = 64 * 1024
    }
}
