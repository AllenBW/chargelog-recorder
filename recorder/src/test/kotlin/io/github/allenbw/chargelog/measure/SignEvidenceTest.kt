// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.measure

import io.github.allenbw.chargelog.data.SampleEntity
import io.github.allenbw.chargelog.data.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SignEvidenceTest {

    private fun sample(i: Int, e: Long, currentRaw: Long) = SampleEntity(
        sessionId = 1L, wallClockMs = i * 1_000L, elapsedRealtimeMs = e, currentRaw = currentRaw,
        chargeCounterRaw = 2_000_000L + i * 800L, voltageRaw = 5_000, voltageAgeMs = null, tempDeciC = null,
        level = 40 + i / 40, status = null, plugged = 2, maxChargingCurrentRaw = null, maxChargingVoltageRaw = null,
        thermalStatus = null, screenOn = null, hingeDeg = null,
    )

    private fun rebootedCharge(charging: Long, dip: Long): List<SampleEntity> = (0 until 100).map { i ->
        val e = if (i < REBOOT_AT) BEFORE_REBOOT_E + i * 1_000L else (i - REBOOT_AT) * 1_000L + 1_000L
        sample(i, e, if (i == DIP_AT) dip else charging)
    }

    private val phone = SessionEntity(
        id = 1L, startedAtMs = 0L, endedAtMs = 100_000L, endReason = null, samplerProfileId = "default",
        schemaVersion = 2, startLevel = 40, endLevel = 42, startChargeCounterRaw = null, endChargeCounterRaw = null,
        sourceFile = "session-0.ndjson", gaugeProfileId = GaugeProfiles.PHONE.id,
    )

    @Test fun `a reboot mid-session does not flip a charging-positive phone's convention or lose its peak`() {
        val samples = rebootedCharge(charging = 3_000_000L, dip = -500_000L)
        assertEquals(true, SignEvidence.chargingPositive(samples))
        assertEquals(15.0, sessionFacts(phone, samples).peakW!!, 1e-9)
    }

    @Test fun `a reboot mid-session does not flip a charging-negative phone's convention or lose its peak`() {
        val samples = rebootedCharge(charging = -3_000_000L, dip = 500_000L)
        assertEquals(false, SignEvidence.chargingPositive(samples))
        assertEquals(15.0, sessionFacts(phone, samples).peakW!!, 1e-9)
    }

    private companion object {
        const val REBOOT_AT = 50
        const val DIP_AT = 25
        const val BEFORE_REBOOT_E = 10_000_000L
    }
}
