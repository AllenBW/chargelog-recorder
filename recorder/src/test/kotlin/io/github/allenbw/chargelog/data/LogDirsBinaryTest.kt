// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import android.content.Context
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class LogDirsBinaryTest {

    @Test
    fun `the one-argument of that published hosts call still exists`() {
        assertNotNull(LogDirs.Companion::class.java.getMethod("of", Context::class.java))
    }

    @Test
    fun `the constructors published hosts compiled against still exist`() {
        val file = File::class.java
        val string = String::class.java
        assertNotNull(LogDirs::class.java.getConstructor(file, file, string, file))
        assertNotNull(
            LogDirs::class.java.getDeclaredConstructor(
                file, file, string, file, Int::class.javaPrimitiveType, kotlin.jvm.internal.DefaultConstructorMarker::class.java,
            ),
        )
    }
}
