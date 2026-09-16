// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

internal object BackgroundStart {

    enum class Decision {
        /** The expected broadcast, and the host reports consent: start the service. */
        START,

        /** Not the broadcast this receiver is registered for: nothing to do, nothing to say. */
        IGNORE,

        /** The expected broadcast, but the host reports no consent yet: do not start, and say
         *  so once — a reboot that left the recorder down should be explicable from logcat. */
        NOT_CONSENTED,
    }

    /** [consented] is a lambda so the host is asked only for the broadcast this receiver is
     *  for — a prefs read on the main thread that an unrelated intent has no business costing. */
    fun decide(action: String?, expectedAction: String, consented: () -> Boolean): Decision = when {
        action != expectedAction -> Decision.IGNORE
        !consented() -> Decision.NOT_CONSENTED
        else -> Decision.START
    }

    /** The one tag both receivers log under. */
    const val TAG = "ChargeLogRecorder"
}
