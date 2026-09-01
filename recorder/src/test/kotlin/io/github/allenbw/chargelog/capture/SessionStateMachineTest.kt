// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.capture.log.Capabilities
import io.github.allenbw.chargelog.capture.log.CounterKinds
import io.github.allenbw.chargelog.capture.log.DeviceKinds
import io.github.allenbw.chargelog.capture.log.EventKinds
import io.github.allenbw.chargelog.capture.log.RawLine
import io.github.allenbw.chargelog.measure.CurrentScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateMachineTest {

    private val profile = SamplerProfile(
        id = "p1-tick1000-wl1", tickMs = 1000,
        deviceModel = "test", osRelease = "16", appVersion = "0.1.0",
    )

    private fun machine() = SessionStateMachine(profile)
    private fun sample(e: Long, current: Long? = 500) =
        RawLine.Sample(t = e + 7_000, e = e, currentRaw = current)

    private fun charging(e: Long, level: Int, ua: Long = 2_000_000) =
        RawLine.Sample(t = e + 7_000, e = e, currentRaw = ua, voltageRaw = 4000, level = level, status = BatteryStatus.CHARGING)
    private fun full(e: Long) =
        RawLine.Sample(t = e + 7_000, e = e, currentRaw = 50_000, voltageRaw = 4000, level = 100, status = BatteryStatus.FULL)

    @Test
    fun `plug opens log with header, start event, wakelock`() {
        val fx = machine().on(CaptureInput.PowerConnected(t = 7_000, e = 100))
        val open = fx.filterIsInstance<CaptureEffect.OpenLog>().single()
        assertEquals(7_000, open.header.sessionStartWallClockMs)
        assertEquals(profile.id, open.header.samplerProfileId)
        assertEquals(1000, open.header.tickMs)
        val events = fx.filterIsInstance<CaptureEffect.Append>()
            .map { it.line }.filterIsInstance<RawLine.Event>()
        assertEquals(listOf(EventKinds.SESSION_START, EventKinds.CADENCE), events.map { it.kind })
        assertEquals("tickMs=1000,policy=tick", events[1].detail)
        assertTrue(fx.contains(CaptureEffect.AcquireWakeLock))
    }

    @Test
    fun `a watch profile settles on mA watts`() {
        val watch = SessionStateMachine(profile.copy(tickMs = 1000, currentScale = CurrentScale.MILLI_AMP))
        watch.on(CaptureInput.PowerConnected(t = 1, e = 0, targetLevel = 100))
        // 350 mA at 4 V ≈ 1.4 W peak; then FULL at 4 mA (~1 % of peak) for the 120 s hold.
        watch.on(CaptureInput.Tick(RawLine.Sample(t = 1, e = 1_000, currentRaw = 350, voltageRaw = 4000, level = 99, status = BatteryStatus.CHARGING)))
        var settled = false
        for (i in 0..130) {
            val fx = watch.on(CaptureInput.Tick(RawLine.Sample(t = 1, e = 2_000L + i * 1_000, currentRaw = 4, voltageRaw = 4000 + (i % 2), level = 100, status = BatteryStatus.FULL)))
            if (fx.any { it is CaptureEffect.SetSampling && it.mode == SamplingMode.EVENT }) settled = true
        }
        assertTrue(settled)
    }

    @Test
    fun `duplicate plug while recording is a no-op`() {
        val m = machine()
        m.on(CaptureInput.PowerConnected(t = 1, e = 1))
        assertTrue(m.on(CaptureInput.PowerConnected(t = 2, e = 2)).isEmpty())
    }

    @Test
    fun `ticks while recording append gated samples`() {
        val m = machine()
        m.on(CaptureInput.PowerConnected(t = 1, e = 0))
        val fx1 = m.on(CaptureInput.Tick(sample(e = 1000)))
        assertTrue(fx1.filterIsInstance<CaptureEffect.Append>().single().line is RawLine.Sample)
        // identical gauge tuple → skipped
        assertTrue(m.on(CaptureInput.Tick(sample(e = 2000))).isEmpty())
        // changed tuple → appended
        val fx3 = m.on(CaptureInput.Tick(sample(e = 3000, current = 900)))
        assertEquals(1, fx3.filterIsInstance<CaptureEffect.Append>().size)
    }

    @Test
    fun `tick while idle produces nothing`() {
        assertTrue(machine().on(CaptureInput.Tick(sample(e = 1000))).isEmpty())
    }

    @Test
    fun `unplug closes with UNPLUGGED, end event, releases wakelock`() {
        val m = machine()
        m.on(CaptureInput.PowerConnected(t = 1, e = 0))
        val fx = m.on(CaptureInput.PowerDisconnected(t = 60_000, e = 59_000))
        val ends = fx.filterIsInstance<CaptureEffect.Append>()
            .map { it.line }.filterIsInstance<RawLine.Event>()
        assertEquals(EventKinds.SESSION_END, ends.single().kind)
        assertEquals(EndReasons.UNPLUGGED, fx.filterIsInstance<CaptureEffect.CloseLog>().single().endReason)
        assertTrue(fx.contains(CaptureEffect.ReleaseWakeLock))
        assertFalse(m.recording)
    }

    @Test
    fun `unplug while recording ALSO logs the ground-truth disconnect marker`() {
        // The connect side appends its marker unconditionally so consumers can join against it.
        // The disconnect side only did so when NOT recording — i.e. almost never — so a plug-in
        // span had a start marker and no end marker.
        val m = machine()
        m.on(CaptureInput.PowerConnected(t = 1, e = 0))
        val fx = m.on(CaptureInput.PowerDisconnected(t = 60_000, e = 59_000))
        assertEquals(
            EventKinds.POWER_DISCONNECTED,
            fx.filterIsInstance<CaptureEffect.LogEvent>().single().event.kind,
        )
        // and it still closes the session exactly as before
        assertEquals(EndReasons.UNPLUGGED, fx.filterIsInstance<CaptureEffect.CloseLog>().single().endReason)
    }

    @Test
    fun `unplug while idle logs event only`() {
        val fx = machine().on(CaptureInput.PowerDisconnected(t = 1, e = 1))
        assertEquals(EventKinds.POWER_DISCONNECTED,
            fx.filterIsInstance<CaptureEffect.LogEvent>().single().event.kind)
        assertTrue(fx.filterIsInstance<CaptureEffect.CloseLog>().isEmpty())
    }

    @Test
    fun `service stop mid-session closes with SERVICE_KILLED`() {
        val m = machine()
        m.on(CaptureInput.PowerConnected(t = 1, e = 0))
        val fx = m.on(CaptureInput.ServiceStopping(t = 9, e = 8))
        assertEquals(EndReasons.SERVICE_KILLED, fx.filterIsInstance<CaptureEffect.CloseLog>().single().endReason)
        assertTrue(fx.contains(CaptureEffect.ReleaseWakeLock))
    }

    @Test
    fun `observation while recording appends to the session log`() {
        val m = machine()
        m.on(CaptureInput.PowerConnected(t = 1, e = 0))
        val ev = RawLine.Event(t = 5, e = 4, kind = EventKinds.HINGE, detail = "deg=90.0")
        val fx = m.on(CaptureInput.Observed(ev))
        assertEquals(ev, fx.filterIsInstance<CaptureEffect.Append>().single().line)
        assertTrue(fx.filterIsInstance<CaptureEffect.LogEvent>().isEmpty())
    }

    @Test
    fun `observation while idle goes to the rolling event log`() {
        val ev = RawLine.Event(t = 5, e = 4, kind = EventKinds.THERMAL, detail = "status=2")
        val fx = machine().on(CaptureInput.Observed(ev))
        assertEquals(ev, fx.filterIsInstance<CaptureEffect.LogEvent>().single().event)
        assertTrue(fx.filterIsInstance<CaptureEffect.Append>().isEmpty())
    }

    @Test
    fun `gate resets between sessions`() {
        val m = machine()
        m.on(CaptureInput.PowerConnected(t = 1, e = 0))
        m.on(CaptureInput.Tick(sample(e = 1000)))
        m.on(CaptureInput.PowerDisconnected(t = 2, e = 2000))
        m.on(CaptureInput.PowerConnected(t = 3, e = 3000))
        // same gauge tuple as previous session's sample must persist (fresh gate)
        val fx = m.on(CaptureInput.Tick(sample(e = 4000)))
        assertEquals(1, fx.filterIsInstance<CaptureEffect.Append>().size)
    }

    @Test
    fun `header carries schema 2 and the profile's device identity and capabilities`() {
        val caps = Capabilities(reportsCurrent = true, reportsChargeCounter = true, counterKind = CounterKinds.COULOMB, hasHinge = true, hasThermal = true)
        val p = profile.copy(deviceKind = DeviceKinds.PHONE, deviceId = "ab".repeat(16), gaugeProfileId = null, capabilities = caps)
        val fx = SessionStateMachine(p).on(CaptureInput.PowerConnected(t = 7_000, e = 100))
        val h = fx.filterIsInstance<CaptureEffect.OpenLog>().single().header
        assertEquals(2, h.schema)
        assertEquals(DeviceKinds.PHONE, h.deviceKind)
        assertEquals("ab".repeat(16), h.deviceId)
        assertEquals(caps, h.capabilities)
    }

    @Test
    fun `settle releases the wake lock, logs policy and cadence, and switches to event sampling`() {
        val m = machine()
        m.on(CaptureInput.PowerConnected(t = 1, e = 0))
        for (i in 1..30) m.on(CaptureInput.Tick(charging(i * 1000L, 90 + i / 10)))
        var settleFx: List<CaptureEffect> = emptyList()
        for (i in 31..200) { val fx = m.on(CaptureInput.Tick(full(i * 1000L))); if (fx.contains(CaptureEffect.ReleaseWakeLock)) settleFx = fx }
        val events = settleFx.filterIsInstance<CaptureEffect.Append>().map { it.line }.filterIsInstance<RawLine.Event>()
        assertEquals(listOf(EventKinds.CAPTURE_POLICY, EventKinds.CADENCE), events.map { it.kind })
        assertEquals("settled", events[0].detail)
        assertEquals("tickMs=${SessionStateMachine.EVENT_GAP_TICK_MS},policy=event", events[1].detail)
        assertTrue(settleFx.contains(CaptureEffect.SetSampling(SamplingMode.EVENT)))
        assertTrue(m.recording)
    }

    @Test
    fun `while settled a 10 s silence is not a gap, and resume restores the tick gate`() {
        val m = machine()
        m.on(CaptureInput.PowerConnected(t = 1, e = 0))
        for (i in 1..30) m.on(CaptureInput.Tick(charging(i * 1000L, 99)))
        for (i in 31..200) m.on(CaptureInput.Tick(full(i * 1000L)))
        val quiet = m.on(CaptureInput.Tick(full(210_000L)))          // 10 s after the last sample
        assertTrue(quiet.filterIsInstance<CaptureEffect.Append>().map { it.line }.filterIsInstance<RawLine.Event>().none { it.kind == EventKinds.GAP })
        val resume = m.on(CaptureInput.Tick(charging(211_000L, 99, ua = 700_000)))
        assertTrue(resume.contains(CaptureEffect.AcquireWakeLock))
        assertTrue(resume.contains(CaptureEffect.SetSampling(SamplingMode.TICK)))
        val late = m.on(CaptureInput.Tick(charging(221_000L, 99, ua = 700_000)))   // 10 s > 3 × 1 s → gap again
        assertTrue(late.filterIsInstance<CaptureEffect.Append>().map { it.line }.filterIsInstance<RawLine.Event>().any { it.kind == EventKinds.GAP })
    }

    @Test
    fun `a second session on the same machine starts unsettled, in tick mode, with a fresh gate`() {
        val m = machine()
        m.on(CaptureInput.PowerConnected(t = 1, e = 0))
        for (i in 1..30) m.on(CaptureInput.Tick(charging(i * 1000L, 99)))
        for (i in 31..200) m.on(CaptureInput.Tick(full(i * 1000L)))
        m.on(CaptureInput.PowerDisconnected(t = 300_000, e = 299_000))
        // Opening declares TICK, so a stale SetSampling(EVENT) from the session that just closed
        // can never leave the next one tickerless: the open's own effect comes after it in queue
        // order and wins.
        val open = m.on(CaptureInput.PowerConnected(t = 301_000, e = 300_000))
        assertTrue(open.contains(CaptureEffect.SetSampling(SamplingMode.TICK)))
        assertTrue(m.recording)
        // And the gate is back on the profile's own cadence: 10 s of silence is a gap again, not
        // the settled policy's 100 s.
        m.on(CaptureInput.Tick(charging(310_000L, 50)))
        val late = m.on(CaptureInput.Tick(charging(320_000L, 50)))
        assertTrue(late.filterIsInstance<CaptureEffect.Append>().map { it.line }.filterIsInstance<RawLine.Event>().any { it.kind == EventKinds.GAP })
    }

    @Test
    fun `unplug while settled closes cleanly`() {
        val m = machine()
        m.on(CaptureInput.PowerConnected(t = 1, e = 0))
        for (i in 1..30) m.on(CaptureInput.Tick(charging(i * 1000L, 99)))
        for (i in 31..200) m.on(CaptureInput.Tick(full(i * 1000L)))
        val fx = m.on(CaptureInput.PowerDisconnected(t = 300_000, e = 299_000))
        assertEquals(EndReasons.UNPLUGGED, fx.filterIsInstance<CaptureEffect.CloseLog>().single().endReason)
        assertFalse(m.recording)
    }
}
