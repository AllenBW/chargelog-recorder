// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture.log

import java.io.File

/**
 * Append-only session log: one file per session, header first, one NDJSON
 * line per record, flushed on every write and fsynced at close and on
 * terminal/gap events (durability over battery — the write amplification is
 * itself part of the measured self-perturbation).
 *
 * NOT thread-safe, by design: it is confined to `RecordingService`'s input
 * pump, the single coroutine that drains the capture input channel. Calling
 * it from anywhere else re-opens the `close()`-during-`append()` race that
 * crashed a 50-trial stress run.
 */
class RawLogWriter(private val dir: File) {

    private var stream: java.io.FileOutputStream? = null
    private var writer: java.io.Writer? = null
    var currentFile: File? = null
        private set
    val isOpen: Boolean get() = writer != null

    companion object {
        /**
         * The session log's file-naming rule, owned here because this is the code that creates
         * the names. It used to be hand-rolled at every read site instead — twice inside this
         * module and once in the closed app's `NudgeEngine`, which meant one fact about the open
         * module's on-disk format was duplicated across the licence line, where a change on this
         * side could not reach it.
         *
         * Name-based only, deliberately: a directory whose name matches is still returned, because
         * callers already handle that (see `Replay.reconcile`) and quietly filtering it here would
         * change behaviour they were written against.
         */
        const val SESSION_PREFIX = "session-"
        const val SESSION_SUFFIX = ".ndjson"

        /** The file name for a session that opened at [sessionStartWallClockMs]. */
        fun sessionFileName(sessionStartWallClockMs: Long): String =
            "$SESSION_PREFIX$sessionStartWallClockMs$SESSION_SUFFIX"

        /** True when [name] is a session log's file name. */
        fun isSessionFile(name: String): Boolean =
            name.startsWith(SESSION_PREFIX) && name.endsWith(SESSION_SUFFIX)

        /**
         * Every session log in [dir], oldest first. The name carries the session's start
         * wall-clock ms, so lexicographic order is chronological order and the newest is
         * `last()`. Empty — never null — when [dir] does not exist or cannot be listed.
         */
        fun sessionFiles(dir: File): List<File> =
            dir.listFiles { f -> isSessionFile(f.name) }?.sortedBy { it.name } ?: emptyList()
    }

    fun open(header: RawLine.Header): File {
        check(writer == null) { "session log already open: ${currentFile?.name}" }
        dir.mkdirs()
        val f = File(dir, sessionFileName(header.sessionStartWallClockMs))
        val s = java.io.FileOutputStream(f, true)
        stream = s
        writer = s.bufferedWriter()
        currentFile = f
        append(header)
        return f
    }

    fun append(line: RawLine) {
        val w = checkNotNull(writer) { "no open session log" }
        w.write(NdjsonCodec.encode(line))
        w.write("\n")
        w.flush()
        if (line is RawLine.Event && (line.kind == EventKinds.SESSION_END || line.kind == EventKinds.SERVICE_STOP || line.kind == EventKinds.GAP)) {
            sync()
        }
    }

    /**
     * Always leaves this writer closed, even when the flush throws.
     *
     * The flush used to run before the fields were cleared, so an `ENOSPC` on the way out stranded
     * a non-null `writer` — and `open()`'s `check(writer == null)` then refused every subsequent
     * session with an `IllegalStateException`, turning one full-disk moment into a recorder that
     * never opened a log again. The bytes are already lost at that point; the writer's state is
     * the part still worth saving (audit 2026-09-14).
     */
    fun close() {
        try {
            writer?.flush()
            sync()
            writer?.close()
        } catch (_: java.io.IOException) {
        } finally {
            writer = null
            stream = null
            currentFile = null
        }
    }

    private fun sync() {
        try { stream?.fd?.sync() } catch (_: java.io.IOException) { }
    }
}

/**
 * Rolling log for out-of-session events (boot, service lifecycle,
 * manifest-receiver probe). Unlike [RawLogWriter] the service's instance IS
 * reached from more than one thread — the input pump plus the broadcast
 * receiver's direct plug-marker write — so appends on an instance are
 * serialized here.
 *
 * Size and erasure are this class's own responsibility, exactly as they are [DischargeLog]'s and
 * for the same reason: nothing else prunes this file. `Retention` is session math and delete-all
 * sweeps session FILES, so until 2026-09-14 "rolling" named an intention the code did not have —
 * the file grew from install to uninstall, and a host's delete-all could not reach it at all. It
 * carries one `power_connected` and one `power_disconnected` line per plug cycle, which is a
 * record of when the owner is near a charger; [clear] is what lets a host honour "remove every
 * log file from this device", and [maxBytes] is what keeps it from outliving its usefulness.
 */
class EventLog(private val dir: File, private val maxBytes: Long = MAX_BYTES) {

    private val file: File get() = File(dir, FILE_NAME)

    @Synchronized
    fun append(event: RawLine.Event) {
        dir.mkdirs()
        file.appendText(NdjsonCodec.encode(event) + "\n")
        if (file.length() > maxBytes) compact()
    }

    private fun compact() {
        val lines = file.readLines().filter { it.isNotBlank() }
        val kept = lines.takeLast(lines.size / 2)
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
        java.nio.file.Files.move(
            tmp.toPath(), file.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    }

    /**
     * Deletes the log outright — the out-of-session half of a host's delete-all, and the
     * counterpart to [DischargeLog.clear].
     *
     * The `.tmp` goes too: a crash mid-[compact] can leave one behind holding the newest half of
     * the very history the user asked to erase, and it is not otherwise cleaned up.
     */
    @Synchronized
    fun clear() {
        file.delete()
        File(dir, "$FILE_NAME.tmp").delete()
    }

    companion object {
        /**
         * The out-of-session log's file name. Exported because a host has to name this file to
         * bundle it for diagnostics or to erase it, and a host that hard-codes the literal
         * silently misses the file the day it is renamed (which is what `SupportBundle` did until
         * this constant existed).
         */
        const val FILE_NAME = "events.ndjson"

        /** Same cap as [DischargeLog.MAX_BYTES]. At a handful of lines per plug cycle this is
         *  many years of history, and nothing reads further back than the recent past. */
        const val MAX_BYTES = 512L * 1024
    }
}
