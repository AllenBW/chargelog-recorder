// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import io.github.allenbw.chargelog.capture.log.DeviceKinds

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: Long,
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
    val deviceKind: String = DeviceKinds.PHONE,
    val deviceId: String? = null,
    val deviceModel: String? = null,
    val gaugeProfileId: String? = null,
    val reportsCurrent: Boolean? = null,
    val counterKind: String? = null,
    val hasHinge: Boolean? = null,
    val osRelease: String? = null,
    val appVersion: String? = null,
    val socModel: String? = null,
    val totalMemBytes: Long? = null,
    /** Version 4: the cell's design capacity in mAh at capture time. Recorded per session rather
     *  than looked up live, so a synced watch row and the phone's own history each keep the
     *  capacity their cell actually had. */
    val designCapacityMah: Int? = null,
    /** Version 5: the gauge's current-sign convention as the header declared it
     *  (`Capabilities.chargingPositive`). Null on a legacy header; `sessionFacts` resolves that
     *  through the gauge catalog by [gaugeProfileId], the way it resolves [counterKind]. */
    val chargingPositive: Boolean? = null,
)

/** Whether this session was recorded on a watch — the one bit that decides which conventions a
 *  reader applies to it: the gauge scale, the DOCK source label, and the top-of-charge hold rule. */
val SessionEntity.isWatch: Boolean get() = deviceKind == DeviceKinds.WATCH
