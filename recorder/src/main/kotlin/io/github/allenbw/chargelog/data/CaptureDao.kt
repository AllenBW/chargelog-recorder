// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

data class FileState(val sourceFile: String, val endReason: String?, val deviceId: String?)

@Dao
interface CaptureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSamples(samples: List<SampleEntity>)

    @Query("SELECT * FROM sessions ORDER BY startedAtMs DESC")
    fun sessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM samples WHERE sessionId = :sessionId ORDER BY elapsedRealtimeMs")
    suspend fun samples(sessionId: Long): List<SampleEntity>

    @Query("DELETE FROM sessions") suspend fun clearSessions()
    @Query("DELETE FROM samples") suspend fun clearSamples()

    @Query("SELECT sourceFile, endReason, deviceId FROM sessions")
    suspend fun fileStates(): List<FileState>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun session(id: Long): SessionEntity?

    @Transaction
    suspend fun clearAll() { clearSamples(); clearSessions() }

    @Query("DELETE FROM sessions WHERE id = :id") suspend fun deleteSessionRow(id: Long)
    @Query("DELETE FROM samples WHERE sessionId = :id") suspend fun deleteSamplesFor(id: Long)

    @Transaction
    suspend fun deleteSession(id: Long) { deleteSamplesFor(id); deleteSessionRow(id) }
}
