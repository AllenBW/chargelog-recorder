// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.capture.log.RawLine
import io.github.allenbw.chargelog.measure.AnalyzerSample
import io.github.allenbw.chargelog.measure.Units
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local bridge from the capture pump to the UI. WRITE SIDE IS PUMP-ONLY:
 * every mutator is called from RecordingService.execute(), which runs on the
 * single input-pump coroutine — the same confinement contract as the writer.
 * The UI only collects [state].
 */
object LiveFeed {
    private const val RING = 600 // ~10 min at 1 Hz

    data class Snapshot(
        val sample: RawLine.Sample?,
        val recording: Boolean,
        val sessionStartMs: Long?,
        val recentWatts: List<Double>,
        val recentLevels: List<Pair<Long, Int>>,
        val history: List<AnalyzerSample>,
    )

    private val watts = ArrayDeque<Double>()
    private val levels = ArrayDeque<Pair<Long, Int>>()

    /**
     * Every sample of the current (or, after [onClose], just-finished) session, so a live
     * session can be handed to an analysis the same way a stored one is.
     *
     * Deliberately UNBOUNDED — unlike [watts]/[levels] above, which only feed a sparkline and
     * are allowed to forget. A multi-hour 1 Hz session is on the order of 15k compact value
     * objects, which is an acceptable amount of memory to hold. Consumers may depend on the
     * session's *earliest* samples, which are exactly what a size cap would evict first, and
     * the loss would not show up as an error. Do not cap this list.
     *
     * A plain `ArrayList` is safe here for the same reason [watts]/[levels] are: this whole
     * object is pump-confined (see the class KDoc), so nothing else ever mutates it. Do not add
     * synchronization — a lock here would misleadingly imply the confinement no longer holds.
     */
    private val history = ArrayList<AnalyzerSample>()
    private var sessionStartMs: Long? = null
    private val _state = MutableStateFlow<Snapshot?>(null)
    val state: StateFlow<Snapshot?> = _state.asStateFlow()

    fun onOpen(sessionStartMs: Long) {
        this.sessionStartMs = sessionStartMs
        watts.clear(); levels.clear(); history.clear()
        // Seeded, never copied: a copy-on-maybe-stale here would briefly
        // broadcast recording=true carrying the PRIOR session's last sample.
        _state.value = Snapshot(sample = null, recording = true, sessionStartMs = sessionStartMs,
            recentWatts = emptyList(), recentLevels = emptyList(), history = emptyList())
    }

    fun onSample(s: RawLine.Sample) {
        Units.watts(s.currentRaw, s.voltageRaw)?.let { watts.addLast(it); if (watts.size > RING) watts.removeFirst() }
        s.level?.let { levels.addLast(s.e to it); if (levels.size > RING) levels.removeFirst() }
        history.add(s.toAnalyzerSample())
        _state.value = Snapshot(s, recording = sessionStartMs != null, sessionStartMs = sessionStartMs,
            recentWatts = watts.toList(), recentLevels = levels.toList(), history = history.toList())
    }

    fun onClose() {
        sessionStartMs = null
        // copy(...) carries `history` through unchanged — recap-adjacent UI may still read the
        // just-finished session's full history off the final snapshot.
        _state.value = _state.value?.copy(recording = false, sessionStartMs = null)
    }

    fun reset() { sessionStartMs = null; watts.clear(); levels.clear(); history.clear(); _state.value = null }
}
