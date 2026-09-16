// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureDaoOwnCountTest {

    private lateinit var db: ChargeLogDb

    @Before fun setUp() { db = ChargeLogDb.openInMemory(ApplicationProvider.getApplicationContext()) }
    @After fun tearDown() { db.close() }

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
    fun ownCompletedCountSkipsTruncatedAndOpenAndForeignRows() = runBlocking {
        val dao = db.dao()
        dao.upsertSession(session(1))
        dao.upsertSession(session(2, deviceId = "loc"))
        dao.upsertSession(session(3, deviceId = "loc", endReason = EndReasonsForReplay.TRUNCATED))
        dao.upsertSession(session(4, deviceId = "loc", endedAtMs = null, endReason = null))
        dao.upsertSession(session(5, deviceId = "watch-1"))

        assertEquals(2, dao.ownCompletedSessionCount("loc"))
    }

    @Test
    fun aNullEndReasonOnAnEndedRowStillCounts() = runBlocking {
        val dao = db.dao()
        dao.upsertSession(session(1, deviceId = "loc", endReason = null))
        assertEquals(1, dao.ownCompletedSessionCount("loc"))
    }

    @Test
    fun aServiceKilledRowCountsAsCompleted() = runBlocking {
        val dao = db.dao()
        dao.upsertSession(session(1, deviceId = "loc", endReason = EndReasonsForReplay.SERVICE_KILLED))
        assertEquals(1, dao.ownCompletedSessionCount("loc"))
    }
}
