// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

sealed interface IngestResult {
    data class Imported(val session: SessionEntity) : IngestResult
    data class Rejected(val reason: String) : IngestResult
}

object IngestReasons {
    const val NO_HEADER = "NO_HEADER"
    const val MISSING_DEVICE_IDENTITY = "MISSING_DEVICE_IDENTITY"
    /** The header's `deviceId` failed [LogDirs.VALID_DEVICE_ID] — untrusted input that would
     *  otherwise become a directory name (path traversal). */
    const val INVALID_DEVICE_ID = "INVALID_DEVICE_ID"
    const val LOCAL_DEVICE = "LOCAL_DEVICE"
    const val CONFLICT = "CONFLICT"
    /** The session's file is already on this host, synced or imported, with a readable header (an
     *  empty or headerless stub does not count). Refused only when [LogDirs.importedRoot] is set,
     *  so an import never lands on top of a copy already held. A sync can still write a session
     *  an import holds; the imported copy then shadows it (see [LogDirs.dirFor]). */
    const val DUPLICATE = "DUPLICATE"
    const val IO = "IO"
}

/**
 * The one entry point for a session file that did not come from this device's recorder: the SAF
 * "Import session log" action now, with more transports (e.g. Bluetooth) to follow.
 * Copies [source] to a temp file, validates the header, moves it atomically into
 * `<importedRoot>/<deviceId>/session-<startMs>.ndjson` when [LogDirs.importedRoot] is set (else
 * `synced/<deviceId>/…`, the layout before that root existed), and projects it. With the root
 * set, a session either root already holds is refused as [IngestReasons.DUPLICATE]. The checks,
 * the move and the projection run under the same lock as [Replay.reconcile] and the deletes, so
 * none of them sees the file landed but not yet projected. Rejections leave no file behind.
 */
suspend fun Replay.ingestExternal(source: InputStream, dirs: LogDirs, dao: CaptureDao): IngestResult =
    withContext(Dispatchers.IO) {
        val tmp = File(dirs.incoming.apply { mkdirs() }, "${UUID.randomUUID()}.tmp")
        try {
            tmp.outputStream().use { out -> source.use { it.copyTo(out) } }
            ingestFile(tmp, dirs, dao)
        } catch (_: IOException) {
            IngestResult.Rejected(IngestReasons.IO)
        } finally {
            tmp.delete()
        }
    }

private suspend fun Replay.ingestFile(tmp: File, dirs: LogDirs, dao: CaptureDao): IngestResult {
    val p = try { parse(tmp) } catch (_: IOException) { null } ?: return IngestResult.Rejected(IngestReasons.NO_HEADER)
    val deviceId = p.session.deviceId ?: return IngestResult.Rejected(IngestReasons.MISSING_DEVICE_IDENTITY)
    return mutex.withLock { withContext(NonCancellable) { land(p, deviceId, tmp, dirs, dao) } }
}

private suspend fun Replay.land(
    p: Replay.Parsed,
    deviceId: String,
    tmp: File,
    dirs: LogDirs,
    dao: CaptureDao,
): IngestResult {
    val name = "session-${p.session.id}.ndjson"
    val reason = refusal(dirs, deviceId, name) { dao.session(p.session.id)?.deviceId }
    if (reason != null) return IngestResult.Rejected(reason)
    val dest = File(dirs.landingDir(deviceId).apply { mkdirs() }, name)
    if (!tmp.renameTo(dest)) return IngestResult.Rejected(IngestReasons.IO)
    val session = p.session.copy(sourceFile = dest.name)
    dao.upsertSession(session)
    dao.upsertSamples(p.samples)
    return IngestResult.Imported(session)
}

private fun LogDirs.landingDir(deviceId: String): File =
    if (importedRoot != null) importedDir(deviceId) else syncedDir(deviceId)

private suspend fun Replay.refusal(
    dirs: LogDirs,
    deviceId: String,
    name: String,
    ownerOf: suspend () -> String?,
): String? {
    if (!LogDirs.VALID_DEVICE_ID.matches(deviceId)) return IngestReasons.INVALID_DEVICE_ID
    if (deviceId == dirs.localDeviceId) return IngestReasons.LOCAL_DEVICE
    val owner = ownerOf()
    return when {
        owner != null && owner != deviceId -> IngestReasons.CONFLICT
        dirs.importedRoot != null && held(dirs, deviceId, name) -> IngestReasons.DUPLICATE
        else -> null
    }
}

private fun Replay.held(dirs: LogDirs, deviceId: String, name: String): Boolean =
    listOf(dirs.syncedDir(deviceId), dirs.importedDir(deviceId)).map { File(it, name) }.any { f ->
        f.exists() && try { parse(f) != null } catch (_: IOException) { false }
    }
