// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class DeviceIdentityTest {
    @Test
    fun `mint yields 32 lowercase hex chars`() {
        val id = DeviceIdentity.mint(Random(1))
        assertEquals(DeviceIdentity.LENGTH, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `mint is deterministic for a seeded random and differs across seeds`() {
        assertEquals(DeviceIdentity.mint(Random(1)), DeviceIdentity.mint(Random(1)))
        assertNotEquals(DeviceIdentity.mint(Random(1)), DeviceIdentity.mint(Random(2)))
    }
}
