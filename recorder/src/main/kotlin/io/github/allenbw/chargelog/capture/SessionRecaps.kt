// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.data.Replay
import io.github.allenbw.chargelog.measure.sessionFacts
import java.io.File

internal fun recapOf(file: File): SessionRecap? {
    val parsed = Replay.parse(file) ?: return null
    val endedAt = parsed.session.endedAtMs ?: parsed.samples.lastOrNull()?.wallClockMs ?: return null
    val facts = sessionFacts(parsed.session, parsed.samples)
    return SessionRecap(
        endedAtMs = endedAt,
        durationMs = facts.durationMs,
        startLevel = facts.startLevel,
        endLevel = facts.endLevel,
        energyAh = facts.energyAh,
        peakW = facts.peakW,
        source = facts.source,
        endReason = parsed.session.endReason,
        sessionId = parsed.session.id,
    )
}
