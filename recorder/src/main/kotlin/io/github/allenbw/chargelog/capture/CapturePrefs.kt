// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Settings that govern how a charging session is captured: how often the recording service
 * samples, and whether recording is on at all. Plain SharedPreferences, no UI framework — a host
 * app wraps the flows in whatever reactive layer it uses.
 */
object CapturePrefs {
    /** The SharedPreferences file. Public so a host can keep related settings in the same file. */
    const val FILE = "chargelog_capture"

    private const val KEY_SAMPLE_INTERVAL_S = "sample_interval_s"
    private const val KEY_RECORD_ENABLED = "record_enabled"
    private const val KEY_LEARNED_GAUGE_ID = "learned_gauge_id"

    /** 1 s matches the service's ticker before this setting existed, so it is the default. */
    const val DEFAULT_SAMPLE_INTERVAL_S = 1

    private val INTERVAL_STEPS = intArrayOf(1, 2, 5)

    private const val DEFAULT_RECORD_ENABLED = true

    /** Nearest of [INTERVAL_STEPS] to [s] — the steps are irregular, so nearest-by-distance is the
     *  only rule that generalizes. */
    fun clampInterval(s: Int): Int =
        INTERVAL_STEPS.minByOrNull { kotlin.math.abs(it - s) } ?: DEFAULT_SAMPLE_INTERVAL_S

    /** Clamped on read, so a corrupted or pre-clamp value can never escape [INTERVAL_STEPS]. */
    fun sampleIntervalS(context: Context): Int {
        val stored = prefs(context).getInt(KEY_SAMPLE_INTERVAL_S, DEFAULT_SAMPLE_INTERVAL_S)
        return clampInterval(stored)
    }

    fun setSampleIntervalS(context: Context, s: Int) {
        prefs(context).edit().putInt(KEY_SAMPLE_INTERVAL_S, clampInterval(s)).apply()
    }

    /** Defaults to `true`: installing this preference must never silently turn recording off. */
    fun recordEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RECORD_ENABLED, DEFAULT_RECORD_ENABLED)

    fun setRecordEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_RECORD_ENABLED, on).apply()
    }

    /**
     * A gauge id the recorder RESOLVED from samples, outranking whatever the host's table said —
     * today only [io.github.allenbw.chargelog.measure.GaugeScaleProbe]'s mA detection, which the
     * bundled table cannot make because nothing about a manufacturer implies a scale.
     *
     * Persisted rather than re-derived: a session header is written at plug-in, before any sample
     * exists, so the FIRST charge on an unknown watch is the only one that can learn this, and
     * every charge after it has to be told. Null until something has been learned, which is every
     * device with a characterized gauge.
     *
     * Public because a host reads it too: the recorder's answer is about the instrument, and a
     * host that renders live watts or analyzes sessions is reading the same instrument
     * ([io.github.allenbw.chargelog.capture.RecorderHost.onGaugeRefined] is the push half).
     */
    fun learnedGaugeId(context: Context): String? =
        prefs(context).getString(KEY_LEARNED_GAUGE_ID, null)

    fun setLearnedGaugeId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_LEARNED_GAUGE_ID, id).apply()
    }

    fun sampleIntervalSFlow(context: Context): Flow<Int> = prefsFlow(context) { sampleIntervalS(context) }

    fun recordEnabledFlow(context: Context): Flow<Boolean> = prefsFlow(context) { recordEnabled(context) }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun <T> prefsFlow(context: Context, read: () -> T): Flow<T> {
        val prefs = prefs(context)
        return callbackFlow {
            trySend(read())
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                trySend(read())
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }.distinctUntilChanged()
    }
}
