// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import io.github.allenbw.chargelog.capture.log.Capabilities
import io.github.allenbw.chargelog.capture.log.CounterKinds
import io.github.allenbw.chargelog.capture.log.DeviceKinds
import io.github.allenbw.chargelog.capture.log.EventKinds
import io.github.allenbw.chargelog.capture.log.NdjsonCodec
import io.github.allenbw.chargelog.capture.log.RawLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReplayTest {

    @get:Rule val tmp = TemporaryFolder()

    private val header = RawLine.Header(
        schema = 1, samplerProfileId = "p1", deviceModel = "test", osRelease = "16",
        appVersion = "0.1.0", tickMs = 1000, sessionStartWallClockMs = 42,
    )

    private fun write(name: String, lines: List<RawLine>, trailingGarbage: String? = null): File {
        val f = File(tmp.root, name)
        f.writeText(lines.joinToString("") { NdjsonCodec.encode(it) + "\n" } + (trailingGarbage ?: ""))
        return f
    }

    @Test
    fun `parse builds session from header, samples, and end event`() {
        val f = write("session-42.ndjson", listOf(
            header,
            RawLine.Event(t = 42, e = 0, kind = EventKinds.SESSION_START),
            RawLine.Sample(t = 1_042, e = 1_000, level = 20, chargeCounterRaw = 1_000_000, currentRaw = -1),
            RawLine.Sample(t = 9_042, e = 9_000, level = 21, chargeCounterRaw = 1_050_000, currentRaw = -2),
            RawLine.Event(t = 9_500, e = 9_400, kind = EventKinds.SESSION_END),
        ))
        val p = Replay.parse(f)!!
        assertEquals(42L, p.session.id)
        assertEquals("session-42.ndjson", p.session.sourceFile)
        assertEquals(20, p.session.startLevel)
        assertEquals(21, p.session.endLevel)
        assertEquals(1_000_000L, p.session.startChargeCounterRaw)
        assertEquals(1_050_000L, p.session.endChargeCounterRaw)
        assertEquals(9_500L, p.session.endedAtMs)
        assertEquals(EndReasonsForReplay.CLEAN, p.session.endReason)
        assertEquals(2, p.samples.size)
        assertEquals(42L, p.samples[0].sessionId)
        assertEquals(1_000L, p.samples[0].elapsedRealtimeMs)
    }

    @Test
    fun `truncated session without end event gets TRUNCATED end reason and endedAtMs from its last sample`() {
        val f = write("session-42.ndjson", listOf(
            header,
            RawLine.Sample(t = 1_042, e = 1_000, level = 20),
        ))
        val p = Replay.parse(f)!!
        assertEquals(1_042L, p.session.endedAtMs)
        assertEquals(EndReasonsForReplay.TRUNCATED, p.session.endReason)
    }

    @Test
    fun `truncated file with multiple samples gets endedAtMs from the last sample, not the first`() {
        val f = write("session-42.ndjson", listOf(
            header,
            RawLine.Sample(t = 1_042, e = 1_000, level = 20),
            RawLine.Sample(t = 5_042, e = 5_000, level = 22),
            RawLine.Sample(t = 9_042, e = 9_000, level = 25),
        ))
        val p = Replay.parse(f)!!
        assertEquals(9_042L, p.session.endedAtMs)
        assertEquals(EndReasonsForReplay.TRUNCATED, p.session.endReason)
    }

    @Test
    fun `truncated file with no samples still has null endedAtMs`() {
        val f = write("session-42.ndjson", listOf(header))
        val p = Replay.parse(f)!!
        assertNull(p.session.endedAtMs)
        assertEquals(EndReasonsForReplay.TRUNCATED, p.session.endReason)
    }

    @Test
    fun `malformed trailing line is skipped not fatal`() {
        val f = write("session-42.ndjson", listOf(
            header,
            RawLine.Sample(t = 1_042, e = 1_000, level = 20),
        ), trailingGarbage = """{"y":"s","t":99""")
        assertEquals(1, Replay.parse(f)!!.samples.size)
    }

    @Test
    fun `file without header parses to null`() {
        val f = write("session-9.ndjson", listOf(RawLine.Sample(t = 1, e = 1)))
        assertNull(Replay.parse(f))
    }

    private fun header(sessionStartWallClockMs: Long) = RawLine.Header(
        schema = 1, samplerProfileId = "p1", deviceModel = "test", osRelease = "16",
        appVersion = "0.1.0", tickMs = 1000, sessionStartWallClockMs = sessionStartWallClockMs,
    )

    private fun sample(t: Long, e: Long, level: Int? = null) = RawLine.Sample(t = t, e = e, level = level)

    private fun event(t: Long, e: Long, kind: String) = RawLine.Event(t = t, e = e, kind = kind)

    private fun writeSession(vararg lines: RawLine): File {
        val h = lines.first() as RawLine.Header
        return write("session-${h.sessionStartWallClockMs}.ndjson", lines.toList())
    }

    @Test
    fun `service_stop terminal classifies as SERVICE_KILLED with endedAtMs`() {
        val f = writeSession(
            header(sessionStartWallClockMs = 1000L),
            sample(t = 2000L, e = 20L, level = 50),
            event(t = 3000L, e = 30L, kind = EventKinds.SERVICE_STOP),
        )
        val p = Replay.parse(f)!!
        assertEquals(EndReasonsForReplay.SERVICE_KILLED, p.session.endReason)
        assertEquals(3000L, p.session.endedAtMs)
    }

    @Test
    fun `session_end still wins as CLEAN when both terminals appear`() {
        val f = writeSession(
            header(sessionStartWallClockMs = 1000L),
            event(t = 2500L, e = 25L, kind = EventKinds.SESSION_END),
            event(t = 3000L, e = 30L, kind = EventKinds.SERVICE_STOP),
        )
        val p = Replay.parse(f)!!
        assertEquals(EndReasonsForReplay.CLEAN, p.session.endReason)
        assertEquals(2500L, p.session.endedAtMs)
    }

    @Test
    fun `no terminal event stays TRUNCATED with endedAtMs from the last sample`() {
        val f = writeSession(header(sessionStartWallClockMs = 1000L), sample(t = 2000L, e = 20L, level = 50))
        val p = Replay.parse(f)!!
        assertEquals(EndReasonsForReplay.TRUNCATED, p.session.endReason)
        assertEquals(2000L, p.session.endedAtMs)
    }

    @Test
    fun `parse carries device identity and capabilities from a schema-2 header`() {
        val h2 = header.copy(
            schema = 2, deviceKind = DeviceKinds.WATCH, deviceId = "cd".repeat(16), gaugeProfileId = "gauge-qbg",
            capabilities = Capabilities(reportsCurrent = false, counterKind = CounterKinds.SOC_DERIVED, hasHinge = false),
        )
        val f = write("session-42.ndjson", listOf(h2, RawLine.Sample(t = 1_042, e = 1_000, level = 20)))
        val s = Replay.parse(f)!!.session
        assertEquals(DeviceKinds.WATCH, s.deviceKind)
        assertEquals("cd".repeat(16), s.deviceId)
        assertEquals("test", s.deviceModel)
        assertEquals("gauge-qbg", s.gaugeProfileId)
        assertEquals(false, s.reportsCurrent)
        assertEquals(CounterKinds.SOC_DERIVED, s.counterKind)
        assertEquals(false, s.hasHinge)
    }

    @Test
    fun `parse defaults a legacy header to PHONE with null identity`() {
        val f = write("session-42.ndjson", listOf(header, RawLine.Sample(t = 1_042, e = 1_000, level = 20)))
        val s = Replay.parse(f)!!.session
        assertEquals(DeviceKinds.PHONE, s.deviceKind)
        assertEquals(null, s.deviceId)
        assertEquals("test", s.deviceModel)
        assertEquals(null, s.reportsCurrent)
        assertEquals(null, s.gaugeProfileId)
        assertEquals(null, s.counterKind)
        assertEquals(null, s.hasHinge)
    }
}
