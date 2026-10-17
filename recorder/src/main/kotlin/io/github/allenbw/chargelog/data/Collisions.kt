// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import io.github.allenbw.chargelog.capture.log.NdjsonCodec
import io.github.allenbw.chargelog.capture.log.RawLine
import java.io.File
import java.io.IOException

/**
 * The session files no row shows because another device's session holds their id. A session's id
 * is its start in ms and one id projects once: [Replay.reconcile] and [Replay.projectAll] keep the
 * session already projected and log `ingest_conflict` for the other, which stays on disk with no
 * row, so nothing reading the projection can show, delete or export it. Reads only the header of
 * files the projection has no row for, under the projection's lock.
 */
suspend fun Replay.collisionHidden(dirs: LogDirs, dao: CaptureDao): List<File> =
    locked { hiddenSessions(dirs, dao).map { it.file } }

/**
 * Deletes what [collisionHidden] finds, re-scanning under the projection's lock so a file that
 * started showing since the caller looked is never touched. A hidden import goes with the synced
 * copy it shadows (the same device's same session, which would otherwise surface hidden in its
 * place). Rows are never touched: the session that is showing keeps its file, row and samples.
 * [keepId] spares one session id, for the session being recorded right now. Returns how many
 * hidden sessions could not be deleted.
 */
suspend fun Replay.deleteCollisionHidden(dirs: LogDirs, dao: CaptureDao, keepId: Long? = null): Int =
    locked {
        hiddenSessions(dirs, dao)
            .filter { it.id != keepId }
            .count { h -> !listOfNotNull(dirs.shadowedBy(h.file), h.file).all { !it.exists() || it.delete() } }
    }

private class HiddenSession(val file: File, val id: Long)

private suspend fun hiddenSessions(dirs: LogDirs, dao: CaptureDao): List<HiddenSession> {
    val states = dao.fileStates()
    return dirs.all().flatMap { dir ->
        val projected = dirs.projected(dir, states)
        dirs.projectable(dir).filterNot { it.name in projected }.mapNotNull { hiddenBy(it, dao) }
    }
}

private suspend fun hiddenBy(file: File, dao: CaptureDao): HiddenSession? {
    val h = headerOf(file) ?: return null
    val deviceId = h.deviceId
    if (deviceId != null && !LogDirs.VALID_DEVICE_ID.matches(deviceId)) return null
    val owner = dao.session(h.sessionStartWallClockMs) ?: return null
    return HiddenSession(file, h.sessionStartWallClockMs).takeIf { owner.deviceId != deviceId }
}

private fun headerOf(file: File): RawLine.Header? = try {
    file.useLines { lines ->
        lines.filter { it.isNotBlank() }.firstNotNullOfOrNull { raw ->
            try { NdjsonCodec.decode(raw) as? RawLine.Header } catch (_: Exception) { null }
        }
    }
} catch (_: IOException) {
    null
}
