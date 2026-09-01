// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import io.github.allenbw.chargelog.capture.log.DeviceKinds
import io.github.allenbw.chargelog.capture.log.EventKinds
import io.github.allenbw.chargelog.capture.log.EventLog
import io.github.allenbw.chargelog.capture.log.NdjsonCodec
import io.github.allenbw.chargelog.capture.log.RawLine
import io.github.allenbw.chargelog.capture.log.RawLogWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

object EndReasonsForReplay {
    /** Session file carries a SESSION_END event — closed by the recorder. */
    const val CLEAN = "CLEAN"
    /** No SESSION_END — process death, reboot, or crash mid-session. */
    const val TRUNCATED = "TRUNCATED"
    /** No SESSION_END, but a SERVICE_STOP terminal — closed by service teardown, not unplug. */
    const val SERVICE_KILLED = "SERVICE_KILLED"
}

/**
 * Rebuilds the Room projection from the raw NDJSON logs. The raw log is the
 * source of truth; this projection is disposable by design.
 */
object Replay {

    // Serializes reconcile(), projectAll(), and deleteSessionCompletely()
    // against each other: all three are suspend/IO-dispatched, and without
    // this a reconcile or rebuild pass racing a delete could re-upsert rows
    // for a file the delete just removed (or vice versa), landing the DB in
    // a state none of the three intended alone.
    private val mutex = Mutex()

    data class Parsed(val session: SessionEntity, val samples: List<SampleEntity>)

    fun parse(file: File): Parsed? {
        var header: RawLine.Header? = null
        var endEvent: RawLine.Event? = null
        var stopEvent: RawLine.Event? = null
        val samples = mutableListOf<RawLine.Sample>()

        file.forEachLine { raw ->
            if (raw.isBlank()) return@forEachLine
            val line = try { NdjsonCodec.decode(raw) } catch (_: Exception) { null }
            when (line) {
                is RawLine.Header -> if (header == null) header = line
                is RawLine.Sample -> samples += line
                is RawLine.Event -> when (line.kind) {
                    EventKinds.SESSION_END -> endEvent = line
                    EventKinds.SERVICE_STOP -> stopEvent = line
                    else -> Unit
                }
                null -> Unit // malformed (crash-truncated) line — skip
            }
        }
        val h = header ?: return null

        val first = samples.firstOrNull()
        val last = samples.lastOrNull()
        val session = SessionEntity(
            id = h.sessionStartWallClockMs,
            startedAtMs = h.sessionStartWallClockMs,
            // Falls back to the last sample's wall clock when there's no
            // terminal event at all (TRUNCATED): a pseudo-final duration
            // beats leaving endedAtMs null, which SessionFilter and the
            // Sessions list otherwise read as "duration unknown" and hide
            // or mis-sort — the endReason stays TRUNCATED regardless, so
            // the badge and reconcile's re-parse-on-TRUNCATED loop are
            // unaffected.
            endedAtMs = endEvent?.t ?: stopEvent?.t ?: last?.t,
            endReason = when {
                endEvent != null -> EndReasonsForReplay.CLEAN
                stopEvent != null -> EndReasonsForReplay.SERVICE_KILLED
                else -> EndReasonsForReplay.TRUNCATED
            },
            samplerProfileId = h.samplerProfileId,
            schemaVersion = h.schema,
            startLevel = first?.level,
            endLevel = last?.level,
            startChargeCounterRaw = first?.chargeCounterRaw,
            endChargeCounterRaw = last?.chargeCounterRaw,
            sourceFile = file.name,
            deviceKind = h.deviceKind ?: DeviceKinds.PHONE,
            deviceId = h.deviceId,
            deviceModel = h.deviceModel,
            gaugeProfileId = h.gaugeProfileId,
            reportsCurrent = h.capabilities?.reportsCurrent,
            counterKind = h.capabilities?.counterKind,
            hasHinge = h.capabilities?.hasHinge,
        )
        return Parsed(session, samples.map { it.toEntity(h.sessionStartWallClockMs) })
    }

    /**
     * True when [p] may be upserted: no row with that id, or a row from the same device. A row
     * from ANOTHER device means two devices started a session in the same millisecond — the
     * failure mode is a silent chimera, so the newcomer is skipped and the conflict logged.
     */
    private suspend fun admit(p: Parsed, dao: CaptureDao, dirs: LogDirs): Boolean {
        val existing = dao.session(p.session.id) ?: return true
        if (existing.deviceId == p.session.deviceId) return true
        EventLog(dirs.phone).append(RawLine.Event(
            t = System.currentTimeMillis(), e = 0, kind = EventKinds.INGEST_CONFLICT,
            detail = "id=${p.session.id},deviceId=${p.session.deviceId},existingDeviceId=${existing.deviceId}"))
        return false
    }

