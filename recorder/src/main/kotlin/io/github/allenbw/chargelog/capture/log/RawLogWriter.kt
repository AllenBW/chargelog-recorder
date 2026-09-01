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
        // Durability beyond process death only where it is cheap and matters:
        // the terminal event and gap markers. Never per sample.
        if (line is RawLine.Event && (line.kind == EventKinds.SESSION_END || line.kind == EventKinds.SERVICE_STOP || line.kind == EventKinds.GAP)) {
            sync()
        }
    }

    fun close() {
        writer?.flush()
        sync()
        writer?.close()
        writer = null
        stream = null
        currentFile = null
    }

    private fun sync() {
        try { stream?.fd?.sync() } catch (_: java.io.IOException) { /* best effort; the flush already happened */ }
    }
}

/**
 * Rolling log for out-of-session events (boot, service lifecycle,
 * manifest-receiver probe). Unlike [RawLogWriter] the service's instance IS
 * reached from more than one thread — the input pump plus the broadcast
 * receiver's direct plug-marker write — so appends on an instance are
 * serialized here.
 */
class EventLog(private val dir: File) {
    @Synchronized
    fun append(event: RawLine.Event) {
        dir.mkdirs()
        File(dir, "events.ndjson").appendText(NdjsonCodec.encode(event) + "\n")
    }
}
