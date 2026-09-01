// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture.log

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Rolling, append-only log of unplugged battery readings — the other half of what this device's
 * battery did. The session log records what happened while the charger was attached; this records
 * the level as it falls between charges. One line per level change while unplugged is appended to
 * a single `discharge.ndjson` inside [dir], so a typical day is ~30–60 lines.
 *
 * It is a writer and a reader, and nothing else: no model, no thresholds, no judgment about what a
 * drain rate means. Whatever consumes [read] decides that. That is why it sits here rather than in
 * a host — `rawlog/` has exactly one owner, this module, which also owns its layout
 * ([io.github.allenbw.chargelog.data.LogLayout]), its retention sweep and its delete-all. Two
 * owners in one directory is how a sweep of unrecognized files quietly destroys a feature's whole
 * history, with no test on either side able to see it coming.
 *
 * Durability discipline follows [RawLogWriter]/[EventLog]: every [append] writes the line, flushes,
 * and fsyncs before returning, so a reading survives process death the instant it is recorded. Like
 * [EventLog] — and unlike the session [RawLogWriter] — it holds NO open stream between calls: it is
 * driven from `RecordingService`'s broadcast receiver on the main thread across arbitrary process
 * lifecycles (a level change can arrive after the process was killed and respawned), so each call is
 * self-contained and [Synchronized] against a concurrent append. `LiveFeed`, the reducer and Room
 * are untouched — this is not charging-session state.
 *
 * Deliberately Android-free: it takes a plain [File] directory, never a `Context`, so the whole
 * append→read path is JVM unit-testable. The caller supplies the `files/rawlog` directory.
 *
 * Size is this class's own responsibility — nothing else ever prunes the file:
 * `Retention` is session math and delete-all sweeps session files — so once the file outgrows
 * [maxBytes] an append compacts it to its newest half. At ~30–60 lines/day on a phone (2–3× on a
 * watch) the default cap is years of history, and the drain model only ever wants the recent past.
 */
class DischargeLog(private val dir: File, private val maxBytes: Long = MAX_BYTES) {

    private val file: File get() = File(dir, FILE_NAME)

    /**
     * Appends one unplugged reading. [wallClockMs] is the reading's wall-clock time (what the drain
     * model buckets by hour-of-day); [elapsedMs] is the monotonic `elapsedRealtime` clock, carried
     * per BG2's `RawLine.t`/`.e` discipline for later monotonic-delta use though the v1 model does
     * not read it; [screenOn] is recorded when the platform gave it, and lets a later model tell
     * active from idle drain (not required by v1).
     */
    @Synchronized
    fun append(wallClockMs: Long, elapsedMs: Long, level: Int, screenOn: Boolean? = null) {
        dir.mkdirs()
        val encoded = codec.encodeToString(
            Line.serializer(),
            Line(t = wallClockMs, e = elapsedMs, level = level, screenOn = screenOn),
        )
        java.io.FileOutputStream(file, /* append = */ true).use { stream ->
            stream.write((encoded + "\n").toByteArray(Charsets.UTF_8))
            stream.flush()
            // Best effort beyond flush, matching RawLogWriter: the write frequency here is low
            // (one line per level change), so a per-line fsync costs nothing measurable.
            try { stream.fd.sync() } catch (_: java.io.IOException) { /* flush already happened */ }
        }
        if (file.length() > maxBytes) compact()
    }

    /** Rewrites the file to the newest half of its lines. `length()` is a cheap stat, so the
     *  common append pays nothing; the rare compaction rewrites at most [maxBytes] of text. The
     *  temp-then-move keeps a reader from ever seeing a half-written file; a crash between the
     *  two leaves either the old file or the new one, both valid. */
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

    /** Deletes the log outright — the discharge half of a host's delete-all. */
    @Synchronized
    fun clear() {
        file.delete()
    }

    /**
     * Reads the whole log back as [DischargeSample]s in append (chronological) order. A missing file
     * reads as empty; a torn or partial trailing line — possible only if a crash landed mid-write
     * before the fsync completed — is skipped rather than failing the read, so one bad line never
     * hides the good history behind it. `elapsedMs` is intentionally dropped here: [DischargeSample]
     * carries the reading, not the bookkeeping.
     */
    @Synchronized
    fun read(): List<DischargeSample> {
        val f = file
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val line = try {
                codec.decodeFromString(Line.serializer(), trimmed)
            } catch (_: SerializationException) {
                return@mapNotNull null
            }
            DischargeSample(wallClockMs = line.t, level = line.level, screenOn = line.screenOn)
        }
    }

    /**
     * One persisted discharge reading. Minimal by design (spec BG2/BG3) and independent of the
     * sealed [RawLine] hierarchy, since `discharge.ndjson` only ever holds these; `t`/`e` mirror
     * [RawLine]'s wall-clock/elapsed field names. `encodeDefaults = false` + `explicitNulls = false`
     * keep a null [screenOn] off the wire.
     */
    @Serializable
    private data class Line(
        val t: Long,
        val e: Long,
        val level: Int,
        val screenOn: Boolean? = null,
    )

    companion object {
        const val FILE_NAME = "discharge.ndjson"

        /** ~10k lines at ~50 bytes each — three to six months of watch-rate history, years of
         *  phone-rate, either far more than the hour-of-day model needs. */
        const val MAX_BYTES = 512L * 1024

        private val codec = Json {
            encodeDefaults = false
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }
}

/**
 * One observed unplugged battery reading: the [level] at a wall-clock time, carrying [screenOn]
 * when the platform gave it. What [DischargeLog.read] returns — the measurement, with no
 * interpretation attached. Deliberately minimal and Android-free.
 */
data class DischargeSample(val wallClockMs: Long, val level: Int, val screenOn: Boolean? = null)
