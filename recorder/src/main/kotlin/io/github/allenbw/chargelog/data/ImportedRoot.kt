// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Deletes [session]'s imported copy only, by its explicit path ([LogDirs.importedCopy]), under the
 * projection's lock. Its row goes too when that copy existed, or when no synced copy holds the
 * session either (a row pointing at nothing); the row of a session held only as synced is never
 * touched. A synced copy the import shadowed is left on disk; the caller's next [Replay.reconcile]
 * projects it. Returns false and does nothing when [LogDirs.importedRoot] is null or the session
 * is this device's own, and false if the file exists but cannot be deleted.
 */
suspend fun Replay.deleteImportedCopy(dirs: LogDirs, dao: CaptureDao, session: SessionEntity): Boolean {
    val id = session.deviceId ?: return false
    val file = dirs.importedCopy(session) ?: return false
    return locked {
        val ownsRow = file.exists() || !File(dirs.syncedDir(id), session.sourceFile).exists()
        removeCopy(dao, session, file, keepRow = !ownsRow)
    }
}

/**
 * Deletes [session]'s synced copy only, by its explicit path under [LogDirs.syncedRoot], under the
 * projection's lock. Its row goes too (also when the file was already gone) unless an imported
 * copy still holds the session, in which case the row is the import's and stays. Returns false
 * and does nothing for this device's own session (its file is under `phone`, never `synced/`) or
 * a row whose id fails [LogDirs.VALID_DEVICE_ID], and false if the file exists but cannot be
 * deleted.
 */
suspend fun Replay.deleteSyncedCopy(dirs: LogDirs, dao: CaptureDao, session: SessionEntity): Boolean {
    val id = session.deviceId ?: return false
    if (id == dirs.localDeviceId || !LogDirs.VALID_DEVICE_ID.matches(id)) return false
    return locked {
        val importHeld = dirs.importedCopy(session)?.exists() == true
        removeCopy(dao, session, File(dirs.syncedDir(id), session.sourceFile), keepRow = importHeld)
    }
}

internal suspend fun <T> Replay.locked(block: suspend () -> T): T =
    withContext(Dispatchers.IO) { mutex.withLock { withContext(NonCancellable) { block() } } }

private suspend fun removeCopy(dao: CaptureDao, session: SessionEntity, file: File?, keepRow: Boolean): Boolean {
    if (file == null) return true
    if (file.exists() && !file.delete()) return false
    val row = dao.session(session.id)
    val owned = row != null && row.deviceId == session.deviceId && row.sourceFile == session.sourceFile
    if (owned && !keepRow) dao.deleteSession(session.id)
    return true
}
