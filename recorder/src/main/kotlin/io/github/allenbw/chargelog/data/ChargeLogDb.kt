// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver

/**
 * Version 2 adds the device columns. Migration is destructive on purpose: this
 * DB is a disposable projection of the raw logs (Replay KDoc), and the launch-time reconcile
 * re-projects every session file it does not know — which, after a drop, is all of them.
 */
@Database(entities = [SessionEntity::class, SampleEntity::class], version = 2, exportSchema = true)
abstract class ChargeLogDb : RoomDatabase() {
    abstract fun dao(): CaptureDao

    companion object {
        fun open(context: Context): ChargeLogDb =
            Room.databaseBuilder<ChargeLogDb>(context, "chargelog.db")
                .setDriver(AndroidSQLiteDriver())
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        fun openInMemory(context: Context): ChargeLogDb =
            Room.inMemoryDatabaseBuilder<ChargeLogDb>(context)
                .setDriver(AndroidSQLiteDriver())
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
