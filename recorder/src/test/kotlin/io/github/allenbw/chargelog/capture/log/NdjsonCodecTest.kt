// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NdjsonCodecTest {

    private val sample = RawLine.Sample(
        t = 1_756_000_000_000, e = 123_456,
        currentRaw = -1_412_000, chargeCounterRaw = 3_100_000,
        voltageRaw = 4123, voltageAgeMs = 250,
        tempDeciC = 312, level = 47, scale = 100,
        status = 2, plugged = 1,
        maxChargingCurrentRaw = 3_000_000, maxChargingVoltageRaw = 9_000_000,
        thermalStatus = 0, screenOn = false, hingeDeg = 180.0f,
    )

    @Test
    fun `sample round-trips`() {
        val line = NdjsonCodec.encode(sample)
        assertFalse("must be single-line", line.contains('\n'))
        assertEquals(sample, NdjsonCodec.decode(line))
    }

    @Test
    fun `nulls are omitted from the wire and survive round-trip`() {
        val sparse = RawLine.Sample(t = 1, e = 2, level = 50)
        val line = NdjsonCodec.encode(sparse)
        assertFalse(line.contains("currentRaw"))
        assertEquals(sparse, NdjsonCodec.decode(line))
    }

    @Test
    fun `header and event round-trip`() {
        val h = RawLine.Header(
            schema = 1, samplerProfileId = "p1-tick1000-wl1",
            deviceModel = "Pixel 11 Fold", osRelease = "16", appVersion = "0.1.0",
            tickMs = 1000, sessionStartWallClockMs = 1_756_000_000_000,
        )
        val ev = RawLine.Event(t = 5, e = 6, kind = EventKinds.GAP, detail = "elapsedGapMs=9000")
        assertEquals(h, NdjsonCodec.decode(NdjsonCodec.encode(h)))
        assertEquals(ev, NdjsonCodec.decode(NdjsonCodec.encode(ev)))
    }

    @Test
    fun `detail strings with quotes and newlines survive`() {
        val ev = RawLine.Event(t = 1, e = 2, kind = EventKinds.SERVICE_STOP, detail = "a \"quoted\"\nline")
        val line = NdjsonCodec.encode(ev)
        assertFalse(line.contains('\n'))
        assertEquals(ev, NdjsonCodec.decode(line))
    }

    @Test
    fun `unknown fields are ignored on decode`() {
        val decoded = NdjsonCodec.decode("""{"y":"e","t":1,"e":2,"kind":"gap","futureField":true}""")
        assertEquals(RawLine.Event(t = 1, e = 2, kind = "gap"), decoded)
    }

    @Test
    fun `legacy seven-field header decodes with null device fields`() {
        val legacy = """{"y":"h","schema":1,"samplerProfileId":"p1","deviceModel":"Pixel 11 Fold","osRelease":"16","appVersion":"0.1.0","tickMs":1000,"sessionStartWallClockMs":42}"""
        val h = NdjsonCodec.decode(legacy) as RawLine.Header
        assertEquals(1, h.schema)
        assertEquals(null, h.deviceKind)
        assertEquals(null, h.deviceId)
        assertEquals(null, h.gaugeProfileId)
        assertEquals(null, h.capabilities)
        assertEquals(null, h.socModel)
        assertEquals(null, h.totalMemBytes)
        assertEquals(null, h.designCapacityMah)
        assertEquals(null, h.cycleCount)
    }

    @Test
    fun `cycleCount rounds-trips in the header and is omitted when null`() {
        val h = RawLine.Header(
            schema = 2, samplerProfileId = "p1", deviceModel = "Pixel 11 Pro Fold", osRelease = "17",
            appVersion = "0.3.2", tickMs = 1000, sessionStartWallClockMs = 7, cycleCount = 7,
        )
        val line = NdjsonCodec.encode(h)
        assertEquals(h, NdjsonCodec.decode(line))
        assertTrue(line.contains(""""cycleCount":7"""))
        assertFalse(NdjsonCodec.encode(h.copy(cycleCount = null)).contains("cycleCount"))
        assertEquals(2, (NdjsonCodec.decode(NdjsonCodec.encode(h.copy(cycleCount = null))) as RawLine.Header).schema)
    }

    @Test
    fun `the added hardware-class fields round-trip without bumping the schema`() {
        val h = RawLine.Header(
            schema = 2, samplerProfileId = "p1", deviceModel = "Pixel 11 Pro Fold", osRelease = "17",
            appVersion = "0.2.0", tickMs = 1000, sessionStartWallClockMs = 7,
            deviceKind = DeviceKinds.PHONE, socModel = "Tensor G5", totalMemBytes = 12_450_000_000L,
            designCapacityMah = 4_890,
        )
        val line = NdjsonCodec.encode(h)
        assertEquals(h, NdjsonCodec.decode(line))
        assertTrue(line.contains(""""socModel":"Tensor G5""""))
        assertTrue(line.contains(""""totalMemBytes":12450000000"""))
        assertTrue(line.contains(""""designCapacityMah":4890"""))
        val older = NdjsonCodec.decode(
            NdjsonCodec.encode(h.copy(socModel = null, totalMemBytes = null, designCapacityMah = null)),
        ) as RawLine.Header
        assertEquals(2, older.schema)
        assertEquals(null, older.socModel)
        assertEquals(null, older.totalMemBytes)
        assertEquals(null, older.designCapacityMah)
    }

    @Test
    fun `header with device fields round-trips and omits nothing it was given`() {
        val h = RawLine.Header(
            schema = 2, samplerProfileId = "p1", deviceModel = "Pixel Watch 3", osRelease = "16",
            appVersion = "0.2.0", tickMs = 1000, sessionStartWallClockMs = 7,
            deviceKind = DeviceKinds.WATCH, deviceId = "0123456789abcdef0123456789abcdef",
            gaugeProfileId = "gauge-qbg",
            capabilities = Capabilities(reportsCurrent = true, reportsChargeCounter = true,
                counterKind = CounterKinds.COULOMB, hasHinge = false, hasThermal = true),
        )
        val line = NdjsonCodec.encode(h)
        assertFalse(line.contains('\n'))
        assertEquals(h, NdjsonCodec.decode(line))
        assertTrue(line.contains(""""deviceKind":"WATCH""""))
    }

    @Test
    fun `null device fields are omitted from the wire`() {
        val h = RawLine.Header(
            schema = 2, samplerProfileId = "p1", deviceModel = "x", osRelease = "16",
            appVersion = "0.2.0", tickMs = 1000, sessionStartWallClockMs = 7,
        )
        val line = NdjsonCodec.encode(h)
        assertFalse(line.contains("deviceKind"))
        assertFalse(line.contains("capabilities"))
    }

    @Test
    fun `chargingStatus rides the sample and is omitted when null`() {
        val withStatus = sample.copy(chargingStatus = 5)
        val line = NdjsonCodec.encode(withStatus)
        assertTrue(line.contains("\"chargingStatus\":5"))
        assertEquals(withStatus, NdjsonCodec.decode(line))
        assertFalse(NdjsonCodec.encode(sample).contains("chargingStatus"))
    }

    @Test
    fun `a sample line written before chargingStatus existed decodes with it null`() {
        val decoded = NdjsonCodec.decode("""{"y":"s","t":1,"e":2,"level":50,"thermalStatus":0}""") as RawLine.Sample
        assertNull(decoded.chargingStatus)
        assertEquals(0, decoded.thermalStatus)
    }

    @Test
    fun `the capability block carries reportsChargingStatus and chargingPositive, both omitted when null`() {
        val caps = Capabilities(reportsCurrent = true, reportsChargingStatus = true, chargingPositive = true)
        val h = RawLine.Header(
            schema = 2, samplerProfileId = "p1", deviceModel = "m", osRelease = "17", appVersion = "0.2.0",
            tickMs = 1000, sessionStartWallClockMs = 1, capabilities = caps,
        )
        val line = NdjsonCodec.encode(h)
        assertTrue(line.contains("\"reportsChargingStatus\":true"))
        assertTrue(line.contains("\"chargingPositive\":true"))
        assertEquals(h, NdjsonCodec.decode(line))
        val bare = NdjsonCodec.encode(h.copy(capabilities = Capabilities(reportsCurrent = true)))
        assertFalse(bare.contains("reportsChargingStatus"))
        assertFalse(bare.contains("chargingPositive"))
    }
}
