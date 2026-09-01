// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DischargeLogTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `reading a log that was never written is empty`() {
        assertTrue(DischargeLog(tmp.root).read().isEmpty())
    }

    @Test fun `append then read round-trips the readings in order`() {
        val log = DischargeLog(tmp.root)
        log.append(wallClockMs = 1_000, elapsedMs = 10, level = 80, screenOn = true)
        log.append(wallClockMs = 2_000, elapsedMs = 20, level = 79, screenOn = false)
        log.append(wallClockMs = 3_000, elapsedMs = 30, level = 78) // screenOn unknown

        assertEquals(
            listOf(
                DischargeSample(wallClockMs = 1_000, level = 80, screenOn = true),
                DischargeSample(wallClockMs = 2_000, level = 79, screenOn = false),
                DischargeSample(wallClockMs = 3_000, level = 78, screenOn = null),
            ),
            log.read(),
        )
    }

    @Test fun `each append is durable without any close`() {
        val log = DischargeLog(tmp.root)
        log.append(wallClockMs = 1_000, elapsedMs = 10, level = 50)
        // A fresh reader (as after a process restart) sees the line already on disk.
        assertEquals(1, DischargeLog(tmp.root).read().size)
    }

    @Test fun `appends accumulate into a single file across instances`() {
        DischargeLog(tmp.root).append(wallClockMs = 1_000, elapsedMs = 10, level = 60)
        DischargeLog(tmp.root).append(wallClockMs = 2_000, elapsedMs = 20, level = 59)
        val file = File(tmp.root, DischargeLog.FILE_NAME)
        assertEquals(2, file.readLines().size)
        assertEquals(2, DischargeLog(tmp.root).read().size)
    }

    @Test fun `a null screenOn is kept off the wire and reads back null`() {
        DischargeLog(tmp.root).append(wallClockMs = 1_000, elapsedMs = 10, level = 40)
        val line = File(tmp.root, DischargeLog.FILE_NAME).readLines().single()
        assertTrue("screenOn should be omitted when unknown, was: $line", !line.contains("screenOn"))
        assertEquals(null, DischargeLog(tmp.root).read().single().screenOn)
    }

    @Test fun `the log compacts to its newest half once it outgrows maxBytes`() {
        // Nothing else ever prunes this file (S6 §1 defect 6): Retention is session math and
        // delete-all sweeps session files, so the cap is the log's own job. Every line here is a
        // constant 29 bytes ({"t":1000,"e":10,"level":80}\n), so five lines are 145 bytes — under
        // the 150-byte cap — and the sixth append crosses it, compacting to the newest half (3).
        val log = DischargeLog(tmp.root, maxBytes = 150)
        var t = 1_000L
        var e = 10L
        for (level in 80 downTo 75) {
            log.append(wallClockMs = t, elapsedMs = e, level = level)
            t += 1_000; e += 10
        }

        assertEquals(
            listOf(
                DischargeSample(wallClockMs = 4_000, level = 77),
                DischargeSample(wallClockMs = 5_000, level = 76),
                DischargeSample(wallClockMs = 6_000, level = 75),
            ),
            log.read(),
        )
        assertTrue(File(tmp.root, DischargeLog.FILE_NAME).length() < 150)
    }

    @Test fun `a compacted log keeps accepting appends`() {
        val log = DischargeLog(tmp.root, maxBytes = 150)
        var t = 1_000L
        for (level in 80 downTo 75) { log.append(wallClockMs = t, elapsedMs = 10, level = level); t += 1_000 }
        log.append(wallClockMs = t, elapsedMs = 10, level = 74)
        assertEquals(74, log.read().last().level)
    }

    @Test fun `clearing the log removes the file`() {
        val log = DischargeLog(tmp.root)
        log.append(wallClockMs = 1_000, elapsedMs = 10, level = 50)
        log.clear()
        assertTrue(log.read().isEmpty())
        assertTrue(!File(tmp.root, DischargeLog.FILE_NAME).exists())
    }

    @Test fun `a torn trailing line is skipped rather than failing the read`() {
        val log = DischargeLog(tmp.root)
        log.append(wallClockMs = 1_000, elapsedMs = 10, level = 30)
        // Simulate a crash mid-write appending a partial JSON line after the good one.
        File(tmp.root, DischargeLog.FILE_NAME).appendText("{\"t\":2000,\"e\":20,\"lev\n")

        val read = log.read()
        assertEquals(1, read.size)
        assertEquals(30, read.single().level)
    }
}
