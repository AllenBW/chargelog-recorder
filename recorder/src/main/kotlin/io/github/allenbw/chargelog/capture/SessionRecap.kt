// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.measure.ChargeSource

/**
 * Display-ready facts about the most recently finished session,
 * derived from the raw log alone by the recording service's recap parser.
 */
data class SessionRecap(
    val endedAtMs: Long,
    val durationMs: Long?,
    val startLevel: Int?,
    val endLevel: Int?,
    val energyAh: Double?,
    val peakW: Double?,
    val source: ChargeSource,
    val endReason: String?,
    val sessionId: Long,
)
