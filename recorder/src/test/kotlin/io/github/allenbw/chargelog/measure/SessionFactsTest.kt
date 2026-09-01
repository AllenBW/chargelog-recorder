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

    // --- chargeSourceOf (extracted mapping) ---

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

    // --- negotiatedW ---

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

    // --- watch-scale facts ---

    @Test fun `a watch session's source is DOCK whatever plugged says`() {
        assertEquals(ChargeSource.WIRED, chargeSourceOf(1))
        assertEquals(ChargeSource.DOCK, chargeSourceOf(1, isWatch = true))
        assertEquals(ChargeSource.DOCK, chargeSourceOf(4, isWatch = true))
        assertEquals(ChargeSource.DOCK, chargeSourceOf(null, isWatch = true))
    }

    @Test fun `negotiatedW is null under 50 mA`() {
        assertEquals(null, negotiatedW(300, 5_000_000))          // Pixel Watch 1's bogus 300 µA
        assertEquals(null, negotiatedW(49_999, 5_000_000))
        assertEquals(5.5, negotiatedW(1_100_000, 5_000_000)!!, 1e-9)
    }

    @Test fun `peakW applies the session's gauge scale`() {
        val s = sampleWith(currentRaw = 350L, voltageRaw = 4_000)       // a Samsung mA reading
        val phoneSession = sessionWith(gaugeProfileId = null)
        val secSession = sessionWith(gaugeProfileId = "gauge-sec", deviceKind = DeviceKinds.WATCH)
        assertEquals(0.0014, sessionFacts(phoneSession, listOf(s)).peakW!!, 1e-6)
        assertEquals(1.4, sessionFacts(secSession, listOf(s)).peakW!!, 1e-6)
        assertEquals(ChargeSource.DOCK, sessionFacts(secSession, listOf(s)).source)
    }

    @Test fun `a detected-mA unknown gauge reads back in mA, not micro-amps`() {
        // `GaugeProfiles.plausibility` writes `gauge-unknown-ma` into the header when it detects an
        // mA gauge behind an UNKNOWN profile. Without a table entry of its own the id resolved back
        // to `gauge-unknown` (µA) and the session read 1000x low — the exact failure the scale field
        // exists to prevent, moved from record time to read time.
        val s = sampleWith(currentRaw = 350L, voltageRaw = 4_000)
        val session = sessionWith(gaugeProfileId = "gauge-unknown-ma", deviceKind = DeviceKinds.WATCH)
        assertEquals(1.4, sessionFacts(session, listOf(s)).peakW!!, 1e-6)
    }

    private fun sampleWith(currentRaw: Long?, voltageRaw: Int?) = sample(elapsedRealtimeMs = 0L, currentRaw = currentRaw, voltageRaw = voltageRaw)

    private fun sessionWith(gaugeProfileId: String?, deviceKind: String = DeviceKinds.PHONE) = SessionEntity(
        id = 1L, startedAtMs = 0L, endedAtMs = 1000L, endReason = null, samplerProfileId = "default",
        schemaVersion = 1, startLevel = null, endLevel = null, startChargeCounterRaw = null,
        endChargeCounterRaw = null, sourceFile = "test.log", deviceKind = deviceKind, gaugeProfileId = gaugeProfileId,
    )
}
