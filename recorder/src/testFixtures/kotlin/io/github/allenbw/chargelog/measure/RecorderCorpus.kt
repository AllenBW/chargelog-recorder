// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.measure

import io.github.allenbw.chargelog.data.Replay
import java.io.File
import java.nio.file.Files

object RecorderCorpus {
    fun file(name: String): File {
        val stream = RecorderCorpus::class.java.classLoader!!.getResourceAsStream("corpus/$name")
            ?: error("No corpus fixture named '$name'")
        val dir = Files.createTempDirectory("chargelog-corpus").toFile().apply { deleteOnExit() }
        val out = File(dir, name.substringAfterLast('/')).apply { deleteOnExit() }
        stream.use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    fun parsed(name: String): Replay.Parsed =
        Replay.parse(file(name)) ?: error("Replay.parse returned null for corpus fixture '$name' — no header line?")

    fun samples(name: String): List<AnalyzerSample> =
        parsed(name).samples.map { it.toAnalyzerSample() }
}
