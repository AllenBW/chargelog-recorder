// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeCaptureDaoTest {

    private fun session(
        id: Long,
        endedAtMs: Long? = id + 60_000,
        endReason: String? = EndReasonsForReplay.CLEAN,
        deviceId: String? = null,
    ) = SessionEntity(
        id = id, startedAtMs = id, endedAtMs = endedAtMs, endReason = endReason,
        samplerProfileId = "p1", schemaVersion = 2,
        startLevel = 20, endLevel = 80, startChargeCounterRaw = null, endChargeCounterRaw = null,
        sourceFile = "session-$id.ndjson", deviceId = deviceId,
    )

    @Test
    fun `own completed count skips truncated rows`() = runBlocking {
        val dao = FakeCaptureDao()
        dao.upsertSession(session(1))
        dao.upsertSession(session(2, deviceId = "loc"))
        dao.upsertSession(session(3, deviceId = "loc", endReason = EndReasonsForReplay.TRUNCATED))
        dao.upsertSession(session(4, deviceId = "loc", endedAtMs = null, endReason = null))
        dao.upsertSession(session(5, deviceId = "watch-1"))

        assertEquals(2, dao.ownCompletedSessionCount("loc"))
    }

    @Test
    fun `a session the service stopped still counts as completed`() = runBlocking {
        val dao = FakeCaptureDao()
        dao.upsertSession(session(1, deviceId = "loc", endReason = EndReasonsForReplay.SERVICE_KILLED))
        assertEquals(1, dao.ownCompletedSessionCount("loc"))
    }

    @Test
    fun `a foreign device's completed sessions are never this device's own`() = runBlocking {
        val dao = FakeCaptureDao()
        dao.upsertSession(session(1, deviceId = "watch-1"))
        dao.upsertSession(session(2, deviceId = "watch-2"))
        assertEquals(0, dao.ownCompletedSessionCount("loc"))
    }

    private fun sample(sessionId: Long, e: Long, level: Int) = SampleEntity(
        sessionId = sessionId, wallClockMs = e, elapsedRealtimeMs = e, currentRaw = null, chargeCounterRaw = null,
        voltageRaw = null, voltageAgeMs = null, tempDeciC = null, level = level, status = null, plugged = null,
        maxChargingCurrentRaw = null, maxChargingVoltageRaw = null, thermalStatus = null, screenOn = null, hingeDeg = null,
    )

    @Test
    fun `samples are replaced by session and elapsed time, as Room's key does`() = runBlocking {
        val dao = FakeCaptureDao()
        dao.upsertSamples(listOf(sample(1, 10, 50), sample(1, 20, 51)))
        dao.upsertSamples(listOf(sample(1, 10, 60), sample(1, 20, 61), sample(1, 30, 62)))
        assertEquals(listOf(60, 61, 62), dao.samples(1).map { it.level })
    }

    @Test
    fun `samples come back in elapsed order and sessions newest first, as the SQL orders them`() = runBlocking {
        val dao = FakeCaptureDao()
        dao.upsertSamples(listOf(sample(1, 30, 3), sample(1, 10, 1), sample(1, 20, 2)))
        dao.upsertSession(session(100))
        dao.upsertSession(session(300))
        dao.upsertSession(session(200))
        assertEquals(listOf(10L, 20L, 30L), dao.samples(1).map { it.elapsedRealtimeMs })
        assertEquals(listOf(300L, 200L, 100L), dao.sessions().first().map { it.id })
    }
}
