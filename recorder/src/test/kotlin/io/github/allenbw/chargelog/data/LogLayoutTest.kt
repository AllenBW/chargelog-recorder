// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogLayoutTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `migrateLegacy moves every file, returns the count, and removes the empty source dir`() {
        val from = tmp.newFolder("files", "rawlog")
        File(from, "session-1.ndjson").writeText("a\n")
        File(from, "events.ndjson").writeText("b\n")
        val to = File(tmp.root, "no_backup/rawlog")

        val n = LogLayout.migrateLegacy(from, to)

        assertEquals(2, n)
        assertEquals("a\n", File(to, "session-1.ndjson").readText())
        assertEquals("b\n", File(to, "events.ndjson").readText())
        assertFalse(from.exists())
    }

    @Test
    fun `migrateLegacy is a no-op when the legacy dir does not exist`() {
        val to = File(tmp.root, "no_backup/rawlog")
        assertEquals(0, LogLayout.migrateLegacy(File(tmp.root, "missing"), to))
        assertFalse(to.exists())
    }

    @Test
    fun `migrateLegacy never overwrites a file already at the destination`() {
        val from = tmp.newFolder("files", "rawlog")
        File(from, "session-1.ndjson").writeText("old\n")
        val to = tmp.newFolder("no_backup", "rawlog")
        File(to, "session-1.ndjson").writeText("new\n")

        val n = LogLayout.migrateLegacy(from, to)

        assertEquals(0, n)
        assertEquals("new\n", File(to, "session-1.ndjson").readText())
        assertTrue(File(from, "session-1.ndjson").exists()) // left behind, not lost
    }
}
