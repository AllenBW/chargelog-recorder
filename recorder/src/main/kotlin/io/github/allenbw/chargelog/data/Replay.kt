// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import android.database.SQLException
import android.util.Log
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
     * The most samples [parse] reads from one file: four days at one sample a second, twice the
     * two days at that rate the import's 128 MiB cap is sized for. The recorder samples no faster
     * than once a second, but a log it writes can still reach this: until a charge settles it
     * persists nearly every tick, and a session lasts until the device is unplugged. A charge held
     * anywhere with nothing changing settles after half an hour (the capture package's
     * `SettleDetector`), but one whose level, status or plug keeps changing for about four days
     * crosses the cap, and so can a log an older build wrote while a charge was held short of its
     * target. A file past it is refused ([TooManySamples]) rather than read whole.
     */
    const val MAX_SAMPLES: Int = 4 * 24 * 60 * 60

    /**
     * Thrown by [parse] for a file holding more than [MAX_SAMPLES] samples. It is an
     * [IOException], so a caller that skips a file it cannot read skips this one too, and a caller
     * that has to say why a file was skipped catches this first.
     */
    class TooManySamples(val file: File) : IOException("${file.name} holds more than $MAX_SAMPLES samples")

    /**
     * One session file as its rows, or null without a header. The row's `gaugeProfileId` is what
     * was measured, not what was assumed: a `gauge_scale` event's id when the file carries one, else
     * `gauge-phone-ma` for a `gauge-phone` file whose own charge proves an mA gauge
     * ([PhoneScaleEvidence]) — a log recorded before the phone probe existed — else the header's.
     *
     * Each line becomes its row as it is read; the file's decoded lines are never all held at
     * once, and reading stops at the first sample past [MAX_SAMPLES].
     *
     * @throws TooManySamples for a file holding more than [MAX_SAMPLES] samples.
     * @throws IOException when the file cannot be read.
     */
    fun parse(file: File): Parsed? {
        val reading = Reading()
        val withinCap = file.useLines { lines -> lines.filter { it.isNotBlank() }.all(reading::take) }
        if (!withinCap) throw TooManySamples(file)
        val h = reading.header ?: return null
        val first = reading.first
        val last = reading.last
        val rows = reading.rows
        val session = SessionEntity(
            id = h.sessionStartWallClockMs,
            startedAtMs = h.sessionStartWallClockMs,
            endedAtMs = reading.endEvent?.t ?: reading.stopEvent?.t ?: last?.t,
            endReason = when {
                reading.endEvent != null -> EndReasonsForReplay.CLEAN
                reading.stopEvent != null -> EndReasonsForReplay.SERVICE_KILLED
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
            gaugeProfileId = measuredGaugeId(reading.gaugeEvent, h.gaugeProfileId, rows),
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

    private class Reading {
        var header: RawLine.Header? = null
            private set
        var endEvent: RawLine.Event? = null
            private set
        var stopEvent: RawLine.Event? = null
            private set
        var gaugeEvent: RawLine.Event? = null
            private set
        var first: RawLine.Sample? = null
            private set
        var last: RawLine.Sample? = null
            private set
        val rows = mutableListOf<SampleEntity>()
        private val beforeHeader = mutableListOf<RawLine.Sample>()
        private var count = 0

        fun take(raw: String): Boolean {
            when (val line = try { NdjsonCodec.decode(raw) } catch (_: Exception) { null }) {
                is RawLine.Header -> if (header == null) open(line)
                is RawLine.Sample -> sample(line)
                is RawLine.Event -> event(line)
                null -> Unit
            }
            return count <= MAX_SAMPLES
        }

        private fun open(h: RawLine.Header) {
            header = h
            beforeHeader.mapTo(rows) { it.toEntity(h.sessionStartWallClockMs) }
            beforeHeader.clear()
        }

        private fun sample(s: RawLine.Sample) {
            count++
            if (first == null) first = s
            last = s
            val h = header
            if (h == null) beforeHeader += s else rows += s.toEntity(h.sessionStartWallClockMs)
        }

        private fun event(e: RawLine.Event) {
            when (e.kind) {
                EventKinds.SESSION_END -> endEvent = e
                EventKinds.SERVICE_STOP -> stopEvent = e
                EventKinds.GAUGE_SCALE -> gaugeEvent = e
                else -> Unit
            }
        }
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
        note(dirs, EventKinds.INGEST_CONFLICT,
            "id=${p.session.id},deviceId=${p.session.deviceId},existingDeviceId=${existing.deviceId}")
        return false
    }

    private fun invalidId(p: Parsed, dirs: LogDirs): Boolean {
        val deviceId = p.session.deviceId ?: return false
        if (LogDirs.VALID_DEVICE_ID.matches(deviceId)) return false
        note(dirs, EventKinds.INGEST_INVALID_ID, "id=${p.session.id},deviceId=${deviceId.take(MAX_LOGGED_ID)}")
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
                val p = readForProjection(f, dirs) ?: continue
                if (!admit(p, dao, dirs)) continue
                dao.project(p.session, p.samples)
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
     *
     * Each session is written whole or not at all ([CaptureDao.project]). A database error — the
     * `SQLiteFullException` of a full disk, or any other [SQLException] — ends the pass rather than
     * reaching the caller, who typically runs this from a UI effect where a throw ends the process:
     * it is logged, every session not yet written keeps the row it had (or none), and the next call
     * does the rest. An [Error] still propagates. Returns how many sessions this pass wrote.
     *
     * A file past [MAX_SAMPLES] samples is skipped and keeps the row it had, or none, and the first
     * pass in a process to meet it writes an [EventKinds.INGEST_TOO_MANY_SAMPLES] line to the event
     * log. A log only grows, so for the rest of the process neither pass reads it again unless it
     * gets shorter. The refusal is held in memory only: each new process that meets the file reads
     * it once more, up to the cap, and writes the line again.
     */
    suspend fun reconcile(dirs: LogDirs, dao: CaptureDao): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            var count = 0
            try {
                for (f in pending(dirs, dao.fileStates())) {
                    val p = readForProjection(f, dirs) ?: continue
                    if (!admit(p, dao, dirs)) continue
                    dao.project(p.session, p.samples)
                    count++
                }
            } catch (e: SQLException) {
                Log.w(TAG, "reconcile stopped after $count session(s); the next call does the rest", e)
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

private const val TAG = "Replay"

private val refusedForSize = HashMap<String, Long>()

// TRAP docs/ndjson-format.md — a log refused for its size is noted once a process, and skipped while it only grows
private fun readForProjection(f: File, dirs: LogDirs): Replay.Parsed? {
    val path = f.absolutePath
    val length = f.length()
    if (refusedForSize[path]?.let { length >= it } == true) return null
    return try {
        Replay.parse(f)
    } catch (_: Replay.TooManySamples) {
        refusedForSize[path] = length
        note(dirs, EventKinds.INGEST_TOO_MANY_SAMPLES, "file=${f.parentFile?.name}/${f.name},max=${Replay.MAX_SAMPLES}")
        null
    } catch (_: IOException) {
        null
    }
}

private fun pending(dirs: LogDirs, states: List<FileState>): List<File> = dirs.all().flatMap { dir ->
    val dirDeviceId = if (dir == dirs.phone) null else dir.name
    val known = states
        .filter { it.deviceId == dirDeviceId || (dirDeviceId == null && it.deviceId == dirs.localDeviceId) }
        .associateBy { it.sourceFile }
    dirs.projectable(dir).filter { f ->
        val k = known[f.name]
        k == null || k.endReason == EndReasonsForReplay.TRUNCATED
    }
}

// TRAP docs/traps/event-log-writes-are-best-effort.md — a diagnostic line never ends a projection pass
private fun note(dirs: LogDirs, kind: String, detail: String) {
    try {
        EventLog(dirs.phone).append(RawLine.Event(t = System.currentTimeMillis(), e = 0, kind = kind, detail = detail))
    } catch (e: IOException) {
        Log.w(TAG, "$kind not written to the event log", e)
    }
}
