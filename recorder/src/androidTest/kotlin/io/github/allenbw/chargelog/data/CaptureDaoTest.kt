// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureDaoTest {

    private lateinit var db: ChargeLogDb

    @Before fun setUp() { db = ChargeLogDb.openInMemory(ApplicationProvider.getApplicationContext()) }
    @After fun tearDown() { db.close() }

    private fun session(id: Long) = SessionEntity(
        id = id, startedAtMs = id, endedAtMs = id + 60_000, endReason = "UNPLUGGED",
        samplerProfileId = "p1", schemaVersion = 1,
        startLevel = 20, endLevel = 80, startChargeCounterRaw = 1_000_000, endChargeCounterRaw = 3_000_000,
        sourceFile = "session-$id.ndjson",
    )

    @Test
    fun sessionRoundTripsAndOrdersDescending() = runBlocking {
        db.dao().upsertSession(session(100))
        db.dao().upsertSession(session(200))
        val all = db.dao().sessions().first()
        assertEquals(listOf(200L, 100L), all.map { it.id })
        assertEquals("UNPLUGGED", all[0].endReason)
    }

    @Test
    fun upsertIsIdempotentByNaturalKeys() = runBlocking {
        val s = SampleEntity(
            sessionId = 100, wallClockMs = 1, elapsedRealtimeMs = 10,
            currentRaw = -500_000, chargeCounterRaw = 2_000_000, voltageRaw = 4100, voltageAgeMs = 50,
            tempDeciC = 300, level = 50, status = 2, plugged = 1,
            maxChargingCurrentRaw = null, maxChargingVoltageRaw = null,
            thermalStatus = 0, screenOn = false, hingeDeg = null,
        )
        db.dao().upsertSession(session(100))
        db.dao().upsertSamples(listOf(s))
        db.dao().upsertSamples(listOf(s.copy(level = 51))) // same keys → replaces
        val stored = db.dao().samples(100)
        assertEquals(1, stored.size)
        assertEquals(51, stored[0].level)
    }

    @Test
    fun clearAllEmptiesBothTables() = runBlocking {
        val s = SampleEntity(
            sessionId = 100, wallClockMs = 1, elapsedRealtimeMs = 10,
            currentRaw = -500_000, chargeCounterRaw = 2_000_000, voltageRaw = 4100, voltageAgeMs = 50,
            tempDeciC = 300, level = 50, status = 2, plugged = 1,
            maxChargingCurrentRaw = null, maxChargingVoltageRaw = null,
            thermalStatus = 0, screenOn = false, hingeDeg = null,
        )
        db.dao().upsertSession(session(100))
        db.dao().upsertSamples(listOf(s))
        db.dao().clearAll()
        assertEquals(0, db.dao().sessions().first().size)
        assertEquals(0, db.dao().samples(100).size)
    }
}
