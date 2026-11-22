// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import android.database.SQLException
import android.util.Log
import io.github.allenbw.chargelog.capture.log.NdjsonCodec
import io.github.allenbw.chargelog.capture.log.RawLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

sealed interface IngestResult {
    data class Imported(val session: SessionEntity) : IngestResult
    data class Rejected(val reason: String) : IngestResult
}

object IngestReasons {
    /** No session header could be found. Also a stream with a line longer than any session log
     *  carries (a binary file, or one with no newline), refused while it is copied, before it is
     *  read into memory or staged whole. */
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
    /** The stream is larger than any session log the recorder writes: 128 MiB, against about
     *  87 MB for two days at one line a second with every line as wide as the widest recorded (506
     *  bytes, a header; no sample line has passed 327). Refused while it is copied, so a wrong pick
     *  never fills the disk or the heap. Also a file under that size that holds more than
     *  [Replay.MAX_SAMPLES] samples, refused when it is read and before it lands. */
    const val TOO_LARGE = "TOO_LARGE"
}

internal object IngestLimits {
    const val MAX_BYTES: Long = 128L * 1024 * 1024
    const val MAX_LINE_BYTES: Int = 64 * 1024
}

/**
 * The one entry point for a session file that did not come from this device's recorder: the SAF
 * "Import session log" action now, with more transports (e.g. Bluetooth) to follow.
 * Copies [source] to a temp file, validates the header, moves it atomically into
 * `<importedRoot>/<deviceId>/session-<startMs>.ndjson` when [LogDirs.importedRoot] is set (else
 * `synced/<deviceId>/…`, the layout before that root existed), and projects it. With the root
 * set, a session either root already holds is refused as [IngestReasons.DUPLICATE]. The checks,
 * the move and the projection run under the same lock as [Replay.reconcile] and the deletes, so
 * none of them sees the file landed but not yet projected. Rejections leave no file behind. The
 * copy stops at the first byte past the size cap ([IngestReasons.TOO_LARGE]) or past the longest
 * line a session log has ([IngestReasons.NO_HEADER]), so neither is ever staged whole. A file
 * past [Replay.MAX_SAMPLES] samples is refused as [IngestReasons.TOO_LARGE] too.
 *
 * A database error never reaches the caller, who typically runs this from a UI coroutine where a
 * throw ends the process. One before the file lands — the `SQLiteDiskIOException` of a failing
 * disk, any other [SQLException] — refuses the import as [IngestReasons.IO] and leaves nothing.
 * One after it lands — the `SQLiteFullException` of a full disk — leaves the import standing:
 * the file is held, [IngestResult.Imported] is returned, no row is written ([CaptureDao.project]
 * is all or nothing), and the next [Replay.reconcile] projects it. An [Error] still propagates.
 */
suspend fun Replay.ingestExternal(source: InputStream, dirs: LogDirs, dao: CaptureDao): IngestResult =
    withContext(Dispatchers.IO) {
        val tmp = File(dirs.incoming.apply { mkdirs() }, "${UUID.randomUUID()}.tmp")
        try {
            val refusal = tmp.outputStream().use { out -> source.use { copyBounded(it, out) } }
            if (refusal != null) IngestResult.Rejected(refusal) else ingestFile(tmp, dirs, dao)
        } catch (_: IOException) {
            IngestResult.Rejected(IngestReasons.IO)
        } catch (e: SQLException) {
            Log.w(TAG, "import refused: the database could not be read", e)
            IngestResult.Rejected(IngestReasons.IO)
        } finally {
            tmp.delete()
        }
    }

private suspend fun Replay.ingestFile(tmp: File, dirs: LogDirs, dao: CaptureDao): IngestResult {
    val p = try {
        parse(tmp)
    } catch (_: Replay.TooManySamples) {
        return IngestResult.Rejected(IngestReasons.TOO_LARGE)
    } catch (_: IOException) {
        null
    }
    val deviceId = p?.session?.deviceId
    return when {
        p == null -> IngestResult.Rejected(IngestReasons.NO_HEADER)
        deviceId == null -> IngestResult.Rejected(IngestReasons.MISSING_DEVICE_IDENTITY)
        else -> mutex.withLock { withContext(NonCancellable) { land(p, deviceId, tmp, dirs, dao) } }
    }
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
    try {
        dao.project(session, p.samples)
    } catch (e: SQLException) {
        Log.w(TAG, "session ${session.id} imported; the next reconcile projects it", e)
    }
    return IngestResult.Imported(session)
}

private const val TAG = "Ingest"

private class LineMeter {
    private var current = 0
    var longest = 0
        private set

    fun feed(buf: ByteArray, n: Int) {
        for (i in 0 until n) {
            current = if (buf[i] == NEWLINE) 0 else current + 1
            if (current > longest) longest = current
        }
    }
}

private const val NEWLINE = '\n'.code.toByte()

private fun copyBounded(source: InputStream, out: OutputStream): String? {
    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
    val lines = LineMeter()
    var total = 0L
    var refusal: String? = null
    var n = source.read(buf)
    while (n >= 0 && refusal == null) {
        total += n
        lines.feed(buf, n)
        refusal = when {
            total > IngestLimits.MAX_BYTES -> IngestReasons.TOO_LARGE
            lines.longest > IngestLimits.MAX_LINE_BYTES -> IngestReasons.NO_HEADER
            else -> null
        }
        if (refusal == null) {
            out.write(buf, 0, n)
            n = source.read(buf)
        }
    }
    return refusal
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

private fun held(dirs: LogDirs, deviceId: String, name: String): Boolean =
    listOf(dirs.syncedDir(deviceId), dirs.importedDir(deviceId)).map { File(it, name) }.any { f ->
        f.exists() && try { hasHeader(f) } catch (_: IOException) { false }
    }

private fun hasHeader(file: File): Boolean = file.useLines { lines ->
    lines.any { raw ->
        raw.isNotBlank() && try { NdjsonCodec.decode(raw) is RawLine.Header } catch (_: Exception) { false }
    }
}
