// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import io.github.allenbw.chargelog.capture.BackgroundStart.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundStartTest {

    private val boot = "android.intent.action.BOOT_COMPLETED"
    private val replaced = "android.intent.action.MY_PACKAGE_REPLACED"

    @Test fun `the expected broadcast starts the recorder when the host reports consent`() {
        assertEquals(Decision.START, BackgroundStart.decide(boot, expectedAction = boot, consented = { true }))
        assertEquals(Decision.START, BackgroundStart.decide(replaced, expectedAction = replaced, consented = { true }))
    }

    @Test fun `the expected broadcast without consent is refused, and says so`() {
        assertEquals(Decision.NOT_CONSENTED, BackgroundStart.decide(boot, expectedAction = boot, consented = { false }))
        assertEquals(Decision.NOT_CONSENTED, BackgroundStart.decide(replaced, expectedAction = replaced, consented = { false }))
    }

    @Test fun `any other broadcast is ignored before consent is even asked`() {
        val neverAsked: () -> Boolean = { throw AssertionError("consent asked for the wrong broadcast") }
        assertEquals(Decision.IGNORE, BackgroundStart.decide(replaced, expectedAction = boot, consented = neverAsked))
        assertEquals(Decision.IGNORE, BackgroundStart.decide(boot, expectedAction = replaced, consented = neverAsked))
        assertEquals(Decision.IGNORE, BackgroundStart.decide(null, expectedAction = boot, consented = neverAsked))
    }
}
