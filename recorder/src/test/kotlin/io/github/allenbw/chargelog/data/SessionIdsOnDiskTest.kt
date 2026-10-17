// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionIdsOnDiskTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dirs() = LogDirs(
        File(tmp.root, "rawlog"), File(tmp.root, "synced"), "local",
        File(tmp.root, "incoming"), File(tmp.root, "imported"),
    )

    private fun touch(path: String) = File(tmp.root, path).apply { parentFile.mkdirs(); writeText("x") }

    @Test
    fun `every session file in every root counts, whether or not anything projected it`() = runBlocking {
        touch("rawlog/session-100.ndjson")
        touch("synced/w1/session-200.ndjson")
        touch("imported/p2/session-300.ndjson")
        touch("synced/p2/session-300.ndjson")
        touch("rawlog/events.ndjson")
        touch("rawlog/session-garbage.ndjson")
        touch("incoming/session-400.ndjson")
        assertEquals(setOf(100L, 200L, 300L), Replay.sessionIdsOnDisk(dirs()))
    }

    @Test
    fun `no roots on disk is no sessions`() = runBlocking {
        assertEquals(emptySet<Long>(), Replay.sessionIdsOnDisk(dirs()))
    }
}
