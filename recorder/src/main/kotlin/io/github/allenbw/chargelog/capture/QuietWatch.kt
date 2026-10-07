// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

internal class QuietWatch(
    private val clock: () -> Long,
    private val repost: () -> Unit,
    private val rearm: () -> Unit,
) {
    companion object {
        const val PERIOD_MS = 60_000L
    }

    @Volatile private var lastBeatMs = clock()

    fun beat() {
        lastBeatMs = clock()
    }

    fun check(recording: Boolean, mode: SamplingMode, tickerAlive: Boolean) {
        if (!recording || mode != SamplingMode.TICK || clock() - lastBeatMs < PERIOD_MS) return
        repost()
        if (!tickerAlive) rearm()
    }
}
