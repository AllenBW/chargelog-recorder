// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import io.github.allenbw.chargelog.capture.log.DeviceKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogDirsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dirs() = LogDirs(File(tmp.root, "rawlog"), File(tmp.root, "synced"), localDeviceId = "local")
    private fun session(deviceKind: String, deviceId: String?) = SessionEntity(
        id = 1L, startedAtMs = 1L, endedAtMs = null, endReason = null, samplerProfileId = "p", schemaVersion = 2,
        startLevel = null, endLevel = null, startChargeCounterRaw = null, endChargeCounterRaw = null,
        sourceFile = "session-1.ndjson", deviceKind = deviceKind, deviceId = deviceId,
    )

    @Test
    fun `legacy and local sessions resolve to the phone dir`() {
        assertEquals(dirs().phone, dirs().dirFor(session(DeviceKinds.PHONE, null)))
        assertEquals(dirs().phone, dirs().dirFor(session(DeviceKinds.PHONE, "local")))
    }

    @Test
    fun `a foreign device resolves to its synced subdirectory regardless of kind`() {
        assertEquals(File(tmp.root, "synced/w1"), dirs().dirFor(session(DeviceKinds.WATCH, "w1")))
        assertEquals(File(tmp.root, "synced/p2"), dirs().dirFor(session(DeviceKinds.PHONE, "p2")))
    }

    @Test
    fun `all lists the phone dir first, then only existing synced subdirectories`() {
        val d = dirs()
        File(tmp.root, "synced/w1").mkdirs()
        File(tmp.root, "synced/stray.txt").writeText("x")
        assertEquals(listOf(d.phone, File(tmp.root, "synced/w1")), d.all())
    }

    @Test
    fun `all never includes the ingest scratch dir`() {
        val d = dirs()
        d.incoming.mkdirs()
        File(tmp.root, "synced/w1").mkdirs()
        assertEquals(listOf(d.phone, File(tmp.root, "synced/w1")), d.all())
    }

    @Test
    fun `syncedDir refuses a deviceId with path separators`() {
        assertThrows(IllegalArgumentException::class.java) { dirs().syncedDir("../x") }
    }
}
