// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture

import android.content.Context
import java.security.SecureRandom
import java.util.Random

/**
 * A per-installation identifier: 16 random bytes, hex, minted once and kept in
 * its own prefs file. It names WHICH device authored a log so the phone hub can keep two devices'
 * sessions apart; it is not tied to hardware identifiers and is never sent anywhere.
 *
 * The app opts out of Auto Backup and device-to-device transfer altogether (`allowBackup="false"`
 * in the manifest — a full-data backup binds an agent into the app process and the backup manager
 * kills that process when it unbinds, foreground service or not), so
 * [FILE] never leaves the device — which is also what identity needs: a restore is a new
 * installation, so the new phone must mint its own id. Inheriting the old one would make two live installs claim the same
 * authorship (breaking `reconcile`'s conflict rule) and would make the old phone's own sessions
 * look local on the new one, so importing them would be refused as `LOCAL_DEVICE`. That coupling
 * is only expressible in the manifest's XML, so it cannot be pinned by a JVM test; this KDoc and
 * the comment in that file are the guard.
 */
object DeviceIdentity {
    const val LENGTH = 32
    private const val FILE = "chargelog_device"
    private const val KEY = "device_id"

    fun mint(random: Random): String {
        val bytes = ByteArray(LENGTH / 2).also { random.nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun id(context: Context): String {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val fresh = mint(SecureRandom())
        prefs.edit().putString(KEY, fresh).apply()
        return fresh
    }
}
