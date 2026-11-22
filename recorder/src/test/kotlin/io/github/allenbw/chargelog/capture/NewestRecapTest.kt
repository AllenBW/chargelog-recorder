// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.capture.log.RawLogWriter
import io.github.allenbw.chargelog.measure.ChargeSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NewestRecapTest {

    @get:Rule val tmp = TemporaryFolder()

    private val recaps = NewestRecap()

    private fun log(startMs: Long) = File(tmp.root, RawLogWriter.sessionFileName(startMs)).apply { writeText("") }

    private fun recap(sessionId: Long, endedAtMs: Long = sessionId + 1) = SessionRecap(
        endedAtMs = endedAtMs, durationMs = 1, startLevel = 40, endLevel = 41, energyAh = null, peakW = null,
        source = ChargeSource.UNKNOWN, endReason = null, sessionId = sessionId,
    )

    @Test fun `a start-up read that finishes after a close never replaces the closed charge`() {
        val closed = recaps.offer(recaps.closing(), recap(sessionId = 200))
        val startup = recaps.offer(NewestRecap.STARTUP, recap(sessionId = 100))
        assertEquals(200L, recaps.current.recap?.sessionId)
        assertTrue(recaps.isNewest(closed))
        assertFalse(recaps.isNewest(startup))
    }

    @Test fun `closes rank in the order the pump closed them, whichever read finishes first`() {
        val first = recaps.closing()
        val second = recaps.closing()
        recaps.offer(second, recap(sessionId = 300))
        recaps.offer(first, recap(sessionId = 200))
        assertEquals(300L, recaps.current.recap?.sessionId)
    }

    @Test fun `a close ranks above the start-up read even when the wall clock went back between them`() {
        recaps.offer(NewestRecap.STARTUP, recap(sessionId = 500, endedAtMs = 900))
        recaps.offer(recaps.closing(), recap(sessionId = 100, endedAtMs = 200))
        assertEquals(100L, recaps.current.recap?.sessionId)
    }

    @Test fun `a closed charge whose log could not be read leaves no recap, never an older charge's`() {
        recaps.offer(NewestRecap.STARTUP, recap(sessionId = 100))
        recaps.offer(recaps.closing(), null)
        assertNull(recaps.current.recap)
    }

    @Test fun `a newer read takes over, and the read it replaced is newest no longer`() {
        val startup = recaps.offer(NewestRecap.STARTUP, recap(sessionId = 100))
        assertTrue(recaps.isNewest(startup))
        val closed = recaps.offer(recaps.closing(), recap(sessionId = 200))
        assertFalse(recaps.isNewest(startup))
        assertTrue(recaps.isNewest(closed))
    }

    @Test fun `nothing is newest before the first read, and the start-up read with nothing to read is`() {
        assertNull(recaps.current.recap)
        val none = recaps.offer(NewestRecap.STARTUP, null)
        assertTrue(recaps.isNewest(none))
    }

    @Test fun `the start-up read leaves out every log this service opened, the open one among them`() {
        val before = log(startMs = 100)
        recaps.opening(sessionStartMs = 200)
        log(startMs = 200)
        recaps.opening(sessionStartMs = 300)
        log(startMs = 300)
        assertEquals(before, recaps.previousLog(tmp.root))
    }

    @Test fun `with no log but the ones this service opened, the start-up read has nothing to read`() {
        recaps.opening(sessionStartMs = 200)
        log(startMs = 200)
        assertNull(recaps.previousLog(tmp.root))
    }

    @Test fun `a session opened while the start-up read lists the directory is left out too`() {
        val before = log(startMs = 100)
        lateinit var racing: NewestRecap
        racing = NewestRecap { dir ->
            racing.opening(sessionStartMs = 300)
            log(startMs = 300)
            RawLogWriter.sessionFiles(dir)
        }
        assertEquals(before, racing.previousLog(tmp.root))
    }
}
