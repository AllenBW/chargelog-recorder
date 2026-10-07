// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import android.content.Context
import io.github.allenbw.chargelog.capture.DeviceIdentity
import io.github.allenbw.chargelog.capture.log.RawLogWriter
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
 *
 * [importedRoot], set through the five-argument constructor, is where [ingestExternal] puts a log
 * that arrived by hand rather than over the sync wire: one subdirectory per `deviceId`, a sibling of
 * [syncedRoot]. The directory IS the provenance. It survives every rebuild of the projection and
 * needs no ledger that a crash could leave unwritten. Only imports made with the root set land
 * there; files imported before stay under [syncedRoot]. Null (the default) keeps imports under
 * [syncedRoot] exactly as before, so a host that does not set it sees no change.
 */
class LogDirs @JvmOverloads constructor(
    val phone: File,
    val syncedRoot: File,
    val localDeviceId: String,
    val incoming: File = File(syncedRoot.parentFile ?: syncedRoot, "incoming"),
) {
    /** See the class doc. Null unless set through the five-argument constructor, so the primary
     *  constructor keeps the exact signature hosts compiled against before this root existed. */
    var importedRoot: File? = null
        private set

    constructor(phone: File, syncedRoot: File, localDeviceId: String, incoming: File, importedRoot: File?) :
        this(phone, syncedRoot, localDeviceId, incoming) {
        this.importedRoot = importedRoot
    }

    /** Rejects a `deviceId` before it becomes a directory name — a foreign session's header is
     *  untrusted input, and an unvalidated id (e.g. `"../../evil"`) would let
     *  [syncedDir] escape [syncedRoot]. Our own ids are 32 lowercase hex; this is deliberately
     *  wider so short test/manual ids like `"w1"` stay valid. */
    fun syncedDir(deviceId: String): File {
        require(VALID_DEVICE_ID.matches(deviceId)) { "invalid deviceId" }
        return File(syncedRoot, deviceId)
    }

    /** [syncedDir]'s twin under [importedRoot], with the same validation. Throws
     *  [IllegalStateException] when this instance was built without an [importedRoot]. */
    fun importedDir(deviceId: String): File {
        val root = checkNotNull(importedRoot) { "no imported root" }
        require(VALID_DEVICE_ID.matches(deviceId)) { "invalid deviceId" }
        return File(root, deviceId)
    }

    /** Where [session]'s imported copy is, or would be, under [importedRoot]: null for this
     *  device's own rows and when there is no [importedRoot]. Says nothing about whether it exists. */
    fun importedCopy(session: SessionEntity): File? {
        val id = session.deviceId
        if (id == null || id == localDeviceId) return null
        return importedRoot?.let { File(importedDir(id), session.sourceFile) }
    }

    /**
     * The directory a session's file lives in. This device's own rows (a null id or the local id)
     * live in [phone]. A foreign row lives under [importedRoot] when its file is there, else under
     * [syncedRoot], as it always did. A session can be held in both, when its device syncs a
     * session that was imported by hand: the imported copy shadows the synced one (the projection
     * reads the import and leaves the synced file on disk) until [deleteImportedCopy] removes the
     * import and a [Replay.reconcile] reveals the synced copy.
     */
    fun dirFor(session: SessionEntity): File {
        val id = session.deviceId
        if (id == null || id == localDeviceId) return phone
        return importedCopy(session)?.takeIf { it.exists() }?.parentFile ?: syncedDir(id)
    }

    /** Phone dir first (it may not exist yet), then each existing synced device dir, then each
     *  existing imported device dir, each group name-sorted. */
    fun all(): List<File> =
        listOf(phone) + deviceDirs(syncedRoot) + (importedRoot?.let(::deviceDirs) ?: emptyList())

    internal fun projectable(dir: File): List<File> {
        val imported = importedRoot?.takeIf { dir.parentFile == syncedRoot }?.let { File(it, dir.name) }
        return RawLogWriter.sessionFiles(dir).filterNot { imported != null && File(imported, it.name).exists() }
    }

    internal fun copiesOf(session: SessionEntity): List<File> {
        val id = session.deviceId
        if (id == null || id == localDeviceId) return listOf(File(phone, session.sourceFile))
        return listOfNotNull(File(syncedDir(id), session.sourceFile), importedCopy(session))
    }

    private fun deviceDirs(root: File): List<File> =
        root.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()

    companion object {
        /** See [syncedDir]'s doc — the one gate between an imported header's `deviceId` and a
         *  filesystem path. */
        val VALID_DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")

        /** The standard layout under `noBackupFilesDir`. [separateImports] adds
         *  [LogLayout.importedRoot] as the [importedRoot]; leaving it false keeps the layout every
         *  host had before that root existed. */
        @JvmOverloads
        fun of(context: Context, separateImports: Boolean = false): LogDirs =
            LogDirs(
                LogLayout.ownDir(context),
                LogLayout.syncedRoot(context),
                DeviceIdentity.id(context),
                incoming = File(LogLayout.syncedRoot(context).parentFile, "incoming"),
                importedRoot = if (separateImports) LogLayout.importedRoot(context) else null,
            )
    }
}