    /**
     * Rebuilds the whole projection over every [LogDirs] directory (a dev-only manual action).
     * Directory listing plus a full parse of every session file, so it moves itself to
     * [Dispatchers.IO] rather than trusting each caller to — a host's UI typically calls it from
     * the main thread. Serialized against [reconcile] and
     * [deleteSessionCompletely] via [mutex] — without it, a delete racing this rebuild could
     * resurrect rows for a file the delete just removed, since this doesn't otherwise know the
     * delete happened.
     */
    suspend fun projectAll(dirs: LogDirs, dao: CaptureDao): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            dao.clearAll()
            var count = 0
            for (dir in dirs.all()) for (f in RawLogWriter.sessionFiles(dir)) {
                // A file can vanish between listFiles() and parse() — e.g. a
                // delete racing this rebuild — which throws
                // FileNotFoundException opening the stream; skip rather than
                // crash the caller's coroutine.
                val p = try { parse(f) } catch (_: IOException) { null } ?: continue
                if (!admit(p, dao, dirs)) continue
                dao.upsertSession(p.session)
                dao.upsertSamples(p.samples)
                count++
            }
            count
        }
    }

    /**
     * Incremental projection over every [LogDirs] directory: parse only session files
     * Room has not seen, plus TRUNCATED ones (their log may have gained a terminal event since).
     * UI-side by design — never called from the capture layer.
     */
    suspend fun reconcile(dirs: LogDirs, dao: CaptureDao): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val states = dao.fileStates()
            var count = 0
            for (dir in dirs.all()) {
                val dirDeviceId = if (dir == dirs.phone) null else dir.name
                // Known files for THIS directory only: the phone dir holds null/local ids, a synced
                // dir holds exactly its own id. Same filename in another dir is a collision, handled by admit().
                val known = states
                    .filter { it.deviceId == dirDeviceId || (dirDeviceId == null && it.deviceId == dirs.localDeviceId) }
                    .associateBy { it.sourceFile }
                for (f in RawLogWriter.sessionFiles(dir)) {
                    val k = known[f.name]
                    if (k != null && k.endReason != EndReasonsForReplay.TRUNCATED) continue
                    // A directory can match the session-*.ndjson name filter too (e.g. a
                    // stray artifact); parse()'s forEachLine would throw opening it as a
                    // stream — skip rather than crash the whole reconcile pass.
                    val p = try { parse(f) } catch (_: IOException) { null } ?: continue
                    if (!admit(p, dao, dirs)) continue
                    dao.upsertSession(p.session)
                    dao.upsertSamples(p.samples)
                    count++
                }
            }
            count
        }
    }

    /**
     * Deletes a session completely from its own [LogDirs] directory: raw file FIRST,
     * then rows — a DB-only delete would resurrect at the next launch, since [reconcile]
     * re-projects any session file it finds on disk. Returns false, leaving the rows untouched,
     * if the file exists but can't be deleted; a file that's already gone is treated as already
     * deleted and the rows are removed as normal.
     */
    suspend fun deleteSessionCompletely(dirs: LogDirs, dao: CaptureDao, session: SessionEntity): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val f = File(dirs.dirFor(session), session.sourceFile)
                if (f.exists() && !f.delete()) return@withLock false
                dao.deleteSession(session.id)
                true
            }
        }

    private fun RawLine.Sample.toEntity(sessionId: Long) = SampleEntity(
        sessionId = sessionId,
        wallClockMs = t,
        elapsedRealtimeMs = e,
        currentRaw = currentRaw,
        chargeCounterRaw = chargeCounterRaw,
        voltageRaw = voltageRaw,
        voltageAgeMs = voltageAgeMs,
        tempDeciC = tempDeciC,
        level = level,
        status = status,
        plugged = plugged,
        maxChargingCurrentRaw = maxChargingCurrentRaw,
        maxChargingVoltageRaw = maxChargingVoltageRaw,
        thermalStatus = thermalStatus,
        screenOn = screenOn,
        hingeDeg = hingeDeg,
    )
}
