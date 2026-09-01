// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Minimal in-memory [CaptureDao] backed by maps, recording upsert/delete calls. */
internal class FakeCaptureDao : CaptureDao {
    val sessions = linkedMapOf<Long, SessionEntity>()
    val samplesBySession = mutableMapOf<Long, MutableList<SampleEntity>>()
    var upsertSessionCalls = 0
        private set
    var upsertSamplesCalls = 0
        private set
    val deleteSessionRowCalls = mutableListOf<Long>()
    val deleteSamplesForCalls = mutableListOf<Long>()

    override suspend fun upsertSession(session: SessionEntity) {
        upsertSessionCalls++
        sessions[session.id] = session
    }

    override suspend fun upsertSamples(samples: List<SampleEntity>) {
        upsertSamplesCalls++
        samples.groupBy { it.sessionId }.forEach { (sid, list) ->
            samplesBySession.getOrPut(sid) { mutableListOf() }.addAll(list)
        }
    }

    override fun sessions(): Flow<List<SessionEntity>> = flowOf(sessions.values.toList())

    override suspend fun samples(sessionId: Long): List<SampleEntity> =
        samplesBySession[sessionId].orEmpty()

    override suspend fun clearSessions() { sessions.clear() }
    override suspend fun clearSamples() { samplesBySession.clear() }

    override suspend fun fileStates(): List<FileState> =
        sessions.values.map { FileState(it.sourceFile, it.endReason, it.deviceId) }

    override suspend fun ownCompletedSessionCount(): Int =
        sessions.values.count { it.endedAtMs != null && it.deviceId == null }

    override suspend fun session(id: Long): SessionEntity? = sessions[id]

    override suspend fun deleteSessionRow(id: Long) {
        deleteSessionRowCalls += id
        sessions.remove(id)
    }

    override suspend fun deleteSamplesFor(id: Long) {
        deleteSamplesForCalls += id
        samplesBySession.remove(id)
    }
}
