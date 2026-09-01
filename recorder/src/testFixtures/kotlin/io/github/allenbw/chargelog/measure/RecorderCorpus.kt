// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.measure

import io.github.allenbw.chargelog.data.Replay
import java.io.File
import java.nio.file.Files

/**
 * Loader for the real-session NDJSON fixtures under `src/testFixtures/resources/corpus/`.
 * Every file is read through the real ingestion path, [Replay.parse], so a fixture that the
 * app could not ingest fails here rather than on a device.
 *
 * Fixtures are copied out of the classpath into a temp directory under their own file name
 * because [Replay.parse] takes a [File] and records `file.name` as the session's source file.
 */
object RecorderCorpus {
    fun file(name: String): File {
        val stream = RecorderCorpus::class.java.classLoader!!.getResourceAsStream("corpus/$name")
            ?: error("No corpus fixture named '$name'")
        val dir = Files.createTempDirectory("chargelog-corpus").toFile().apply { deleteOnExit() }
        val out = File(dir, name.substringAfterLast('/')).apply { deleteOnExit() }
        stream.use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    /** `parse` returns null only for a file with no header line; every fixture has one, so a
     *  null here is a fixture bug worth failing loudly on. */
    fun parsed(name: String): Replay.Parsed =
        Replay.parse(file(name)) ?: error("Replay.parse returned null for corpus fixture '$name' — no header line?")

    fun samples(name: String): List<AnalyzerSample> =
        parsed(name).samples.map { it.toAnalyzerSample() }
}
