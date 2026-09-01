// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.measure

import io.github.allenbw.chargelog.data.SampleEntity
import io.github.allenbw.chargelog.data.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportCsvTest {

    private fun sample(
        sessionId: Long = 42L,
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
        id: Long = 42L,
        startedAtMs: Long = 0L,
        endedAtMs: Long? = 1000L,
        endReason: String? = null,
        samplerProfileId: String = "default",
        schemaVersion: Int = 1,
        startLevel: Int? = null,
        endLevel: Int? = null,
        startChargeCounterRaw: Long? = null,
        endChargeCounterRaw: Long? = null,
        sourceFile: String = "session-42.ndjson",
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

    private val columnHeader =
        "elapsed_s,wall_clock_ms,watts,level_pct,temp_c,voltage_mv,current_ua,charge_counter_uah,thermal_status,screen_on,hinge_deg"

    @Test
    fun `header line contains the honesty phrase`() {
        val csv = ExportCsv.csv(session(), listOf(sample(elapsedRealtimeMs = 1_000L)), "ChargeLog")
        val headerLine = csv.lines().first()
        assertTrue(headerLine.contains("battery-side, uncalibrated"))
    }

    @Test
    fun `header line names the host app`() {
        val csv = ExportCsv.csv(session(), listOf(sample(elapsedRealtimeMs = 1_000L)), "SampleApp")
        assertTrue(csv.startsWith("# SampleApp session "))
    }

    @Test
    fun `header line contains the session id`() {
        val csv = ExportCsv.csv(session(id = 42L), listOf(sample(elapsedRealtimeMs = 1_000L)), "ChargeLog")
        val headerLine = csv.lines().first()
        assertTrue(headerLine.contains("42"))
    }

    @Test
    fun `second line is the exact column header`() {
        val csv = ExportCsv.csv(session(), listOf(sample(elapsedRealtimeMs = 1_000L)), "ChargeLog")
        assertEquals(columnHeader, csv.lines()[1])
    }

    @Test
    fun `row count matches sample count`() {
        val samples = listOf(
            sample(elapsedRealtimeMs = 1_000L),
            sample(elapsedRealtimeMs = 2_000L),
            sample(elapsedRealtimeMs = 3_000L),
        )
        val csv = ExportCsv.csv(session(), samples, "ChargeLog")
        val dataLines = csv.trimEnd('\n').lines().drop(2)
        assertEquals(3, dataLines.size)
    }

    @Test
    fun `watts column is Units watts formatted to three decimal places`() {
        val csv = ExportCsv.csv(
            session(),
            listOf(sample(elapsedRealtimeMs = 1_000L, currentRaw = 3_000_000L, voltageRaw = 5_000)),
            "ChargeLog",
        )
        val row = csv.trimEnd('\n').lines()[2].split(",")
        assertEquals("15.000", row[2])
    }

    @Test
    fun `watts column is empty string when current is null`() {
        val csv = ExportCsv.csv(
            session(),
            listOf(sample(elapsedRealtimeMs = 1_000L, currentRaw = null, voltageRaw = 5_000)),
            "ChargeLog",
        )
        val row = csv.trimEnd('\n').lines()[2].split(",")
        assertEquals("", row[2])
    }

    @Test
    fun `temp_c column is formatted to one decimal place`() {
        val csv = ExportCsv.csv(session(), listOf(sample(elapsedRealtimeMs = 1_000L, tempDeciC = 312)), "ChargeLog")
        val row = csv.trimEnd('\n').lines()[2].split(",")
        assertEquals("31.2", row[4])
    }

    @Test
    fun `temp_c column is empty string when null`() {
        val csv = ExportCsv.csv(session(), listOf(sample(elapsedRealtimeMs = 1_000L, tempDeciC = null)), "ChargeLog")
        val row = csv.trimEnd('\n').lines()[2].split(",")
        assertEquals("", row[4])
    }

    @Test
    fun `nullable raw columns render as empty string when null`() {
        val csv = ExportCsv.csv(session(), listOf(sample(elapsedRealtimeMs = 1_000L)), "ChargeLog")
        val row = csv.trimEnd('\n').lines()[2].split(",")
        // level_pct, voltage_mv, current_ua, charge_counter_uah, thermal_status, screen_on, hinge_deg
        assertEquals("", row[3])
        assertEquals("", row[5])
        assertEquals("", row[6])
        assertEquals("", row[7])
        assertEquals("", row[8])
        assertEquals("", row[9])
        assertEquals("", row[10])
    }

    @Test
    fun `elapsed_s is offset from the first sample elapsedRealtimeMs in seconds`() {
        val samples = listOf(
            sample(elapsedRealtimeMs = 1_000L),
            sample(elapsedRealtimeMs = 3_500L),
        )
        val csv = ExportCsv.csv(session(), samples, "ChargeLog")
        val rows = csv.trimEnd('\n').lines().drop(2)
        assertEquals("0.000", rows[0].split(",")[0])
        assertEquals("2.500", rows[1].split(",")[0])
    }

    @Test
    fun `suggestedCsvName is chargelog dash id dot csv`() {
        assertEquals("chargelog-42.csv", ExportCsv.suggestedCsvName(session(id = 42L), "chargelog"))
    }

    @Test
    fun `suggestedRawName is the session source file`() {
        assertEquals("session-42.ndjson", ExportCsv.suggestedRawName(session(sourceFile = "session-42.ndjson")))
    }
}
