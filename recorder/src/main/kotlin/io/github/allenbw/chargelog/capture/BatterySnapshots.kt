// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import android.content.Intent
import android.os.BatteryManager
import android.os.SystemClock
import io.github.allenbw.chargelog.capture.log.RawLine

/**
 * Merges the two clocks the platform exposes: property reads (current,
 * charge counter — polled) and the sticky ACTION_BATTERY_CHANGED intent
 * (voltage, temp, level, plug — pushed). There is NO BatteryManager property
 * for voltage or temperature. voltageAgeMs records the staleness of the
 * intent-side values relative to the property read.
 */
class BatterySnapshots(private val bm: BatteryManager) {

    companion object {
        /** `BatteryManager.EXTRA_CHARGING_STATUS` — public in the SDK only from API 36, while this
         *  module's minSdk is 31. The value is an inlined string on every API, and like the two
         *  hidden cap keys below, the key is stable in AOSP's `BatteryManager.java`. */
        const val EXTRA_CHARGING_STATUS = "android.os.extra.CHARGING_STATUS"

        /** `BatteryManager.EXTRA_CYCLE_COUNT` — public from API 34, above this module's minSdk,
         *  and an inlined string on every API; read by its stable key for the same reason. */
        const val EXTRA_CYCLE_COUNT = "android.os.extra.CYCLE_COUNT"
    }

    data class Sticky(
        val voltageRaw: Int?,
        val tempDeciC: Int?,
        val level: Int?,
        val scale: Int?,
        val status: Int?,
        val plugged: Int?,
        val maxChargingCurrentRaw: Int?,
        val maxChargingVoltageRaw: Int?,
        val atElapsedMs: Long,
        /** The platform's charging attribution (`measure/ChargingStatus`), or null when the key
         *  was absent from this intent. Defaulted so a caller building a Sticky by hand compiles. */
        val chargingStatus: Int? = null,
        /** The platform's battery cycle count (`EXTRA_CYCLE_COUNT`), or null when the key was
         *  absent from this intent. Defaulted like [chargingStatus]. */
        val cycleCount: Int? = null,
    )

    @Volatile var lastSticky: Sticky? = null
        private set

    fun onBatteryChanged(intent: Intent) {
        fun extra(name: String): Int? = Sentinels.intOrNull(intent.getIntExtra(name, Int.MIN_VALUE))
        lastSticky = Sticky(
            voltageRaw = extra(BatteryManager.EXTRA_VOLTAGE),
            tempDeciC = extra(BatteryManager.EXTRA_TEMPERATURE),
            level = extra(BatteryManager.EXTRA_LEVEL),
            scale = extra(BatteryManager.EXTRA_SCALE),
            status = extra(BatteryManager.EXTRA_STATUS),
            plugged = extra(BatteryManager.EXTRA_PLUGGED),
            // TRAP docs/traps/hidden-battery-extras.md — @hide extras, read by literal key
            maxChargingCurrentRaw = extra("max_charging_current"),
            maxChargingVoltageRaw = extra("max_charging_voltage"),
            atElapsedMs = SystemClock.elapsedRealtime(),
            chargingStatus = extra(EXTRA_CHARGING_STATUS),
            cycleCount = extra(EXTRA_CYCLE_COUNT),
        )
    }

    fun sample(screenOn: Boolean?, hingeDeg: Float?): RawLine.Sample {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val sticky = lastSticky
        return RawLine.Sample(
            t = now,
            e = elapsed,
            currentRaw = Sentinels.longOrNull(
                bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)),
            chargeCounterRaw = Sentinels.longOrNull(
                bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)),
            voltageRaw = sticky?.voltageRaw,
            voltageAgeMs = sticky?.let { elapsed - it.atElapsedMs },
            tempDeciC = sticky?.tempDeciC,
            level = sticky?.level,
            scale = sticky?.scale,
            status = sticky?.status,
            plugged = sticky?.plugged,
            maxChargingCurrentRaw = sticky?.maxChargingCurrentRaw,
            maxChargingVoltageRaw = sticky?.maxChargingVoltageRaw,
            thermalStatus = null,
            screenOn = screenOn,
            hingeDeg = hingeDeg,
            chargingStatus = sticky?.chargingStatus,
        ).let { s -> s }
    }
}
