// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.capture.log.EventKinds
import io.github.allenbw.chargelog.capture.log.NdjsonCodec
import io.github.allenbw.chargelog.capture.log.RawLine
import io.github.allenbw.chargelog.capture.log.RawLogWriter
import io.github.allenbw.chargelog.measure.GaugeProfile
import io.github.allenbw.chargelog.measure.GaugeProfiles
import io.github.allenbw.chargelog.measure.GaugeScaleProbe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException

class EffectRunnerTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakePorts : EffectPorts {
        var eventsFail = false
        var wakeLockError: Error? = null
        var held = false
        var beats = 0
        val events = mutableListOf<RawLine.Event>()
        val modes = mutableListOf<SamplingMode>()
        val closed = mutableListOf<File>()
        val learned = mutableListOf<GaugeProfile>()

        override fun appendEvent(event: RawLine.Event) {
            if (eventsFail) throw IOException("No space left on device")
            events += event
        }

        override fun acquireWakeLock() {
            wakeLockError?.let { throw it }
            held = true
        }

        override fun releaseWakeLock() {
            held = false
        }

        override fun onSampling(mode: SamplingMode) {
            modes += mode
        }

        override fun onClosed(file: File) {
            closed += file
        }

        override fun fallbackGauge(): GaugeProfile = GaugeProfiles.PHONE

        override fun onGaugeLearned(profile: GaugeProfile) {
            learned += profile
        }

