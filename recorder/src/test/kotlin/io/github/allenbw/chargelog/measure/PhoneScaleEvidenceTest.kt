// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.measure

import io.github.allenbw.chargelog.capture.BatteryStatus
import io.github.allenbw.chargelog.data.SampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneScaleEvidenceTest {

    private fun row(i: Int, currentRaw: Long?, level: Int, status: Int = BatteryStatus.CHARGING, stepMs: Long = 1_000L) =
        SampleEntity(
            sessionId = 1L, wallClockMs = i * stepMs, elapsedRealtimeMs = i * stepMs, currentRaw = currentRaw,
            chargeCounterRaw = null, voltageRaw = 4_000, voltageAgeMs = null, tempDeciC = null, level = level,
            status = status, plugged = 2, maxChargingCurrentRaw = null, maxChargingVoltageRaw = null,
            thermalStatus = null, screenOn = null, hingeDeg = null,
        )

    private fun charge(n: Int, currentRaw: Long, fromLevel: Int, secondsPerPoint: Int, status: Int = BatteryStatus.CHARGING) =
        (0 until n).map { row(it, currentRaw, fromLevel + it / secondsPerPoint, status) }

    private fun PhoneScaleEvidence.feed(rows: List<SampleEntity>): Boolean? {
        var verdict: Boolean? = null
        rows.forEach { verdict = offer(it.elapsedRealtimeMs, it.currentRaw, it.level, it.status) ?: verdict }
        return verdict
    }

    @Test fun `a milliamp phone charging is proven once its level has risen`() {
        val evidence = PhoneScaleEvidence()
        val rows = charge(n = 400, currentRaw = 2_000L, fromLevel = 40, secondsPerPoint = 72)
        assertNull(evidence.feed(rows.take(PhoneScaleEvidence.MIN_LEVEL_RISE * 72)))
        assertEquals(true, evidence.feed(rows.drop(PhoneScaleEvidence.MIN_LEVEL_RISE * 72)))
        assertTrue(PhoneScaleEvidence.speaksMilliAmps(rows))
    }

    @Test fun `a microamp phone charging is ruled out once the window fills`() {
        val evidence = PhoneScaleEvidence()
        val rows = charge(n = 60, currentRaw = 2_000_000L, fromLevel = 40, secondsPerPoint = 72)
        assertNull(evidence.feed(rows.take(GaugeScaleProbe.SAMPLES - 1)))
        assertEquals(false, evidence.feed(rows.drop(GaugeScaleProbe.SAMPLES - 1)))
        assertFalse(PhoneScaleEvidence.speaksMilliAmps(rows))
    }

    @Test fun `a full battery's microamp trickle is never evidence`() {
        val full = (0 until 2_000).map { row(it, if (it % 2 == 0) 2_865L else -1_823L, 100, BatteryStatus.FULL) }
        val toppingOff = (0 until 2_000).map { row(it, 1_563L, 100) }
        assertNull(PhoneScaleEvidence().feed(full))
        assertNull(PhoneScaleEvidence().feed(toppingOff))
        assertFalse(PhoneScaleEvidence.speaksMilliAmps(full))
        assertFalse(PhoneScaleEvidence.speaksMilliAmps(toppingOff))
    }

    @Test fun `a microamp hold below ninety is never evidence, however long`() {
        val hold = (0 until 3_000).map { row(it, 2_000L, 80, stepMs = 100_000L) }
        assertNull(PhoneScaleEvidence().feed(hold))
        assertFalse(PhoneScaleEvidence.speaksMilliAmps(hold))
    }

    @Test fun `a microamp hold that fills the window is not rescued by the charge after it`() {
        val hold = (0 until 30).map { row(it, 2_000L, 70) }
        val charge = (30 until 1_000).map { row(it, 2_000_000L, 70 + (it - 30) / 72) }
        assertNull(PhoneScaleEvidence().feed(hold + charge))
        assertFalse(PhoneScaleEvidence.speaksMilliAmps(hold + charge))
    }

    @Test fun `zeros, nulls and discharging samples are not evidence`() {
        val rows = (0 until 500).map { row(it, if (it % 2 == 0) 0L else null, 40 + it / 72) } +
            (0 until 500).map { row(it, 2_000L, 40 + it / 72, BatteryStatus.DISCHARGING) }
        assertNull(PhoneScaleEvidence().feed(rows))
    }

    @Test fun `every real microamp phone recording in the corpus reads as microamps`() {
        val phones = listOf(
            "blip.ndjson", "session-1787605010301.ndjson", "session-1787606163594.ndjson",
            "session-1787616748485.ndjson", "recovered/session-1787607880183.ndjson",
            "recovered/session-1787681709815.ndjson", "recovered/session-1787682386321.ndjson",
            "recovered/session-1787790913803.ndjson",
        )
        phones.forEach { assertFalse(it, PhoneScaleEvidence.speaksMilliAmps(RecorderCorpus.parsed(it).samples)) }
    }
}
