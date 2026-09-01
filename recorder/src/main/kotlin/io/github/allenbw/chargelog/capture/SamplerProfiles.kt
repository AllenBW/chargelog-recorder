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
            ),
            currentScale = gauge.currentScale,
        )
    }
}
