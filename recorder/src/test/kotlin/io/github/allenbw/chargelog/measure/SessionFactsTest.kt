// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.measure

import io.github.allenbw.chargelog.capture.log.DeviceKinds
import io.github.allenbw.chargelog.data.SampleEntity
import io.github.allenbw.chargelog.data.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionFactsTest {

    private fun sample(
        sessionId: Long = 1L,
        wallClockMs: Long = 0L,
        elapsedRealtimeMs: Long,
        currentRaw: Long? = null,
        chargeCounterRaw: Long? = null,
        voltageRaw: Int? = null,
        voltageAgeMs: Long? = null,
        tempDeciC: Int? = null,
        level: Int? = null,
        status: Int? = null,
        plugged: Int? = null,
        maxChargingCurrentRaw: Int? = null,
        maxChargingVoltageRaw: Int? = null,
        thermalStatus: Int? = null,
        screenOn: Boolean? = null,
        hingeDeg: Float? = null,
    ) = SampleEntity(
        sessionId = sessionId,
        wallClockMs = wallClockMs,
        elapsedRealtimeMs = elapsedRealtimeMs,
        currentRaw = currentRaw,
        chargeCounterRaw = chargeCounterRaw,
        voltageRaw = voltageRaw,
        voltageAgeMs = voltageAgeMs,
        tempDeciC = tempDeciC,
        level = level,
        status = status,
        plugged = plugged,
        maxChargingCurrentRaw = maxChargingCurrentRaw,
        maxChargingVoltageRaw = maxChargingVoltageRaw,
        thermalStatus = thermalStatus,
        screenOn = screenOn,
        hingeDeg = hingeDeg,
    )

    private fun session(
        id: Long = 1L,
        startedAtMs: Long = 0L,
        endedAtMs: Long? = 1000L,
        endReason: String? = null,
        samplerProfileId: String = "default",
        schemaVersion: Int = 1,
        startLevel: Int? = null,
        endLevel: Int? = null,
        startChargeCounterRaw: Long? = null,
        endChargeCounterRaw: Long? = null,
        sourceFile: String = "test.log",
    ) = SessionEntity(
        id = id,
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        endReason = endReason,
        samplerProfileId = samplerProfileId,
        schemaVersion = schemaVersion,
        startLevel = startLevel,
        endLevel = endLevel,
        startChargeCounterRaw = startChargeCounterRaw,
        endChargeCounterRaw = endChargeCounterRaw,
        sourceFile = sourceFile,
    )

    @Test
    fun `duration is endedAtMs minus startedAtMs`() {
        val facts = sessionFacts(session(startedAtMs = 100L, endedAtMs = 1100L), emptyList())
        assertEquals(1000L, facts.durationMs)
    }

    @Test
    fun `duration is null when endedAtMs is null`() {
        val facts = sessionFacts(session(endedAtMs = null), emptyList())
        assertNull(facts.durationMs)
    }

    @Test
    fun `energyAh is endChargeCounterRaw minus startChargeCounterRaw divided by 1 million`() {
        val facts = sessionFacts(
            session(startChargeCounterRaw = 100_000L, endChargeCounterRaw = 3_100_000L),
            emptyList()
        )
        assertEquals(3.0, facts.energyAh!!, 0.0001)
    }

    @Test
    fun `energyAh is null when startChargeCounterRaw is null`() {
        val facts = sessionFacts(
            session(startChargeCounterRaw = null, endChargeCounterRaw = 3_100_000L),
            emptyList()
        )
        assertNull(facts.energyAh)
    }

    @Test
    fun `energyAh is null when endChargeCounterRaw is null`() {
        val facts = sessionFacts(
            session(startChargeCounterRaw = 100_000L, endChargeCounterRaw = null),
            emptyList()
        )
        assertNull(facts.energyAh)
    }

    @Test
    fun `peakW is max watts over all samples`() {
        val samples = listOf(
            sample(elapsedRealtimeMs = 0L, currentRaw = 1_000_000L, voltageRaw = 5_000),
            sample(elapsedRealtimeMs = 1_000L, currentRaw = 3_000_000L, voltageRaw = 5_000),
            sample(elapsedRealtimeMs = 2_000L, currentRaw = 2_000_000L, voltageRaw = 5_000),
        )
        val facts = sessionFacts(session(), samples)
        assertEquals(15.0, facts.peakW!!, 0.0001)
    }

    @Test
    fun `peakW ignores a sample the battery was driving rather than receiving`() {
        val samples = listOf(
            sample(elapsedRealtimeMs = 0L, currentRaw = 1_000_000L, voltageRaw = 5_000),
            sample(elapsedRealtimeMs = 1_000L, currentRaw = -3_000_000L, voltageRaw = 5_000),
            sample(elapsedRealtimeMs = 2_000L, currentRaw = 2_000_000L, voltageRaw = 5_000),
        )
        val facts = sessionFacts(sessionWith(gaugeProfileId = "gauge-qbg", deviceKind = DeviceKinds.WATCH), samples)
        assertEquals(10.0, facts.peakW!!, 0.0001)
    }

    @Test
    fun `peakW is null when every sample ran the battery down`() {
        val samples = listOf(
            sample(elapsedRealtimeMs = 0L, currentRaw = -1_000_000L, voltageRaw = 5_000),
            sample(elapsedRealtimeMs = 1_000L, currentRaw = -3_000_000L, voltageRaw = 5_000),
        )
        assertNull(sessionFacts(sessionWith(gaugeProfileId = "gauge-qbg", deviceKind = DeviceKinds.WATCH), samples).peakW)
    }

    @Test
    fun `peakW keeps the magnitude when the sign convention was never measured`() {
        val samples = listOf(
            sample(elapsedRealtimeMs = 0L, currentRaw = -3_000_000L, voltageRaw = 5_000),
            sample(elapsedRealtimeMs = 1_000L, currentRaw = 1_000_000L, voltageRaw = 5_000),
        )
        val unmeasured = sessionWith(gaugeProfileId = "gauge-unknown", deviceKind = DeviceKinds.WATCH)
        assertEquals(15.0, sessionFacts(unmeasured, samples).peakW!!, 0.0001)
    }

    @Test
    fun `peakW is null when no samples have current and voltage`() {
        val samples = listOf(
            sample(elapsedRealtimeMs = 0L),
            sample(elapsedRealtimeMs = 1_000L),
        )
        val facts = sessionFacts(session(), samples)
        assertNull(facts.peakW)
    }

    @Test
    fun `maxTempC is max temperature over all samples`() {
        val samples = listOf(
            sample(elapsedRealtimeMs = 0L, tempDeciC = 250),
            sample(elapsedRealtimeMs = 1_000L, tempDeciC = 350),
            sample(elapsedRealtimeMs = 2_000L, tempDeciC = 300),
        )
        val facts = sessionFacts(session(), samples)
        assertEquals(35.0, facts.maxTempC!!, 0.0001)
    }

    @Test
    fun `maxTempC is null when no samples have temperature`() {
        val samples = listOf(
            sample(elapsedRealtimeMs = 0L),
            sample(elapsedRealtimeMs = 1_000L),
        )
        val facts = sessionFacts(session(), samples)
        assertNull(facts.maxTempC)
    }

    @Test
    fun `source uses first non-null plugged value`() {
        val samples = listOf(
            sample(elapsedRealtimeMs = 0L, plugged = null),
            sample(elapsedRealtimeMs = 1_000L, plugged = 4),
            sample(elapsedRealtimeMs = 2_000L, plugged = 1),
        )
        val facts = sessionFacts(session(), samples)
        assertEquals(ChargeSource.WIRELESS, facts.source)
    }

    @Test
    fun `source skips a pre-plug first sample`() {
        val samples = listOf(
            sample(elapsedRealtimeMs = 0L, plugged = 0),
            sample(elapsedRealtimeMs = 1_000L, plugged = 0),
            sample(elapsedRealtimeMs = 2_000L, plugged = 1),
        )
        assertEquals(ChargeSource.WIRED, sessionFacts(session(), samples).source)
    }

    @Test
    fun `source is UNKNOWN when no sample ever reports a plug`() {
        val samples = listOf(
            sample(elapsedRealtimeMs = 0L, plugged = 0),
            sample(elapsedRealtimeMs = 1_000L, plugged = null),
        )
        assertEquals(ChargeSource.UNKNOWN, sessionFacts(session(), samples).source)
    }

    @Test
    fun `chargeSourceOf is WIRED for plugged 1`() {
        assertEquals(ChargeSource.WIRED, chargeSourceOf(1))
    }

    @Test
    fun `chargeSourceOf is WIRED for plugged 2`() {
        assertEquals(ChargeSource.WIRED, chargeSourceOf(2))
    }

    @Test
    fun `chargeSourceOf is WIRELESS for plugged 4`() {
        assertEquals(ChargeSource.WIRELESS, chargeSourceOf(4))
    }

    @Test
    fun `chargeSourceOf is DOCK for plugged 8`() {
        assertEquals(ChargeSource.DOCK, chargeSourceOf(8))
    }

    @Test
    fun `chargeSourceOf is UNKNOWN for null`() {
        assertEquals(ChargeSource.UNKNOWN, chargeSourceOf(null))
    }

    @Test
    fun `chargeSourceOf is UNKNOWN for an unrecognized value`() {
        assertEquals(ChargeSource.UNKNOWN, chargeSourceOf(99))
    }

    @Test
    fun `negotiatedW converts uA times uV to watts`() {
        assertEquals(15.0, negotiatedW(3_000_000, 5_000_000)!!, 1e-9)
    }

    @Test
    fun `negotiatedW is null when maxChargingCurrentRaw is null`() {
        assertNull(negotiatedW(null, 5_000_000))
    }

    @Test
    fun `negotiatedW is null when maxChargingVoltageRaw is null`() {
        assertNull(negotiatedW(3_000_000, null))
    }

    @Test
    fun `negotiatedW is null when both are null`() {
        assertNull(negotiatedW(null, null))
    }

    @Test fun `a watch session's source is DOCK whatever plugged says`() {
        assertEquals(ChargeSource.WIRED, chargeSourceOf(1))
        assertEquals(ChargeSource.DOCK, chargeSourceOf(1, isWatch = true))
        assertEquals(ChargeSource.DOCK, chargeSourceOf(4, isWatch = true))
        assertEquals(ChargeSource.DOCK, chargeSourceOf(null, isWatch = true))
    }

    @Test fun `negotiatedW is null under 50 mA`() {
        assertEquals(null, negotiatedW(300, 5_000_000))
        assertEquals(null, negotiatedW(49_999, 5_000_000))
        assertEquals(5.5, negotiatedW(1_100_000, 5_000_000)!!, 1e-9)
    }

    @Test fun `peakW applies the session's gauge scale`() {
        val s = sampleWith(currentRaw = 350L, voltageRaw = 4_000)
        val phoneSession = sessionWith(gaugeProfileId = null)
        val secSession = sessionWith(gaugeProfileId = "gauge-sec", deviceKind = DeviceKinds.WATCH)
        assertEquals(0.0014, sessionFacts(phoneSession, listOf(s)).peakW!!, 1e-6)
        assertEquals(1.4, sessionFacts(secSession, listOf(s)).peakW!!, 1e-6)
        assertEquals(ChargeSource.DOCK, sessionFacts(secSession, listOf(s)).source)
    }

    @Test fun `a detected-mA unknown gauge reads back in mA, not micro-amps`() {
        val s = sampleWith(currentRaw = 350L, voltageRaw = 4_000)
        val session = sessionWith(gaugeProfileId = "gauge-unknown-ma", deviceKind = DeviceKinds.WATCH)
        assertEquals(1.4, sessionFacts(session, listOf(s)).peakW!!, 1e-6)
    }

    @Test fun `an undeclared counter kind resolves through the gauge catalog, a declared one wins`() {
        val s = sampleWith(currentRaw = 350L, voltageRaw = 4_000)
        assertEquals("COULOMB", sessionFacts(sessionWith(gaugeProfileId = null), listOf(s)).counterKind)
        assertEquals("SOC_DERIVED", sessionFacts(sessionWith(gaugeProfileId = "gauge-qbg", deviceKind = DeviceKinds.WATCH), listOf(s)).counterKind)
        val declared = sessionWith(gaugeProfileId = "gauge-qbg", deviceKind = DeviceKinds.WATCH).copy(counterKind = "COULOMB")
        assertEquals("COULOMB", sessionFacts(declared, listOf(s)).counterKind)
        assertEquals(null, sessionFacts(sessionWith(gaugeProfileId = "gauge-unknown", deviceKind = DeviceKinds.WATCH), listOf(s)).counterKind)
    }

    @Test fun `the gauge's current scale is carried on the facts, resolved through the catalog`() {
        val s = sampleWith(currentRaw = 350L, voltageRaw = 4_000)
        assertEquals(CurrentScale.MICRO_AMP, sessionFacts(sessionWith(gaugeProfileId = null), listOf(s)).currentScale)
        assertEquals(
            CurrentScale.MILLI_AMP,
            sessionFacts(sessionWith(gaugeProfileId = "gauge-sec", deviceKind = DeviceKinds.WATCH), listOf(s)).currentScale,
        )
        assertEquals(CurrentScale.MICRO_AMP, sessionFacts(sessionWith(gaugeProfileId = "gauge-martian"), listOf(s)).currentScale)
    }

    @Test fun `off a phone gauge an undeclared sign convention resolves through the catalog, a declared one wins`() {
        val s = sampleWith(currentRaw = 350L, voltageRaw = 4_000)
        assertEquals(true, sessionFacts(sessionWith(gaugeProfileId = "gauge-qbg", deviceKind = DeviceKinds.WATCH), listOf(s)).chargingPositive)
        val declared = sessionWith(gaugeProfileId = "gauge-qbg", deviceKind = DeviceKinds.WATCH).copy(chargingPositive = false)
        assertEquals(false, sessionFacts(declared, listOf(s)).chargingPositive)
        assertEquals(null, sessionFacts(sessionWith(gaugeProfileId = "gauge-sec", deviceKind = DeviceKinds.WATCH), listOf(s)).chargingPositive)
        assertEquals(null, sessionFacts(sessionWith(gaugeProfileId = "gauge-unknown", deviceKind = DeviceKinds.WATCH), listOf(s)).chargingPositive)
        assertEquals(null, sessionFacts(sessionWith(gaugeProfileId = "gauge-martian"), listOf(s)).chargingPositive)
        assertEquals(false, sessionFacts(sessionWith(gaugeProfileId = "gauge-martian").copy(chargingPositive = false), listOf(s)).chargingPositive)
    }

    private fun charge(currents: List<Long>, counterStep: Long = 800L, levelEvery: Int = 40) = currents.mapIndexed { i, c ->
        sample(
            elapsedRealtimeMs = i * 1_000L, currentRaw = c, voltageRaw = 5_000,
            chargeCounterRaw = 2_000_000L + i * counterStep, level = 40 + i / levelEvery,
        )
    }

    @Test fun `a phone that reports charging as negative reads its true peak, whatever its header guessed`() {
        val samples = charge(List(100) { if (it % 25 == 0) 500_000L else -3_000_000L })
        val guessed = sessionWith(gaugeProfileId = "gauge-phone").copy(chargingPositive = true)
        val facts = sessionFacts(guessed, samples)
        assertEquals(false, facts.chargingPositive)
        assertEquals(15.0, facts.peakW!!, 1e-9)
        assertEquals(false, sessionFacts(sessionWith(gaugeProfileId = null), samples).chargingPositive)
    }

    @Test fun `an owner-style charging-positive phone session keeps its convention and its peak`() {
        val samples = charge(List(100) { if (it == 50) -4_000_000L else 3_000_000L })
        val facts = sessionFacts(sessionWith(gaugeProfileId = "gauge-phone").copy(chargingPositive = true), samples)
        assertEquals(true, facts.chargingPositive)
        assertEquals(15.0, facts.peakW!!, 1e-9)
    }

    @Test fun `a phone session that cannot show its convention has none, and its peak is the magnitude`() {
        val balanced = charge(List(100) { if (it % 2 == 0) 3_000_000L else -4_000_000L })
        val flat = charge(List(100) { -3_000_000L }, counterStep = 0L, levelEvery = 1_000)
        val noCurrent = charge(List(100) { 0L })
        for (samples in listOf(balanced, flat, noCurrent, emptyList())) {
            assertNull(SignEvidence.chargingPositive(samples))
            assertNull(sessionFacts(sessionWith(gaugeProfileId = "gauge-phone"), samples).chargingPositive)
        }
        assertEquals(20.0, sessionFacts(sessionWith(gaugeProfileId = "gauge-phone"), balanced).peakW!!, 1e-9)
    }

    @Test fun `the level stands in for a missing counter, and a counter and level that disagree are no evidence`() {
        val levelOnly = charge(List(100) { -3_000_000L }).map { it.copy(chargeCounterRaw = null) }
        assertEquals(false, SignEvidence.chargingPositive(levelOnly))
        val falling = charge(List(100) { -3_000_000L }, counterStep = -800L)
        assertNull(SignEvidence.chargingPositive(falling))
        val draining = charge(List(100) { -3_000_000L }, counterStep = -800L).map { it.copy(level = 90 - it.elapsedRealtimeMs.toInt() / 40_000) }
        assertEquals(true, SignEvidence.chargingPositive(draining))
    }

    private fun sampleWith(currentRaw: Long?, voltageRaw: Int?) = sample(elapsedRealtimeMs = 0L, currentRaw = currentRaw, voltageRaw = voltageRaw)

    private fun sessionWith(gaugeProfileId: String?, deviceKind: String = DeviceKinds.PHONE) = SessionEntity(
        id = 1L, startedAtMs = 0L, endedAtMs = 1000L, endReason = null, samplerProfileId = "default",
        schemaVersion = 1, startLevel = null, endLevel = null, startChargeCounterRaw = null,
        endChargeCounterRaw = null, sourceFile = "test.log", deviceKind = deviceKind, gaugeProfileId = gaugeProfileId,
    )
}
