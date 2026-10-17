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

    /**
     * Runs [start] and reports whether the platform accepted it. Both broadcasts are documented
     * FGS background-start exemptions, but an OEM battery manager can refuse anyway — as
     * `ForegroundServiceStartNotAllowedException` (an [IllegalStateException]) or a
     * [SecurityException] — and an uncaught refusal in a receiver kills the process on every boot
     * or update. A refusal leaves the recorder down until the app is opened, which is what a
     * refusal means; it must not also crash.
     */
    fun attempt(start: () -> Unit): Boolean = try {
        start()
        true
    } catch (_: IllegalStateException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /** The one tag both receivers log under. */
    const val TAG = "ChargeLogRecorder"
}
