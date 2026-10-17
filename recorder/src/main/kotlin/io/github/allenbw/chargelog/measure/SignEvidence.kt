// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.measure

import io.github.allenbw.chargelog.data.SampleEntity
import kotlin.math.abs
import kotlin.math.sign

/**
 * A gauge's current-sign convention, read from one session's own samples rather than from a table.
 *
 * The rule is an identity, not a heuristic: the charge counter (or, without one, the level) says
 * which way charge moved over the session, and the time-integrated current must have moved the
 * same way. If it did, positive means into the battery; if it ran opposite, negative does. A
 * phone that reports charging as negative and the owner's charging-positive Pixel both read
 * correctly, from the same rule, with no device table.
 *
 * It answers null — no convention, so [Units.signedWatts] yields nothing and a peak falls back to
 * magnitude — whenever the session cannot show it: no counter or level movement, a counter and a
 * level that moved opposite ways, no current, or currents that mostly cancel (a top-off at 100 %
 * where the battery and the charger trade the load). Never a guess.
 */
object SignEvidence {

    /** The smallest |net| / gross share of the integrated current that counts as one direction:
     *  at 0.5, three quarters of the charge that moved went the same way. */
    const val MIN_NET_SHARE = 0.5

    /** `true` when a positive raw current means charge flowing INTO the battery over [samples],
     *  `false` when it means out, null when the samples cannot say. [samples] are in recording
     *  order; a negative time step (a reboot mid-session) contributes nothing. */
    fun chargingPositive(samples: List<SampleEntity>): Boolean? {
        val gained = gainedCharge(samples) ?: return null
        var net = 0.0
        var gross = 0.0
        for (i in 0 until samples.size - 1) {
            val current = samples[i].currentRaw ?: continue
            val dt = (samples[i + 1].elapsedRealtimeMs - samples[i].elapsedRealtimeMs).coerceAtLeast(0L).toDouble()
            net += current * dt
            gross += abs(current) * dt
        }
        if (gross == 0.0 || abs(net) < MIN_NET_SHARE * gross) return null
        return (net > 0.0) == gained
    }

    private fun gainedCharge(samples: List<SampleEntity>): Boolean? {
        val directions = listOfNotNull(delta(samples) { it.chargeCounterRaw }, delta(samples) { it.level?.toLong() })
            .map { it.sign }
            .filter { it != 0 }
            .distinct()
        return directions.singleOrNull()?.let { it > 0 }
    }

    private fun delta(samples: List<SampleEntity>, value: (SampleEntity) -> Long?): Long? {
        val first = samples.firstNotNullOfOrNull(value) ?: return null
        val last = samples.asReversed().firstNotNullOfOrNull(value) ?: return null
        return last - first
    }
}
