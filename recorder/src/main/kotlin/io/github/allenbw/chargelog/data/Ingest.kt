// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import kotlinx.coroutines.Dispatchers
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
    const val IO = "IO"
}

/**
 * The one entry point for a session file that did not come from this device's recorder: the SAF
 * "Import session log" action now, with more transports (e.g. Bluetooth) to follow.
 * Copies [source] to a temp file, validates the header, moves it atomically into
 * `synced/<deviceId>/session-<startMs>.ndjson`, and projects it. Rejections leave no file behind.
 */
suspend fun Replay.ingestExternal(source: InputStream, dirs: LogDirs, dao: CaptureDao): IngestResult =
    withContext(Dispatchers.IO) {
        val tmp = File(dirs.incoming.apply { mkdirs() }, "${UUID.randomUUID()}.tmp")
        try {
            tmp.outputStream().use { out -> source.use { it.copyTo(out) } }
            val p = try { parse(tmp) } catch (_: IOException) { null }
                ?: return@withContext IngestResult.Rejected(IngestReasons.NO_HEADER)
            val deviceId = p.session.deviceId
                ?: return@withContext IngestResult.Rejected(IngestReasons.MISSING_DEVICE_IDENTITY)
            if (!LogDirs.VALID_DEVICE_ID.matches(deviceId)) return@withContext IngestResult.Rejected(IngestReasons.INVALID_DEVICE_ID)
            if (deviceId == dirs.localDeviceId) return@withContext IngestResult.Rejected(IngestReasons.LOCAL_DEVICE)
            val existing = dao.session(p.session.id)
            if (existing != null && existing.deviceId != deviceId) return@withContext IngestResult.Rejected(IngestReasons.CONFLICT)
            val dest = File(dirs.syncedDir(deviceId).apply { mkdirs() }, "session-${p.session.id}.ndjson")
            if (!tmp.renameTo(dest)) return@withContext IngestResult.Rejected(IngestReasons.IO)
            val session = p.session.copy(sourceFile = dest.name)
            dao.upsertSession(session)
            dao.upsertSamples(p.samples)
            IngestResult.Imported(session)
        } catch (_: IOException) {
            IngestResult.Rejected(IngestReasons.IO)
        } finally {
            tmp.delete()
        }
    }