        override fun beat() {
            beats++
        }
    }

    private val profile = SamplerProfile(
        id = "p1-tick1000-wl1", tickMs = 1000, deviceModel = "test", osRelease = "16", appVersion = "0.1.0",
    )
    private val ports = FakePorts()
    private val failures = mutableListOf<Pair<CaptureEffect?, Exception>>()

    private fun runner(log: RawLogWriter, machine: SessionStateMachine = SessionStateMachine(profile)) =
        EffectRunner(machine, log, ports) { fx, e -> failures += fx to e }

    private fun refusingOpen(refuse: () -> Boolean) = RawLogWriter(tmp.root) { f ->
        if (refuse()) throw FileNotFoundException("${f.name} (Read-only file system)") else FileOutputStream(f, true)
    }

    private fun charging(e: Long, ua: Long = 2_000_000L) =
        RawLine.Sample(t = 1_000 + e, e = e, currentRaw = ua, voltageRaw = 4_000, level = 50, status = BatteryStatus.CHARGING)

    private fun lines(f: File): List<RawLine> = f.readLines().filter { it.isNotBlank() }.map(NdjsonCodec::decode)

    private fun kinds(f: File): List<String> = lines(f).filterIsInstance<RawLine.Event>().map { it.kind }

    @Before fun resetFeed() = LiveFeed.reset()

    @After fun clearFeed() = LiveFeed.reset()

    private fun unplugWithFullEventLog(log: RawLogWriter, r: EffectRunner): File {
        assertTrue(r.handle(CaptureInput.PowerConnected(t = 1_000, e = 0)))
        assertTrue(r.handle(CaptureInput.Tick(charging(e = 1_000))))
        val first = log.currentFile!!
        ports.eventsFail = true
        assertFalse(r.handle(CaptureInput.PowerDisconnected(t = 5_000, e = 4_000)))
        return first
    }

    @Test fun `an event log that fails at unplug still ends the session, closes the log and releases the wakelock`() {
        val log = RawLogWriter(tmp.root)
        val first = unplugWithFullEventLog(log, runner(log))

        assertEquals(EventKinds.SESSION_END, kinds(first).last())
        assertFalse("the writer was left open", log.isOpen)
        assertFalse("the partial wakelock was left held", ports.held)
        assertEquals(listOf(first), ports.closed)
        assertEquals(false, LiveFeed.state.value?.recording)
        assertTrue(failures.single().first is CaptureEffect.LogEvent)
    }

    @Test fun `after an unplug whose event write failed, the next plug-in opens a new log and records into it`() {
        val log = RawLogWriter(tmp.root)
        val r = runner(log)
        val first = unplugWithFullEventLog(log, r)
        val firstLines = lines(first)

        val opened = r.handle(CaptureInput.PowerConnected(t = 9_000, e = 8_000))
        assertEquals("the next plug-in did not open its own log", "session-9000.ndjson", log.currentFile?.name)
        assertTrue(r.handle(CaptureInput.Tick(charging(e = 9_000, ua = 2_100_000L))))

        val next = File(tmp.root, "session-9000.ndjson")
        assertTrue(lines(next).first() is RawLine.Header)
        assertEquals("the next plug-in's sample is not in its own log",
            2_100_000L, lines(next).filterIsInstance<RawLine.Sample>().single().currentRaw)
        assertEquals("the next plug-in wrote into the unplugged session's log", firstLines, lines(first))
        assertTrue(opened)
        assertTrue(ports.held)
        assertEquals(1, failures.size)
    }

    @Test fun `appends after an open whose stream could not be created stay inside the runner`() {
        var refuse = true
        val log = refusingOpen { refuse }
        val r = runner(log)

        assertFalse(r.handle(CaptureInput.PowerConnected(t = 1_000, e = 0)))
        assertTrue("the effects after the failed open were skipped", ports.held)
        assertEquals(listOf(SamplingMode.TICK), ports.modes)
        assertFalse(r.handle(CaptureInput.Tick(charging(e = 1_000))))
        assertFalse(r.handle(CaptureInput.Tick(charging(e = 2_000, ua = 2_100_000L))))
        assertTrue(failures.all { it.second is IOException || it.second is IllegalStateException })
        assertEquals(null, LiveFeed.state.value)

        assertFalse(r.handle(CaptureInput.PowerDisconnected(t = 5_000, e = 4_000)))
        assertFalse(ports.held)
        refuse = false
        assertTrue(r.handle(CaptureInput.PowerConnected(t = 9_000, e = 8_000)))
        assertTrue(log.isOpen)
        assertTrue(lines(log.currentFile!!).first() is RawLine.Header)
    }

    @Test fun `a log left open is closed before the next session opens, never written into`() {
        val log = RawLogWriter(tmp.root)
        val stale = log.open(RawLine.Header(
            schema = 2, samplerProfileId = "p1", deviceModel = "test", osRelease = "16",
            appVersion = "0.1.0", tickMs = 1000, sessionStartWallClockMs = 500,
        ))
        val r = runner(log)

        val opened = r.handle(CaptureInput.PowerConnected(t = 1_000, e = 0))
        assertEquals("the next plug-in did not open its own log", "session-1000.ndjson", log.currentFile?.name)
        assertTrue(r.handle(CaptureInput.Tick(charging(e = 1_000))))

        assertEquals("the next plug-in wrote into the log left open", 1, lines(stale).size)
        assertEquals(1, lines(File(tmp.root, "session-1000.ndjson")).filterIsInstance<RawLine.Sample>().size)
        assertEquals(listOf(stale), ports.closed)
        assertTrue(opened)
        assertTrue(failures.isEmpty())
    }

    @Test fun `a learned gauge is persisted, logged into the session that learned it, and heads the next one`() {
        val log = RawLogWriter(tmp.root)
        val r = runner(log, SessionStateMachine(profile.copy(gaugeProfileId = GaugeProfiles.UNKNOWN.id)))
        r.handle(CaptureInput.PowerConnected(t = 1_000, e = 0))
        val learning = log.currentFile!!
        repeat(GaugeScaleProbe.SAMPLES) { i ->
            r.handle(CaptureInput.Tick(RawLine.Sample(t = 2_000L + i, e = 1_000L * (i + 1), currentRaw = 400L + i)))
        }

        assertEquals(listOf(GaugeProfiles.UNKNOWN_MA), ports.learned)
        val marker = lines(learning).filterIsInstance<RawLine.Event>().single { it.kind == EventKinds.GAUGE_SCALE }
        assertEquals(EventKinds.GAUGE_SCALE_DETAIL_KEY + GaugeProfiles.UNKNOWN_MA.id, marker.detail)

        r.handle(CaptureInput.PowerDisconnected(t = 30_000, e = 29_000))
        r.handle(CaptureInput.PowerConnected(t = 40_000, e = 39_000))
        assertEquals(GaugeProfiles.UNKNOWN_MA.id, (lines(log.currentFile!!).first() as RawLine.Header).gaugeProfileId)
        assertTrue(failures.isEmpty())
    }

    @Test fun `the live feed reads its watts at the scale of the gauge that heads the session`() {
        val r = runner(RawLogWriter(tmp.root), SessionStateMachine(profile.copy(gaugeProfileId = GaugeProfiles.PHONE_MA.id)))
        r.handle(CaptureInput.PowerConnected(t = 1_000, e = 0))
        r.handle(CaptureInput.Tick(charging(e = 1_000, ua = 2_500L).copy(voltageRaw = 4_100)))

        assertEquals(10.25, LiveFeed.state.value!!.recentWatts.single(), 1e-9)
    }

    @Test fun `a gauge learned mid-charge rescales the live feed's watts, the earlier ones too`() {
        val r = runner(RawLogWriter(tmp.root), SessionStateMachine(profile.copy(gaugeProfileId = GaugeProfiles.UNKNOWN.id)))
        r.handle(CaptureInput.PowerConnected(t = 1_000, e = 0))
        repeat(GaugeScaleProbe.SAMPLES) { i ->
            r.handle(CaptureInput.Tick(RawLine.Sample(t = 2_000L + i, e = 1_000L * (i + 1), currentRaw = 400L + i, voltageRaw = 4_000)))
        }

        assertEquals(listOf(GaugeProfiles.UNKNOWN_MA), ports.learned)
        val expected = List(GaugeScaleProbe.SAMPLES) { i -> (400 + i) * 4.0 / 1_000 }
        assertEquals(expected, LiveFeed.state.value!!.recentWatts.map { Math.round(it * 1e6) / 1e6 })
    }

    @Test fun `a tick beats the quiet watch only when every effect in its batch ran clean`() {
        var refuse = true
        val r = runner(refusingOpen { refuse })
        r.handle(CaptureInput.PowerConnected(t = 1_000, e = 0))
        assertFalse(r.handle(CaptureInput.Tick(charging(e = 1_000))))
        assertEquals("a tick whose sample was not written beat", 0, ports.beats)

        r.handle(CaptureInput.PowerDisconnected(t = 5_000, e = 4_000))
        refuse = false
        assertTrue(r.handle(CaptureInput.PowerConnected(t = 9_000, e = 8_000)))
        assertEquals("an input that is not a tick beat", 0, ports.beats)
        assertTrue(r.handle(CaptureInput.Tick(charging(e = 9_000))))
        assertEquals(1, ports.beats)
    }

    @Test fun `a sample whose write fails is neither shown nor offered to the gauge probe`() {
        var full = false
        val log = RawLogWriter(tmp.root) { f ->
            object : FilterOutputStream(FileOutputStream(f, true)) {
                override fun write(b: Int) {
                    if (full) throw IOException("No space left on device")
                    super.write(b)
                }
            }
        }
        val r = runner(log, SessionStateMachine(profile.copy(gaugeProfileId = GaugeProfiles.UNKNOWN.id)))
        assertTrue(r.handle(CaptureInput.PowerConnected(t = 1_000, e = 0)))
        full = true
        repeat(GaugeScaleProbe.SAMPLES) { i ->
            r.handle(CaptureInput.Tick(RawLine.Sample(t = 2_000L + i, e = 1_000L * (i + 1), currentRaw = 400L + i)))
        }

        assertTrue(failures.isNotEmpty() && failures.all { it.first is CaptureEffect.Append })
        assertEquals("the probe learned from samples the log never held", emptyList<GaugeProfile>(), ports.learned)
        assertEquals(true, LiveFeed.state.value?.recording)
        assertEquals("LiveFeed showed a sample the log never held", null, LiveFeed.state.value?.sample)
    }

    @Test fun `an Error is not contained, so it leaves handle and is not reported as an effect failure`() {
        ports.wakeLockError = OutOfMemoryError("test")
        val r = runner(RawLogWriter(tmp.root))

        assertThrows(OutOfMemoryError::class.java) { r.handle(CaptureInput.PowerConnected(t = 1_000, e = 0)) }
        assertTrue(failures.isEmpty())
    }
}
