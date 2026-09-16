// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.capture.log.Capabilities
import io.github.allenbw.chargelog.capture.log.DeviceKinds
import io.github.allenbw.chargelog.measure.GaugeProfile

/**
 * Builds the header profile the recording service writes, from what the host and the platform
 * report. Pure, so the phone's profile can be pinned exactly: the phone id keeps its historical
 * `-flush1` suffix (it names an instrument configuration and appears in every existing log); a
 * watch writes a `w1-…` id with no suffix.
 */
object SamplerProfiles {
    fun forHost(
        deviceKind: String,
        gauge: GaugeProfile,
        tickMs: Long,
        deviceModel: String,
        osRelease: String,
        appVersion: String,
        deviceId: String,
        reportsCurrent: Boolean,
        reportsCounter: Boolean,
        hasHinge: Boolean,
        hasThermal: Boolean,
        socModel: String? = null,
        totalMemBytes: Long? = null,
        designCapacityMah: Int? = null,
        /** Whether the sticky battery intent carried `EXTRA_CHARGING_STATUS` when the service
         *  probed it; null when the host did not probe (no sticky yet, or an older host). */
        reportsChargingStatus: Boolean? = null,
    ): SamplerProfile {
        val watch = deviceKind == DeviceKinds.WATCH
        return SamplerProfile(
            id = if (watch) "w1-tick${tickMs}-wlsession" else "p1-tick${tickMs}-wlsession-flush1",
            tickMs = tickMs,
            deviceModel = deviceModel,
            osRelease = osRelease,
            appVersion = appVersion,
            deviceKind = deviceKind,
            deviceId = deviceId,
            gaugeProfileId = gauge.id,
            capabilities = Capabilities(
                reportsCurrent = reportsCurrent,
                reportsChargeCounter = reportsCounter,
                counterKind = if (reportsCounter) gauge.counterKind else null,
                hasHinge = hasHinge,
                hasThermal = hasThermal,
                reportsChargingStatus = reportsChargingStatus,
                chargingPositive = gauge.chargingPositive,
            ),
            currentScale = gauge.currentScale,
            socModel = socModel,
            totalMemBytes = totalMemBytes,
            designCapacityMah = designCapacityMah,
        )
    }

    /**
     * [profile] recording under [gauge] instead of the one it was built with — the scale probe's
     * one writer. Everything the gauge decides moves together: the header's id, the capabilities'
     * counter kind and charging sign, and the scale the settle rule reads. Split out here so the
     * set stays in one place; a swap that updated the id and left the scale behind is exactly the
     * 1000× error the probe exists to remove.
     *
     * `counterKind` stays null when the header declared no counter at all — the gauge's answer is
     * about a counter this device does not report.
     */
    fun withGauge(profile: SamplerProfile, gauge: GaugeProfile): SamplerProfile = profile.copy(
        gaugeProfileId = gauge.id,
        currentScale = gauge.currentScale,
        capabilities = profile.capabilities?.copy(
            counterKind = if (profile.capabilities.reportsChargeCounter == true) gauge.counterKind else null,
            chargingPositive = gauge.chargingPositive,
        ),
    )
}
