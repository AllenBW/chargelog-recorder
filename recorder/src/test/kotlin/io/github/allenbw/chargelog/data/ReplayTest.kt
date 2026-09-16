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
    fun `a gauge_scale event outranks the header's provisional profile`() {
        // A header goes out at plug-in, before the session has a single sample, so a scale the
        // samples revealed cannot be in it (audit 2026-09-14). Without this preference the one
        // session that LEARNED the scale is the one session that reads a thousand times low.
        val f = write("session-42.ndjson", listOf(
            header.copy(gaugeProfileId = "gauge-unknown"),
            RawLine.Event(t = 42, e = 0, kind = EventKinds.SESSION_START),
            RawLine.Sample(t = 1_042, e = 1_000, level = 20, currentRaw = 420),
            RawLine.Event(t = 1_042, e = 1_000, kind = EventKinds.GAUGE_SCALE, detail = "gaugeProfileId=gauge-unknown-ma"),
            RawLine.Event(t = 9_500, e = 9_400, kind = EventKinds.SESSION_END),
        ))
        assertEquals("gauge-unknown-ma", Replay.parse(f)!!.session.gaugeProfileId)
    }

    @Test
    fun `an unreadable gauge_scale event leaves the header standing`() {
        // A session with a gauge beats a session without one, so an id this build does not know —
        // a newer recorder's table, a corrupted line — falls back rather than to null.
        val f = write("session-42.ndjson", listOf(
            header.copy(gaugeProfileId = "gauge-qbg"),
            RawLine.Event(t = 1_042, e = 1_000, kind = EventKinds.GAUGE_SCALE, detail = "gaugeProfileId=gauge-from-the-future"),
            RawLine.Event(t = 9_500, e = 9_400, kind = EventKinds.SESSION_END),
        ))
        assertEquals("gauge-qbg", Replay.parse(f)!!.session.gaugeProfileId)
        val bare = write("session-43.ndjson", listOf(
            header.copy(sessionStartWallClockMs = 43, gaugeProfileId = "gauge-qbg"),
            RawLine.Event(t = 1_043, e = 1_000, kind = EventKinds.GAUGE_SCALE, detail = null),
        ))
        assertEquals("gauge-qbg", Replay.parse(bare)!!.session.gaugeProfileId)
    }

    @Test
    fun `a sample whose scale is not 100 lands in the projection as a percent`() {
        val f = write("session-42.ndjson", listOf(
            header,
            RawLine.Sample(t = 1_042, e = 1_000, level = 200, scale = 1000),
            RawLine.Sample(t = 9_042, e = 9_000, level = 875, scale = 1000),
            RawLine.Event(t = 9_500, e = 9_400, kind = EventKinds.SESSION_END),
        ))
        val p = Replay.parse(f)!!
        assertEquals(listOf(20, 88), p.samples.map { it.level })
        assertEquals(20, p.session.startLevel)
        assertEquals(88, p.session.endLevel)
    }

    @Test
    fun `a sample with no scale keeps its level as written`() {
        val f = write("session-42.ndjson", listOf(header, RawLine.Sample(t = 1_042, e = 1_000, level = 20)))
        assertEquals(20, Replay.parse(f)!!.samples.single().level)
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
            capabilities = Capabilities(reportsCurrent = false, counterKind = CounterKinds.SOC_DERIVED, hasHinge = false, chargingPositive = true),
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
        assertEquals(true, s.chargingPositive)
    }

    @Test
    fun `parse carries the recording device's own software and hardware class into the row`() {
        // The row is the only thing the donation envelope reads, and before Room version 3 it
        // held no OS release or app version at all — so a synced watch row was published under
        // the PHONE's Android version. The header has always carried both; now the row does too.
        val hw = header.copy(
            schema = 2, deviceKind = DeviceKinds.WATCH, osRelease = "17", appVersion = "0.2.0",
            socModel = "Tensor W1", totalMemBytes = 2_000_000_000L, designCapacityMah = 306,
        )
        val f = write("session-42.ndjson", listOf(hw, RawLine.Sample(t = 1_042, e = 1_000, level = 20)))
        val s = Replay.parse(f)!!.session
        assertEquals("17", s.osRelease)
        assertEquals("0.2.0", s.appVersion)
        assertEquals("Tensor W1", s.socModel)
        assertEquals(2_000_000_000L, s.totalMemBytes)
        assertEquals(306, s.designCapacityMah)
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
        assertEquals(null, s.chargingPositive)
        assertEquals("16", s.osRelease)
        assertEquals("0.1.0", s.appVersion)
        assertEquals(null, s.socModel)
        assertEquals(null, s.totalMemBytes)
        assertEquals(null, s.designCapacityMah)
    }

    @Test
    fun `parse carries chargingStatus onto the sample row`() {
        val h2 = header.copy(schema = 2, capabilities = Capabilities(reportsChargingStatus = true, chargingPositive = true))
        val f = write("session-42.ndjson", listOf(
            h2,
            RawLine.Sample(t = 1_042, e = 1_000, level = 20, chargingStatus = 5),
            RawLine.Sample(t = 2_042, e = 2_000, level = 21),
        ))
        val p = Replay.parse(f)!!
        assertEquals(listOf(5, null), p.samples.map { it.chargingStatus })
        assertEquals(true, p.session.chargingPositive)
    }
}
