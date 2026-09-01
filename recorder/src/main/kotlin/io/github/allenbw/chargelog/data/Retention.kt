// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

/** How long recorded charging sessions are kept before being pruned. */
enum class RetentionWindow {
    FOREVER,
    ONE_YEAR,
    SIX_MONTHS;

    companion object {
        /** Falls back to [FOREVER] on `null` or an unrecognized stored name: never delete a
         *  user's history because of a parse failure. */
        fun fromStored(s: String?): RetentionWindow =
            entries.find { it.name == s } ?: FOREVER
    }
}

/**
 * Pure retention math: how far back a [RetentionWindow] reaches, and whether a given session's
 * start falls before that reach. No I/O, no `Context`. Callers decide *when* to prune and use
 * [Replay.deleteSessionCompletely] for the deletion — this object only decides which sessions
 * qualify.
 */
object Retention {
    /** 365 24-hour days, in milliseconds — [RetentionWindow.ONE_YEAR] is defined as this exact
     *  span, not "one calendar year back", so [cutoffMs] is plain arithmetic. */
    private const val ONE_YEAR_MS = 365L * 24 * 3600 * 1000

    /** 182 24-hour days, in milliseconds — [RetentionWindow.SIX_MONTHS]'s exact span. */
    private const val SIX_MONTHS_MS = 182L * 24 * 3600 * 1000

    /** The oldest instant, relative to [nowMs], that [window] keeps. `null` means keep forever —
     *  no cutoff, so [isPrunable] can never return `true` against it. */
    fun cutoffMs(nowMs: Long, window: RetentionWindow): Long? = when (window) {
        RetentionWindow.FOREVER -> null
        RetentionWindow.ONE_YEAR -> nowMs - ONE_YEAR_MS
        RetentionWindow.SIX_MONTHS -> nowMs - SIX_MONTHS_MS
    }

    /** A session starting at [sessionStartMs] is prunable once it started strictly before
     *  [cutoff]. `cutoff == null` (FOREVER) or a session starting exactly AT the cutoff is never
     *  prunable — the boundary is exclusive. */
    fun isPrunable(sessionStartMs: Long, cutoff: Long?): Boolean =
        cutoff != null && sessionStartMs < cutoff
}
