// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.measure

import io.github.allenbw.chargelog.data.SampleEntity
import io.github.allenbw.chargelog.data.SessionEntity
import java.util.Locale

/**
 * Per-session CSV export: a comment header states the host app's name, session id,
 * source file, and the same honesty note used elsewhere (battery-side, uncalibrated — see Units
 * doc) so a row of numbers in a spreadsheet is never mistaken for calibrated wall power. Pure
 * and testable off-device.
 */
object ExportCsv {
    private const val HONESTY_NOTE = "battery-side, uncalibrated"
    private val COLUMNS = listOf(
        "elapsed_s", "wall_clock_ms", "watts", "level_pct", "temp_c", "voltage_mv",
        "current_ua", "charge_counter_uah", "thermal_status", "screen_on", "hinge_deg",
    )

    fun csv(session: SessionEntity, samples: List<SampleEntity>, appName: String): String {
        val e0 = samples.firstOrNull()?.elapsedRealtimeMs ?: 0L
        val sb = StringBuilder()
        sb.append("# $appName session ${session.id} · source ${session.sourceFile} · $HONESTY_NOTE\n")
        sb.append(COLUMNS.joinToString(",")).append('\n')
        for (s in samples) sb.append(row(s, e0)).append('\n')
        return sb.toString()
    }

    private fun row(s: SampleEntity, e0: Long): String {
        val elapsedS = (s.elapsedRealtimeMs - e0) / 1000.0
        val watts = Units.watts(s.currentRaw, s.voltageRaw)
        val tempC = Units.tempC(s.tempDeciC)
        return listOf(
            String.format(Locale.US, "%.3f", elapsedS),
            s.wallClockMs.toString(),
            watts?.let { String.format(Locale.US, "%.3f", it) } ?: "",
            s.level?.toString() ?: "",
            tempC?.let { String.format(Locale.US, "%.1f", it) } ?: "",
            s.voltageRaw?.toString() ?: "",
            s.currentRaw?.toString() ?: "",
            s.chargeCounterRaw?.toString() ?: "",
            s.thermalStatus?.toString() ?: "",
            s.screenOn?.toString() ?: "",
            s.hingeDeg?.toString() ?: "",
        ).joinToString(",")
    }

    fun suggestedCsvName(session: SessionEntity, prefix: String): String = "$prefix-${session.id}.csv"

    fun suggestedRawName(session: SessionEntity): String = session.sourceFile
}
