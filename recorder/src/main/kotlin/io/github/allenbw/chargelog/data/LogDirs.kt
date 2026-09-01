// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import android.content.Context
import io.github.allenbw.chargelog.capture.DeviceIdentity
import java.io.File

/**
 * The set of directories the projection is built from: this device's own
 * recorder output plus one subdirectory per other device that has synced or been imported.
 * A session's directory is a function of its `deviceId` — legacy rows (null) and this device's
 * rows resolve to [phone]; every other id to `synced/<id>/`. `sourceFile` stays a bare filename.
 *
 * @param incoming Scratch space for [ingestExternal]'s half-written copies. Deliberately a SIBLING
 *   of [syncedRoot], not a child: [all] enumerates every subdirectory of [syncedRoot] as a device,
 *   so a scratch dir inside it is visited on every `reconcile` pass and any real file dropped there
 *   would be projected under `deviceId = "incoming"`.
 */
class LogDirs(
    val phone: File,
    val syncedRoot: File,
    val localDeviceId: String,
    val incoming: File = File(syncedRoot.parentFile ?: syncedRoot, "incoming"),
) {

    /** Rejects a `deviceId` before it becomes a directory name — a foreign session's header is
     *  untrusted input, and an unvalidated id (e.g. `"../../evil"`) would let
     *  [syncedDir] escape [syncedRoot]. Our own ids are 32 lowercase hex; this is deliberately
     *  wider so short test/manual ids like `"w1"` stay valid. */
    fun syncedDir(deviceId: String): File {
        require(VALID_DEVICE_ID.matches(deviceId)) { "invalid deviceId" }
        return File(syncedRoot, deviceId)
    }

    fun dirFor(session: SessionEntity): File =
        if (session.deviceId == null || session.deviceId == localDeviceId) phone else syncedDir(session.deviceId)

    /** Phone dir first (it may not exist yet), then each existing synced device dir, name-sorted. */
    fun all(): List<File> =
        listOf(phone) + (syncedRoot.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList())

    companion object {
        /** See [syncedDir]'s doc — the one gate between an imported header's `deviceId` and a
         *  filesystem path. */
        val VALID_DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")

        fun of(context: Context): LogDirs =
            LogDirs(
                LogLayout.ownDir(context),
                LogLayout.syncedRoot(context),
                DeviceIdentity.id(context),
                incoming = File(LogLayout.syncedRoot(context).parentFile, "incoming"),
            )
    }
}
