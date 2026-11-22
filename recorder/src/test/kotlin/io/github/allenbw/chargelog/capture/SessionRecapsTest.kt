// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.capture.log.RawLogWriter
import java.io.File
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionRecapsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `a log that cannot be read has no recap, rather than an exception in the recorder's process`() {
        val unreadable = File(tmp.root, RawLogWriter.sessionFileName(1L)).apply { mkdirs() }
        assertNull(recapOf(unreadable))
    }

    @Test fun `a log deleted before its recap was read has no recap`() {
        assertNull(recapOf(File(tmp.root, RawLogWriter.sessionFileName(2L))))
    }
}
