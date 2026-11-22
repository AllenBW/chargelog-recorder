// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChargeLogDbSchemaPinTest {

    private val pinned = mapOf(
        1 to "01b1d1503d827d1acdbf8a159e6d26ac",
        2 to "9430c2feec11ae894ab2585a5dc41363",
        3 to "362cab86b67dc027245a3f57058994cd",
        4 to "2b8ade5585f441ca47e1baa11a1f63e4",
        5 to "190075ed5f7e3fa35d72abeb815cd1af",
        6 to "190075ed5f7e3fa35d72abeb815cd1af",
    )

    private val moduleRoot = if (File("schemas").isDirectory) File(".") else File("recorder/recorder")
    private val schemas = File(moduleRoot, "schemas/io.github.allenbw.chargelog.data.ChargeLogDb")
    private val exports = File(moduleRoot, "build/intermediates/room/schemas")
    private val source = File(moduleRoot, "src/main/kotlin/io/github/allenbw/chargelog/data/ChargeLogDb.kt")

    private fun declaredVersion(): Int =
        Regex("""@Database\([^)]*version = (\d+)""").find(source.readText())!!.groupValues[1].toInt()

    private fun identity(file: File): Pair<Int, String> {
        val db = Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
        return db.getValue("version").jsonPrimitive.int to db.getValue("identityHash").jsonPrimitive.content
    }

    private fun committed(): Map<Int, String> =
        schemas.listFiles { f -> f.name.endsWith(".json") }.orEmpty().associate(::identity)

    private fun changed(version: Int, hash: String): String {
        val stale = exports.walkTopDown()
            .filter { it.name == "$version.json" && it.parentFile?.name == schemas.name }
            .filter { identity(it).second != hash }
            .joinToString { it.path }
        val where = stale.ifEmpty { "under build/intermediates/room/schemas" }
        return "schemas/$version.json no longer matches version $version as released: an entity changed " +
            "without a version bump. Restore schemas/$version.json, and bump ChargeLogDb.version or revert " +
            "the entity. Room's export of the changed schema is $where; should a restored file keep coming " +
            "back changed, run ./gradlew :recorder:clean and build once with --no-build-cache"
    }

    @Test
    fun `the schema directory and the database source are where this test looks`() {
        assertTrue("expected ${schemas.absolutePath}", schemas.isDirectory)
        assertTrue("expected ${source.absolutePath}", source.isFile)
    }

    @Test
    fun `every committed schema still carries the identity it was released with`() {
        val committed = committed()
        for ((version, hash) in pinned) {
            if (committed[version] != hash) assertEquals(changed(version, hash), hash, committed[version])
        }
    }

    @Test
    fun `the declared version has a committed schema and a pinned identity`() {
        val version = declaredVersion()
        val committed = committed()
        assertEquals(
            "ChargeLogDb.version is $version but the newest committed schema is ${committed.keys.maxOrNull()}: " +
                "build once so Room exports schemas/$version.json, and commit it",
            version, committed.keys.maxOrNull(),
        )
        assertTrue(
            "version $version has no pinned identity hash: add $version to \"${committed[version]}\" to the " +
                "map above, once the bump is known to be meant",
            pinned.containsKey(version),
        )
    }
}
