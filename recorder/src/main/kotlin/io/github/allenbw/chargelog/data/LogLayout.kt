// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import android.content.Context
import java.io.File

/**
 * Where raw logs live. Under `noBackupFilesDir`: Auto Backup is 25 MB
 * all-or-nothing (Wear) and the phone's logs already overflow it after ~12–25 sessions, which
 * silently disabled backup of everything — prefs included. (The app has since opted out of Auto
 * Backup entirely — `allowBackup="false"`, because a full-data backup kills the recorder's process —
 * but logs stay here: `noBackupFilesDir` is also the honest home for data the app never uploads.)
 * The DB is a projection and rebuilds from these files.
 */
object LogLayout {
    const val RAWLOG = "rawlog"
    const val SYNCED = "synced"

    /** This device's own raw-log directory (`noBackupFilesDir/rawlog`) — the same path on a phone
     *  and on a watch; a device's own recordings never mix with `synced/`. */
    fun ownDir(context: Context): File = File(context.noBackupFilesDir, RAWLOG)

    /** Sessions received from other devices, one subdirectory per `deviceId`. */
    fun syncedRoot(context: Context): File = File(context.noBackupFilesDir, SYNCED)

    /** Where logs lived before this layout (`filesDir/rawlog`). */
    fun legacyPhoneDir(context: Context): File = File(context.filesDir, RAWLOG)

    /**
     * Moves every regular file in [from] to [to] (created on demand); a file that already exists
     * at the destination is left in place untouched — never overwritten, never deleted. Returns
     * how many files moved and removes [from] once it is empty. Idempotent; safe to call at
     * every startup.
     */
    fun migrateLegacy(from: File, to: File): Int {
        val files = from.listFiles { f -> f.isFile } ?: return 0
        if (files.isNotEmpty()) to.mkdirs()
        var moved = 0
        for (f in files) {
            val dest = File(to, f.name)
            if (dest.exists()) continue
            if (f.renameTo(dest)) moved++
        }
        if (from.listFiles()?.isEmpty() == true) from.delete()
        return moved
    }
}
