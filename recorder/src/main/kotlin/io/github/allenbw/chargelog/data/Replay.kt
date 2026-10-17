// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import io.github.allenbw.chargelog.capture.log.DeviceKinds
import io.github.allenbw.chargelog.capture.log.EventKinds
import io.github.allenbw.chargelog.capture.log.EventLog
import io.github.allenbw.chargelog.capture.log.NdjsonCodec
import io.github.allenbw.chargelog.capture.log.RawLine
import io.github.allenbw.chargelog.capture.log.RawLogWriter
import io.github.allenbw.chargelog.measure.GaugeProfiles
import io.github.allenbw.chargelog.measure.PhoneScaleEvidence
import io.github.allenbw.chargelog.measure.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

    internal val mutex = Mutex()

    private const val MAX_LOGGED_ID = 64

    data class Parsed(val session: SessionEntity, val samples: List<SampleEntity>)

    /**
     * One session file as its rows, or null without a header. The row's `gaugeProfileId` is what
     * was measured, not what was assumed: a `gauge_scale` event's id when the file carries one, else
     * `gauge-phone-ma` for a `gauge-phone` file whose own charge proves an mA gauge
     * ([PhoneScaleEvidence]) — a log recorded before the phone probe existed — else the header's.
     */
    fun parse(file: File): Parsed? {
        var header: RawLine.Header? = null
        var endEvent: RawLine.Event? = null
        var stopEvent: RawLine.Event? = null
        var gaugeEvent: RawLine.Event? = null
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
                    EventKinds.GAUGE_SCALE -> gaugeEvent = line
                    else -> Unit
                }
                null -> Unit
            }
        }
        val h = header ?: return null

        val first = samples.firstOrNull()
        val last = samples.lastOrNull()
        val rows = samples.map { it.toEntity(h.sessionStartWallClockMs) }
        val session = SessionEntity(
            id = h.sessionStartWallClockMs,
            startedAtMs = h.sessionStartWallClockMs,
            endedAtMs = endEvent?.t ?: stopEvent?.t ?: last?.t,
            endReason = when {
                endEvent != null -> EndReasonsForReplay.CLEAN
                stopEvent != null -> EndReasonsForReplay.SERVICE_KILLED
                else -> EndReasonsForReplay.TRUNCATED
            },
            samplerProfileId = h.samplerProfileId,
            schemaVersion = h.schema,
            startLevel = Units.levelPct(first?.level, first?.scale),
            endLevel = Units.levelPct(last?.level, last?.scale),
            startChargeCounterRaw = first?.chargeCounterRaw,
            endChargeCounterRaw = last?.chargeCounterRaw,
            sourceFile = file.name,
            deviceKind = h.deviceKind ?: DeviceKinds.PHONE,
            deviceId = h.deviceId,
            deviceModel = h.deviceModel,
            gaugeProfileId = measuredGaugeId(gaugeEvent, h.gaugeProfileId, rows),
            reportsCurrent = h.capabilities?.reportsCurrent,
            counterKind = h.capabilities?.counterKind,
            hasHinge = h.capabilities?.hasHinge,
            osRelease = h.osRelease,
            appVersion = h.appVersion,
            socModel = h.socModel,
            totalMemBytes = h.totalMemBytes,
            designCapacityMah = h.designCapacityMah,
            chargingPositive = h.capabilities?.chargingPositive,
        )
        return Parsed(session, rows)
    }

    private fun measuredGaugeId(gaugeEvent: RawLine.Event?, headerId: String?, rows: List<SampleEntity>): String? {
        val probed = gaugeEvent?.detail
            ?.substringAfter(EventKinds.GAUGE_SCALE_DETAIL_KEY, "")
            ?.takeIf { GaugeProfiles.byId(it) != null }
        if (probed != null) return probed
        val repaired = headerId == GaugeProfiles.PHONE.id && PhoneScaleEvidence.speaksMilliAmps(rows)
        return if (repaired) GaugeProfiles.PHONE_MA.id else headerId
    }

    private suspend fun admit(p: Parsed, dao: CaptureDao, dirs: LogDirs): Boolean {
        if (invalidId(p, dirs)) return false
        val existing = dao.session(p.session.id) ?: return true
        if (existing.deviceId == p.session.deviceId) return true
        EventLog(dirs.phone).append(RawLine.Event(
            t = System.currentTimeMillis(), e = 0, kind = EventKinds.INGEST_CONFLICT,
            detail = "id=${p.session.id},deviceId=${p.session.deviceId},existingDeviceId=${existing.deviceId}"))
        return false
    }

    private fun invalidId(p: Parsed, dirs: LogDirs): Boolean {
        val deviceId = p.session.deviceId ?: return false
        if (LogDirs.VALID_DEVICE_ID.matches(deviceId)) return false
        EventLog(dirs.phone).append(RawLine.Event(
            t = System.currentTimeMillis(), e = 0, kind = EventKinds.INGEST_INVALID_ID,
            detail = "id=${p.session.id},deviceId=${deviceId.take(MAX_LOGGED_ID)}"))
        return true
    }

    /**
     * Rebuilds the whole projection over every [LogDirs] directory (a dev-only manual action).
     * Directory listing plus a full parse of every session file, so it moves itself to
     * [Dispatchers.IO] rather than trusting each caller to — a host's UI typically calls it from
     * the main thread. Serialized against [reconcile] and
     * [deleteSessionCompletely] via [mutex] — without it, a delete racing this rebuild could
     * resurrect rows for a file the delete just removed, since this doesn't otherwise know the
     * delete happened.
     *
     * When two devices' sessions share an id (their start in ms), the one that was projected
     * before the rebuild is projected again, so a rebuild never swaps which of them is shown.
     * With neither projected, [LogDirs.all]'s order decides, as it does in [reconcile]. The other
     * stays on disk unprojected; [collisionHidden] finds it.
     */
    suspend fun projectAll(dirs: LogDirs, dao: CaptureDao): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val incumbents = dao.fileStates()
            dao.clearAll()
            var count = 0
            for (f in incumbentsFirst(dirs, incumbents)) {
                val p = try { parse(f) } catch (_: IOException) { null } ?: continue
                if (!admit(p, dao, dirs)) continue
                dao.upsertSession(p.session)
                dao.upsertSamples(p.samples)
                count++
            }
            count
        }
    }

    private fun incumbentsFirst(dirs: LogDirs, incumbents: List<FileState>): List<File> =
        dirs.all()
            .flatMap { dir ->
                val held = dirs.projected(dir, incumbents)
                dirs.projectable(dir).map { it to (it.name in held) }
            }
            .sortedBy { (_, held) -> !held }
            .map { (f, _) -> f }

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
                val known = states
                    .filter { it.deviceId == dirDeviceId || (dirDeviceId == null && it.deviceId == dirs.localDeviceId) }
                    .associateBy { it.sourceFile }
                for (f in dirs.projectable(dir)) {
                    val k = known[f.name]
                    if (k != null && k.endReason != EndReasonsForReplay.TRUNCATED) continue
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
     * The id of every session that has a log on disk in any [LogDirs] root — this device's own,
     * `synced/<id>/` and `imported/<id>/` — read from the file names (`session-<id>.ndjson`) and
     * whether or not any row projects it. A file is what a session IS: [deleteSessionCompletely]
     * removes the files before the rows, and a projection can lag them (a schema bump drops every
     * row; a file that cannot be read is skipped). So "no row" never means "gone", and a host that
     * keeps its own state per session decides what is gone from this. Serialized with the other
     * writers through the same lock, so it never sees an import or a delete half done.
     */
    suspend fun sessionIdsOnDisk(dirs: LogDirs): Set<Long> = withContext(Dispatchers.IO) {
        mutex.withLock {
            dirs.all().flatMapTo(HashSet()) { dir ->
                RawLogWriter.sessionFiles(dir).mapNotNull { f ->
                    f.name.removePrefix(RawLogWriter.SESSION_PREFIX)
                        .removeSuffix(RawLogWriter.SESSION_SUFFIX)
                        .toLongOrNull()
                }
            }
        }
    }

    /**
     * Deletes a session completely: every copy of its raw file first (this device's own, or a
     * foreign session's imported and synced copies alike), then rows — a DB-only delete would
     * resurrect at the next launch, since [reconcile] re-projects any session file it finds on
     * disk, and so would a copy left in the other root. A foreign session's synced copy goes
     * before its imported one, and the first copy that cannot be deleted stops the delete, so a
     * failure never leaves a synced copy standing where its import was: the session stays imported.
     * Returns false, leaving the rows untouched, if any copy remains on disk; a file that's
     * already gone counts as deleted.
     */
    suspend fun deleteSessionCompletely(dirs: LogDirs, dao: CaptureDao, session: SessionEntity): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withContext(NonCancellable) {
                    val gone = dirs.copiesOf(session).all { !it.exists() || it.delete() }
                    if (gone) dao.deleteSession(session.id)
                    gone
                }
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
        level = Units.levelPct(level, scale),
        status = status,
        plugged = plugged,
        maxChargingCurrentRaw = maxChargingCurrentRaw,
        maxChargingVoltageRaw = maxChargingVoltageRaw,
        thermalStatus = thermalStatus,
        screenOn = screenOn,
        hingeDeg = hingeDeg,
        chargingStatus = chargingStatus,
    )
}
