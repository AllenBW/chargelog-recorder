// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

package io.github.allenbw.chargelog.capture.log

import kotlinx.serialization.json.Json

object NdjsonCodec {
    private val json = Json {
        classDiscriminator = "y"
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun encode(line: RawLine): String = json.encodeToString(RawLine.serializer(), line)
    fun decode(s: String): RawLine = json.decodeFromString(RawLine.serializer(), s)
}
