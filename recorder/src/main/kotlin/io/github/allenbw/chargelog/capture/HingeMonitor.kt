// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Best-effort hinge angle. Prefers the wake-up variant (non-wake-up hinge
 * sensors batch or drop events across AP suspend), falls back, and records
 * a distinct "present but silent" state — a documented Pixel Fold failure
 * mode. NEVER gates any session behavior.
 */
class HingeMonitor(
    private val sensorManager: SensorManager,
    private val onTransition: (deg: Float) -> Unit,
) : SensorEventListener {

    /**
     * True when the device exposes a hinge sensor at all; [selectedSensorName] is only known
     * after [start]. This is the capability answer for callers that build a
     * [SamplerProfile] before the monitor has been started — reading [selectedSensorName] there
     * reports `false` on a folding device.
     */
    val available: Boolean = sensorManager.getSensorList(Sensor.TYPE_HINGE_ANGLE).isNotEmpty()

    @Volatile var latestDeg: Float? = null
        private set
    var selectedSensorName: String? = null
        private set
    var eventsSeen: Long = 0
        private set

    fun start() {
        val candidates = sensorManager.getSensorList(Sensor.TYPE_HINGE_ANGLE)
        val sensor = candidates.firstOrNull { it.isWakeUpSensor } ?: candidates.firstOrNull() ?: return
        // The platform's own name usually already carries the marker (it does
        // on the Pixel Fold: "Hinge Angle Sensor (wake-up)"), so only add it
        // when it's missing — otherwise the committed service_start detail
        // reads "hinge=Hinge Angle Sensor (wake-up) (wake-up)".
        val marker = " (wake-up)"
        selectedSensorName = when {
            !sensor.isWakeUpSensor -> sensor.name
            sensor.name.endsWith(marker, ignoreCase = true) -> sensor.name
            else -> sensor.name + marker
        }
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() = sensorManager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        eventsSeen++
        val deg = event.values.firstOrNull() ?: return
        val prev = latestDeg
        latestDeg = deg
        if (prev == null || kotlin.math.abs(deg - prev) >= 1f) onTransition(deg)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
