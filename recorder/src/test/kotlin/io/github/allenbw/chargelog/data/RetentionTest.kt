// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionTest {

    private val now = 1_700_000_000_000L // arbitrary fixed instant

    @Test
    fun `FOREVER never prunes -- cutoff is null`() {
        assertNull(Retention.cutoffMs(now, RetentionWindow.FOREVER))
    }

    @Test
    fun `ONE_YEAR cutoff is exactly 365 days before now`() {
        assertEquals(now - 365L * 24 * 3600 * 1000, Retention.cutoffMs(now, RetentionWindow.ONE_YEAR))
    }

    @Test
    fun `SIX_MONTHS cutoff is exactly 182 days before now`() {
        assertEquals(now - 182L * 24 * 3600 * 1000, Retention.cutoffMs(now, RetentionWindow.SIX_MONTHS))
    }

    @Test
    fun `isPrunable is true one ms before the cutoff`() {
        val cutoff = 1_000_000L
        assertTrue(Retention.isPrunable(cutoff - 1, cutoff))
    }

    @Test
    fun `isPrunable is false exactly at the cutoff -- boundary is exclusive`() {
        val cutoff = 1_000_000L
        assertFalse(Retention.isPrunable(cutoff, cutoff))
    }

    @Test
    fun `isPrunable is false after the cutoff`() {
        val cutoff = 1_000_000L
        assertFalse(Retention.isPrunable(cutoff + 1, cutoff))
    }

    @Test
    fun `isPrunable is always false when cutoff is null -- FOREVER`() {
        assertFalse(Retention.isPrunable(0L, null))
        assertFalse(Retention.isPrunable(Long.MAX_VALUE, null))
    }
}
