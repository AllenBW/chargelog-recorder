// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.capture.log.RawLine
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyzerAdaptersTest {

    @Test
    fun `a live sample whose scale is not 100 reaches the analyzer as a percent`() {
        val s = RawLine.Sample(t = 1_042, e = 1_000, level = 500, scale = 1000)
        assertEquals(50, s.toAnalyzerSample().level)
    }

    @Test
    fun `a live sample with a percent scale or none keeps its level`() {
        assertEquals(87, RawLine.Sample(t = 1, e = 1, level = 87, scale = 100).toAnalyzerSample().level)
        assertEquals(87, RawLine.Sample(t = 1, e = 1, level = 87).toAnalyzerSample().level)
    }

    @Test
    fun `a live sample's chargingStatus reaches the analyzer`() {
        assertEquals(5, RawLine.Sample(t = 1, e = 1, chargingStatus = 5).toAnalyzerSample().chargingStatus)
        assertEquals(null, RawLine.Sample(t = 1, e = 1).toAnalyzerSample().chargingStatus)
    }
}
