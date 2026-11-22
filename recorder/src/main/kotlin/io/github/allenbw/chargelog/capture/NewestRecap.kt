// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.capture.log.RawLogWriter
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class NewestRecap(private val list: (File) -> List<File> = { RawLogWriter.sessionFiles(it) }) {
    class Read(val rank: Long, val recap: SessionRecap?)

    private val newest = AtomicReference(Read(NONE, null))
    private val closes = AtomicLong(STARTUP)
    private val opened = AtomicReference<MutableSet<String>?>(ConcurrentHashMap.newKeySet())

    val current: Read get() = newest.get()

    fun opening(sessionStartMs: Long) {
        opened.get()?.add(RawLogWriter.sessionFileName(sessionStartMs))
    }

    // TRAP docs/recording-service-threading.md — list, then take the names: a log opened between is read live
    fun previousLog(dir: File): File? {
        val logs = list(dir)
        val own = opened.getAndSet(null).orEmpty()
        return logs.lastOrNull { it.name !in own }
    }

    fun closing(): Long = closes.incrementAndGet()

    // TRAP docs/recording-service-threading.md — ranked by close order, not the clock; start-up below every close
    fun offer(rank: Long, recap: SessionRecap?): Read =
        Read(rank, recap).also { read -> newest.updateAndGet { if (rank > it.rank) read else it } }

    fun isNewest(read: Read): Boolean = newest.get() === read

    companion object {
        const val STARTUP = 0L
        private const val NONE = -1L
    }
}
