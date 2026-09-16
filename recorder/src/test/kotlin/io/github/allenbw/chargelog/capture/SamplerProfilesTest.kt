// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.capture.log.Capabilities
import io.github.allenbw.chargelog.capture.log.CounterKinds
import io.github.allenbw.chargelog.capture.log.DeviceKinds
import io.github.allenbw.chargelog.measure.CurrentScale
import io.github.allenbw.chargelog.measure.GaugeProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SamplerProfilesTest {

    private val id = "0123456789abcdef0123456789abcdef"

    @Test fun `the phone profile is byte-for-byte what the recorder wrote before`() {
        val p = SamplerProfiles.forHost(
            deviceKind = DeviceKinds.PHONE, gauge = GaugeProfiles.PHONE, tickMs = 1000,
            deviceModel = "Pixel 11 Pro Fold", osRelease = "17", appVersion = "0.1.0", deviceId = id,
            reportsCurrent = true, reportsCounter = true, hasHinge = true, hasThermal = true,
            socModel = "Tensor G5", totalMemBytes = 12_450_000_000L, designCapacityMah = 4_890,
            reportsChargingStatus = true,
        )
        assertEquals(
            SamplerProfile(
                id = "p1-tick1000-wlsession-flush1", tickMs = 1000, deviceModel = "Pixel 11 Pro Fold", osRelease = "17",
                appVersion = "0.1.0", deviceKind = DeviceKinds.PHONE, deviceId = id, gaugeProfileId = "gauge-phone",
                capabilities = Capabilities(
                    reportsCurrent = true, reportsChargeCounter = true, counterKind = CounterKinds.COULOMB,
                    hasHinge = true, hasThermal = true, reportsChargingStatus = true, chargingPositive = true,
                ),
                currentScale = CurrentScale.MICRO_AMP,
                socModel = "Tensor G5", totalMemBytes = 12_450_000_000L, designCapacityMah = 4_890,
            ),
            p,
        )
    }

    @Test fun `a watch host gets the w1 id, WATCH kind and its gauge's id, counter kind and scale`() {
        val p = SamplerProfiles.forHost(
            deviceKind = DeviceKinds.WATCH, gauge = GaugeProfiles.QBG, tickMs = 1000,
            deviceModel = "Pixel Watch 5", osRelease = "17", appVersion = "0.1.0", deviceId = id,
            reportsCurrent = true, reportsCounter = true, hasHinge = false, hasThermal = true,
        )
        assertEquals("w1-tick1000-wlsession", p.id)
        assertEquals(DeviceKinds.WATCH, p.deviceKind)
        assertEquals("gauge-qbg", p.gaugeProfileId)
        assertEquals(CounterKinds.SOC_DERIVED, p.capabilities?.counterKind)
        assertEquals(false, p.capabilities?.hasHinge)
        assertEquals(CurrentScale.MICRO_AMP, p.currentScale)
    }

    @Test fun `a Samsung gauge carries the mA scale into the profile`() {
        val p = SamplerProfiles.forHost(DeviceKinds.WATCH, GaugeProfiles.SEC, 1000, "SM-R960", "15", "0.1.0", id, true, true, false, true)
        assertEquals(CurrentScale.MILLI_AMP, p.currentScale)
        assertEquals("gauge-sec", p.gaugeProfileId)
    }

    @Test fun `no counter means no counter kind`() {
        val p = SamplerProfiles.forHost(DeviceKinds.WATCH, GaugeProfiles.UNKNOWN, 1000, "X", "14", "0.1.0", id, false, false, false, true)
        assertEquals(null, p.capabilities?.counterKind)
        assertEquals(false, p.capabilities?.reportsCurrent)
    }

    @Test fun `the header declares the gauge's sign convention, and nothing for an unmeasured gauge`() {
        val qbg = SamplerProfiles.forHost(DeviceKinds.WATCH, GaugeProfiles.QBG, 1000, "Pixel Watch 5", "17", "0.1.0", id, true, true, false, true)
        assertEquals(true, qbg.capabilities?.chargingPositive)
        val sec = SamplerProfiles.forHost(DeviceKinds.WATCH, GaugeProfiles.SEC, 1000, "SM-R960", "15", "0.1.0", id, true, true, false, true)
        assertEquals(null, sec.capabilities?.chargingPositive)
    }

    @Test fun `reportsChargingStatus is declared as the host probed it, and stays null when it did not`() {
        val probedAbsent = SamplerProfiles.forHost(
            deviceKind = DeviceKinds.WATCH, gauge = GaugeProfiles.QBG, tickMs = 1000,
            deviceModel = "Pixel Watch 5", osRelease = "17", appVersion = "0.1.0", deviceId = id,
            reportsCurrent = true, reportsCounter = true, hasHinge = false, hasThermal = true,
            reportsChargingStatus = false,
        )
        assertEquals(false, probedAbsent.capabilities?.reportsChargingStatus)
        val unprobed = SamplerProfiles.forHost(DeviceKinds.WATCH, GaugeProfiles.UNKNOWN, 1000, "X", "14", "0.1.0", id, false, false, false, true)
        assertEquals(null, unprobed.capabilities?.reportsChargingStatus)
    }
    @Test fun `withGauge moves the id, the scale and the capabilities together`() {
        val unknown = SamplerProfiles.forHost(
            deviceKind = DeviceKinds.WATCH, gauge = GaugeProfiles.UNKNOWN, tickMs = 1000,
            deviceModel = "Some Watch", osRelease = "16", appVersion = "0.1.0", deviceId = "d",
            reportsCurrent = true, reportsCounter = true, hasHinge = false, hasThermal = true,
        )
        assertEquals(CurrentScale.MICRO_AMP, unknown.currentScale)

        val refined = SamplerProfiles.withGauge(unknown, GaugeProfiles.SEC)
        assertEquals(GaugeProfiles.SEC.id, refined.gaugeProfileId)
        assertEquals(CurrentScale.MILLI_AMP, refined.currentScale)
        assertEquals(GaugeProfiles.SEC.counterKind, refined.capabilities?.counterKind)
        assertEquals(GaugeProfiles.SEC.chargingPositive, refined.capabilities?.chargingPositive)
        assertEquals(unknown.id, refined.id)
        assertEquals(unknown.deviceId, refined.deviceId)
        assertEquals(true, refined.capabilities?.reportsCurrent)
    }

    @Test fun `withGauge leaves counterKind null on a device that reports no counter`() {
        val noCounter = SamplerProfiles.forHost(
            deviceKind = DeviceKinds.WATCH, gauge = GaugeProfiles.UNKNOWN, tickMs = 1000,
            deviceModel = "Some Watch", osRelease = "16", appVersion = "0.1.0", deviceId = "d",
            reportsCurrent = true, reportsCounter = false, hasHinge = false, hasThermal = true,
        )
        assertNull(SamplerProfiles.withGauge(noCounter, GaugeProfiles.SEC).capabilities?.counterKind)
    }
}
