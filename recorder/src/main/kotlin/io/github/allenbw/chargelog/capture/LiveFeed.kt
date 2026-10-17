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
    private const val RING = 600

    data class Snapshot(
        val sample: RawLine.Sample?,
        val recording: Boolean,
        val sessionStartMs: Long?,
        val recentWatts: List<Double>,
        val recentLevels: List<Pair<Long, Int>>,
        val history: List<AnalyzerSample>,
        val eventSampled: Boolean = false,
    )

    private val watts = ArrayDeque<Double>()
    private val levels = ArrayDeque<Pair<Long, Int>>()

    private val history = ArrayList<AnalyzerSample>()
    private var sessionStartMs: Long? = null
    private var eventSampled = false
    private val _state = MutableStateFlow<Snapshot?>(null)
    val state: StateFlow<Snapshot?> = _state.asStateFlow()

    fun onOpen(sessionStartMs: Long) {
        this.sessionStartMs = sessionStartMs
        eventSampled = false
        watts.clear(); levels.clear(); history.clear()
        _state.value = Snapshot(sample = null, recording = true, sessionStartMs = sessionStartMs,
            recentWatts = emptyList(), recentLevels = emptyList(), history = emptyList())
    }

    fun onSample(s: RawLine.Sample) {
        Units.watts(s.currentRaw, s.voltageRaw)?.let { watts.addLast(it); if (watts.size > RING) watts.removeFirst() }
        s.level?.let { levels.addLast(s.e to it); if (levels.size > RING) levels.removeFirst() }
        history.add(s.toAnalyzerSample())
        _state.value = Snapshot(s, recording = sessionStartMs != null, sessionStartMs = sessionStartMs,
            recentWatts = watts.toList(), recentLevels = levels.toList(), history = history.toList(),
            eventSampled = eventSampled)
    }

    /**
     * The sampler's mode, as [SamplingMode]: true once a settled charge samples on battery
     * broadcasts alone, where a long silence means the battery has not changed, not that the
     * recorder has gone quiet. A reader judging freshness from the newest sample's age must not
     * read a settled hold as stale. False again at every [onOpen].
     */
    fun onSampling(eventSampled: Boolean) {
        this.eventSampled = eventSampled
        _state.value = _state.value?.copy(eventSampled = eventSampled)
    }

    fun onClose() {
        sessionStartMs = null
        _state.value = _state.value?.copy(recording = false, sessionStartMs = null)
    }

    fun reset() {
        sessionStartMs = null; eventSampled = false; watts.clear(); levels.clear(); history.clear(); _state.value = null
    }
}
