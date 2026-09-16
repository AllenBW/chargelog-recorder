// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

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
}
