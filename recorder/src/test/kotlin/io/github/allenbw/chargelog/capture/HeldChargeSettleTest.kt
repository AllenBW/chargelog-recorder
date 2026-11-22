// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.capture.log.EventKinds
import io.github.allenbw.chargelog.capture.log.NdjsonCodec
import io.github.allenbw.chargelog.capture.log.RawLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldChargeSettleTest {

    private val profile = SamplerProfile(
        id = "p1-tick1000-wl1", tickMs = TICK_MS,
        deviceModel = "test", osRelease = "16", appVersion = "0.1.0",
    )

    private class Rig(profile: SamplerProfile, private val target: Int) {
        private val machine = SessionStateMachine(profile)
        private var lastState: List<Any?>? = null
        var mode = SamplingMode.TICK
            private set
        val written = mutableListOf<RawLine>()

        init {
            plug(0)
        }

        fun plug(sec: Long) = take(machine.on(CaptureInput.PowerConnected(START_T + sec * TICK_MS, sec * TICK_MS, target)))

        fun unplug(sec: Long) = take(machine.on(CaptureInput.PowerDisconnected(START_T + sec * TICK_MS, sec * TICK_MS)))

        fun second(s: RawLine.Sample) {
            val state = listOf(s.level, s.status, s.plugged, s.chargingStatus, s.voltageRaw, s.tempDeciC)
            val broadcast = state != lastState
            lastState = state
            if (mode == SamplingMode.TICK || broadcast) take(machine.on(CaptureInput.Tick(s)))
        }

        private fun take(effects: List<CaptureEffect>) = effects.forEach { fx ->
            if (fx is CaptureEffect.Append) written += fx.line
            if (fx is CaptureEffect.SetSampling) mode = fx.mode
        }
    }

    private fun gauge(
        sec: Long,
        level: Int,
        ua: Long,
        temp: Int = 300,
        status: Int = BatteryStatus.CHARGING,
        plugged: Int = 1,
        cs: Int? = 1,
        mv: Int = 3_700 + level * 7,
    ) = RawLine.Sample(
        t = START_T + sec * TICK_MS, e = sec * TICK_MS, currentRaw = ua, voltageRaw = mv,
        tempDeciC = temp, level = level, scale = 100, status = status, plugged = plugged, chargingStatus = cs,
    )

    private fun nearZero(sec: Long) = (sec % 7 - 3) * 1_000L

    private fun policyAt(rig: Rig, detail: String): List<Long> =
        rig.written.filterIsInstance<RawLine.Event>()
            .filter { it.kind == EventKinds.CAPTURE_POLICY && it.detail == detail }
            .map { it.e }

    @Test fun `a charge held flat below its target stops ticking after thirty minutes and resumes on a change`() {
        val rig = Rig(profile, target = 100)
        val climbSteps = HOLD_LEVEL - CLIMB_FROM
        for (sec in 0 until climbSteps * STEP_S) rig.second(gauge(sec, CLIMB_FROM + (sec / STEP_S).toInt(), 2_500_000L + nearZero(sec)))
        val holdStart = climbSteps * STEP_S
        val holdEnd = holdStart + HOLD_HOURS * HOUR_S
        for (sec in holdStart until holdEnd) {
            rig.second(gauge(sec, HOLD_LEVEL, nearZero(sec), temp = 300 + ((sec / TEMP_STEP_S) % 3).toInt()))
        }
        for (sec in holdEnd until holdEnd + 60) rig.second(gauge(sec, HOLD_LEVEL - 1, nearZero(sec)))

        val perHour = (0 until HOLD_HOURS).map { h ->
            val from = (holdStart + h * HOUR_S) * TICK_MS
            rig.written.filter { it !is RawLine.Header && it.e >= from && it.e < from + HOUR_S * TICK_MS }
        }
        val bytesPerDay = perHour.last().sumOf { NdjsonCodec.encode(it).length + 1L } * 24
        assertTrue(
            "lines written in each hour of the hold: ${perHour.map { it.size }}, about ${bytesPerDay / 1_000_000} MB a day",
            perHour.drop(1).all { it.size < HELD_HOUR_MAX_LINES },
        )
        assertEquals(listOf((holdStart + FLAT_HOLD_S) * TICK_MS), policyAt(rig, "settled"))
        assertEquals(listOf(holdEnd * TICK_MS), policyAt(rig, "resumed"))
        assertEquals(SamplingMode.TICK, rig.mode)
    }

    @Test fun `a slow climb whose steps take under thirty minutes never settles below the target`() {
        val d = SettleDetector()
        val stepS = FLAT_HOLD_S - 1
        val t = (0 until 5 * stepS).mapNotNull { sec -> d.offer(gauge(sec, 60 + (sec / stepS).toInt(), nearZero(sec)), 100) }
        assertEquals(emptyList<SettleDetector.Transition>(), t)
    }

    @Test fun `the long hold settles at thirty minutes, not a second sooner`() {
        val d = SettleDetector()
        val early = (0 until FLAT_HOLD_S).mapNotNull { sec -> d.offer(gauge(sec, HOLD_LEVEL, nearZero(sec)), 100) }
        assertEquals(emptyList<SettleDetector.Transition>(), early)
        assertEquals(SettleDetector.Transition.SETTLED, d.offer(gauge(FLAT_HOLD_S, HOLD_LEVEL, nearZero(0)), 100))
    }

    @Test fun `a held charge stays settled while only the voltage, temperature or current moves`() {
        val d = held()
        val after = FLAT_HOLD_S + 1
        val quiet = (after until after + HOUR_S).mapNotNull { sec ->
            val temp = 280 + (sec % 9).toInt()
            d.offer(gauge(sec, HOLD_LEVEL, 400_000L + nearZero(sec), temp = temp, mv = 4_290 + (sec % 5).toInt()), 100)
        }
        assertEquals(emptyList<SettleDetector.Transition>(), quiet)
    }

    @Test fun `any change in level, status, plug or charging status resumes a held charge`() {
        val after = FLAT_HOLD_S + 1
        val changes = mapOf(
            "level down" to gauge(after, HOLD_LEVEL - 1, 0),
            "level up" to gauge(after, HOLD_LEVEL + 1, 0),
            "status" to gauge(after, HOLD_LEVEL, 0, status = BatteryStatus.NOT_CHARGING),
            "plug" to gauge(after, HOLD_LEVEL, 0, plugged = 2),
            "charging status" to gauge(after, HOLD_LEVEL, 0, cs = 5),
        )
        for ((what, changed) in changes) {
            assertEquals(what, SettleDetector.Transition.RESUMED, held().offer(changed, 100))
        }
    }

    @Test fun `a resumed hold needs thirty quiet minutes again before it settles`() {
        val d = held()
        val back = FLAT_HOLD_S + 1
        assertEquals(SettleDetector.Transition.RESUMED, d.offer(gauge(back, HOLD_LEVEL + 1, 0), 100))
        val again = (back + 1 until back + FLAT_HOLD_S).mapNotNull { sec -> d.offer(gauge(sec, HOLD_LEVEL + 1, 0), 100) }
        assertEquals(emptyList<SettleDetector.Transition>(), again)
        assertEquals(SettleDetector.Transition.SETTLED, d.offer(gauge(back + FLAT_HOLD_S, HOLD_LEVEL + 1, 0), 100))
    }

    @Test fun `a bounce off a long quiet settle needs thirty quiet minutes again before it settles`() {
        val d = SettleDetector()
        val dropAt = 300L
        val bounceAt = dropAt + 40 * 60L
        val run = (0..bounceAt + FLAT_HOLD_S).map { sec ->
            when {
                sec < 60 -> gauge(sec, 99, 2_500_000L)
                sec < dropAt -> gauge(sec, 100, nearZero(sec))
                sec < bounceAt -> gauge(sec, 99, nearZero(sec))
                else -> gauge(sec, 99, 2_000_000L)
            }
        }
        val transitions = run.mapNotNull { s -> d.offer(s, 100)?.let { s.e / TICK_MS to it } }
        assertEquals(
            listOf(
                180L to SettleDetector.Transition.SETTLED,
                bounceAt to SettleDetector.Transition.RESUMED,
                bounceAt + FLAT_HOLD_S to SettleDetector.Transition.SETTLED,
            ),
            transitions,
        )
    }

    @Test fun `a re-plug in the state a long hold was unplugged in waits thirty minutes of its own`() {
        val rig = Rig(profile, target = 100)
        val unplugAt = 40 * 60L
        for (sec in 0 until unplugAt) rig.second(gauge(sec, HOLD_LEVEL, nearZero(sec)))
        rig.unplug(unplugAt)
        val replugAt = unplugAt + 4
        rig.plug(replugAt)
        val firstTick = replugAt + 1
        for (sec in firstTick..firstTick + FLAT_HOLD_S) rig.second(gauge(sec, HOLD_LEVEL, nearZero(sec)))

        val settledAt = listOf(FLAT_HOLD_S, firstTick + FLAT_HOLD_S).map { it * TICK_MS }
        assertEquals(settledAt, policyAt(rig, "settled"))
        assertEquals(emptyList<Long>(), policyAt(rig, "resumed"))
    }

    private fun held(): SettleDetector {
        val d = SettleDetector()
        val t = (0..FLAT_HOLD_S).mapNotNull { sec -> d.offer(gauge(sec, HOLD_LEVEL, nearZero(sec)), 100) }
        assertEquals(listOf(SettleDetector.Transition.SETTLED), t)
        return d
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val START_T = 1_790_000_000_000L
        const val CLIMB_FROM = 60
        const val HOLD_LEVEL = 85
        const val STEP_S = 90L
        const val HOUR_S = 3_600L
        const val HOLD_HOURS = 6
        const val TEMP_STEP_S = 600L
        const val FLAT_HOLD_S = 30 * 60L
        const val HELD_HOUR_MAX_LINES = 60
    }
}
