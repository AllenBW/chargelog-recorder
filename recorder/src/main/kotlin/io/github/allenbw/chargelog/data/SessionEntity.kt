// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import io.github.allenbw.chargelog.capture.log.DeviceKinds

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: Long, // session start wall-clock ms — natural key shared with the raw log filename
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val endReason: String?,
    val samplerProfileId: String,
    val schemaVersion: Int,
    val startLevel: Int?,
    val endLevel: Int?,
    val startChargeCounterRaw: Long?,
    val endChargeCounterRaw: Long?,
    val sourceFile: String,
    // Which device authored this session and what its gauge declared.
    val deviceKind: String = DeviceKinds.PHONE,
    val deviceId: String? = null,
    val deviceModel: String? = null,
    val gaugeProfileId: String? = null,
    val reportsCurrent: Boolean? = null,
    val counterKind: String? = null,
    val hasHinge: Boolean? = null,
)

/** Whether this session was recorded on a watch — the one bit that decides which conventions a
 *  reader applies to it: the gauge scale, the DOCK source label, and the top-of-charge hold rule. */
val SessionEntity.isWatch: Boolean get() = deviceKind == DeviceKinds.WATCH
