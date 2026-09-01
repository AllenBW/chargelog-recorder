// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import androidx.room3.Entity

@Entity(tableName = "samples", primaryKeys = ["sessionId", "elapsedRealtimeMs"])
data class SampleEntity(
    val sessionId: Long,
    val wallClockMs: Long,
    val elapsedRealtimeMs: Long,
    val currentRaw: Long?,
    val chargeCounterRaw: Long?,
    val voltageRaw: Int?,
    val voltageAgeMs: Long?,
    val tempDeciC: Int?,
    val level: Int?,
    val status: Int?,
    val plugged: Int?,
    val maxChargingCurrentRaw: Int?,
    val maxChargingVoltageRaw: Int?,
    val thermalStatus: Int?,
    val screenOn: Boolean?,
    val hingeDeg: Float?,
)
