// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.measure

import io.github.allenbw.chargelog.capture.log.CounterKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GaugeProfileTest {

    @Test fun `ids are the table's and unique`() {
        assertEquals(
            listOf("gauge-phone", "gauge-qbg", "gauge-sec", "gauge-unknown", "gauge-unknown-ma", "gauge-phone-ma"),
            GaugeProfiles.all.map { it.id },
        )
        assertEquals(GaugeProfiles.all.size, GaugeProfiles.all.map { it.id }.toSet().size)
    }

    @Test fun `counterKind strings match the header vocabulary`() {
        assertEquals(CounterKinds.COULOMB, GaugeProfiles.PHONE.counterKind)
        assertEquals(CounterKinds.COULOMB, GaugeProfiles.PHONE_MA.counterKind)
        assertEquals(CounterKinds.SOC_DERIVED, GaugeProfiles.QBG.counterKind)
        assertEquals(CounterKinds.SOC_DERIVED, GaugeProfiles.SEC.counterKind)
        assertNull(GaugeProfiles.UNKNOWN.counterKind)
    }

    @Test fun `each profile declares the scale its raw current is in`() {
        assertEquals(CurrentScale.MICRO_AMP, GaugeProfiles.PHONE.currentScale)
        assertEquals(CurrentScale.MICRO_AMP, GaugeProfiles.QBG.currentScale)
        assertEquals(CurrentScale.MILLI_AMP, GaugeProfiles.SEC.currentScale)
        assertEquals(CurrentScale.MICRO_AMP, GaugeProfiles.UNKNOWN.currentScale)
        assertEquals(CurrentScale.MILLI_AMP, GaugeProfiles.UNKNOWN_MA.currentScale)
        assertEquals(CurrentScale.MILLI_AMP, GaugeProfiles.PHONE_MA.currentScale)
    }

    @Test fun `forDevice selects by manufacturer on a watch and phone otherwise`() {
        assertSame(GaugeProfiles.QBG, GaugeProfiles.forDevice("Google", "Pixel Watch 5", isWatch = true))
        assertSame(GaugeProfiles.QBG, GaugeProfiles.forDevice("google", "Pixel Watch 2", isWatch = true))
        assertSame(GaugeProfiles.SEC, GaugeProfiles.forDevice("samsung", "SM-R960", isWatch = true))
        assertSame(GaugeProfiles.UNKNOWN, GaugeProfiles.forDevice("Mobvoi", "TicWatch Pro 5", isWatch = true))
        assertTrue("forDevice never selects the detected-mA entry", listOf("Mobvoi", "Google", "samsung", "OnePlus").none {
            GaugeProfiles.forDevice(it, "a watch", isWatch = true) === GaugeProfiles.UNKNOWN_MA
        })
        assertSame(GaugeProfiles.PHONE, GaugeProfiles.forDevice("Google", "Pixel 11 Pro Fold", isWatch = false))
        assertSame(GaugeProfiles.PHONE, GaugeProfiles.forDevice("samsung", "SM-S931B", isWatch = false))
        assertTrue("forDevice never selects the detected-mA phone entry", listOf("samsung", "Google", "OnePlus").none {
            GaugeProfiles.forDevice(it, "a phone", isWatch = false) === GaugeProfiles.PHONE_MA
        })
    }

    @Test fun `byId round-trips and tolerates null and unknown ids`() {
        for (p in GaugeProfiles.all) assertSame(p, GaugeProfiles.byId(p.id))
        assertSame(GaugeProfiles.PHONE_MA, GaugeProfiles.byId("gauge-phone-ma"))
        assertNull(GaugeProfiles.byId(null))
        assertNull(GaugeProfiles.byId("gauge-from-the-future"))
    }

    @Test fun `plausibility promotes UNKNOWN to mA when charging current reads under 5000`() {
        val mA = listOf(350L, 360L, 355L, 340L)
        val ua = listOf(350_000L, 360_000L, 355_000L)
        val asMa = GaugeProfiles.plausibility(mA, GaugeProfiles.UNKNOWN)
        assertEquals(CurrentScale.MILLI_AMP, asMa.currentScale)
        assertSame(GaugeProfiles.UNKNOWN_MA, asMa)
        assertEquals("gauge-unknown-ma", asMa.id)
        assertSame(GaugeProfiles.UNKNOWN, GaugeProfiles.plausibility(ua, GaugeProfiles.UNKNOWN))
    }

    @Test fun `plausibility never changes a measured profile`() {
        assertSame(GaugeProfiles.QBG, GaugeProfiles.plausibility(listOf(350L, 360L), GaugeProfiles.QBG))
        assertSame(GaugeProfiles.PHONE, GaugeProfiles.plausibility(listOf(350L, 360L), GaugeProfiles.PHONE))
        assertSame(GaugeProfiles.SEC, GaugeProfiles.plausibility(listOf(350_000L), GaugeProfiles.SEC))
    }

    @Test fun `plausibility with no charging samples is a no-op`() {
        assertSame(GaugeProfiles.UNKNOWN, GaugeProfiles.plausibility(emptyList(), GaugeProfiles.UNKNOWN))
    }

    @Test fun `only the measured watch gauge declares a sign, and no phone profile guesses one`() {
        assertNull(GaugeProfiles.PHONE.chargingPositive)
        assertNull(GaugeProfiles.PHONE_MA.chargingPositive)
        assertEquals(true, GaugeProfiles.QBG.chargingPositive)
        assertNull(GaugeProfiles.SEC.chargingPositive)
        assertNull(GaugeProfiles.UNKNOWN.chargingPositive)
        assertNull(GaugeProfiles.UNKNOWN_MA.chargingPositive)
    }
}
