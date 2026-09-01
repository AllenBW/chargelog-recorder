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
            // BatteryManager.EXTRA_MAX_CHARGING_CURRENT/VOLTAGE are @hide framework
            // constants, absent from the public SDK stub jar; their string keys are
            // stable (AOSP frameworks/base/core/java/android/os/BatteryManager.java)
            // and the sticky intent still carries them at runtime.
            maxChargingCurrentRaw = extra("max_charging_current"),
            maxChargingVoltageRaw = extra("max_charging_voltage"),
            atElapsedMs = SystemClock.elapsedRealtime(),
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
            thermalStatus = null, // set by the service from its thermal listener
            screenOn = screenOn,
            hingeDeg = hingeDeg,
        ).let { s -> s }
    }
}
