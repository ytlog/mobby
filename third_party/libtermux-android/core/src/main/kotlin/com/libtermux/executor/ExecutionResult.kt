/**
 * LibTermux-Android
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * Author: cybernahid-dev (Systems Developer)
 * Project: https://github.com/AeonCoreX-Lab/libtermux-android
 */
package com.libtermux.executor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * Result of a command execution inside the Termux environment.
 */
@Serializable
data class ExecutionResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val executionTimeMs: Long,
    val command: String,
) {
    val isSuccess: Boolean get() = exitCode == 0
    val isFailure: Boolean get() = !isSuccess

    /** Combined stdout + stderr */
    val output: String
        get() = buildString {
            if (stdout.isNotEmpty()) append(stdout)
            if (stderr.isNotEmpty()) {
                if (stdout.isNotEmpty()) appendLine()
                append(stderr)
            }
        }

    /**
     * Parse stdout as a typed object using kotlinx.serialization.
     *
     * Handles special cases:
     * - `Map<String, Any>` / `Map<String, *>`  → converted via JsonObject
     * - `List<Any>`                             → converted via JsonArray
     * - Any `@Serializable` data class          → standard decoding
     */
    @Suppress("UNCHECKED_CAST")
    inline fun <reified T> parseJson(): T {
        val text = stdout.trim()
        val element = json.parseToJsonElement(text)

        if (isMapType<T>()) {
            return (element as JsonObject).toAnyMap() as T
        }
        if (isListType<T>()) {
            return (element as JsonArray).toAnyList() as T
        }
        return json.decodeFromString(text)
    }

    /** Get stdout lines as a list, filtering blank lines */
    fun stdoutLines(): List<String> = stdout.lines().filter { it.isNotEmpty() }

    override fun toString() =
        "ExecutionResult(exit=$exitCode, time=${executionTimeMs}ms, cmd='$command')"

    companion object {
        @PublishedApi
        internal val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

// ── Type-check helpers ────────────────────────────────────────────────────────

@PublishedApi
internal inline fun <reified T> isMapType(): Boolean {
    val cls = T::class.java
    return cls == Map::class.java ||
        cls == HashMap::class.java ||
        cls == LinkedHashMap::class.java ||
        cls == MutableMap::class.java ||
        Map::class.java.isAssignableFrom(cls)
}

@PublishedApi
internal inline fun <reified T> isListType(): Boolean {
    val cls = T::class.java
    return cls == List::class.java ||
        cls == ArrayList::class.java ||
        cls == MutableList::class.java ||
        List::class.java.isAssignableFrom(cls)
}

// ── JsonElement → Kotlin Any converters ─────────────────────────────────────

@PublishedApi
internal fun JsonObject.toAnyMap(): Map<String, Any?> =
    entries.associate { (k, v) -> k to v.toAny() }

@PublishedApi
internal fun JsonArray.toAnyList(): List<Any?> =
    map { it.toAny() }

@PublishedApi
internal fun JsonElement.toAny(): Any? = when (this) {
    is JsonNull      -> null
    is JsonPrimitive -> when {
        isString             -> content
        booleanOrNull != null -> boolean
        longOrNull != null   -> long
        doubleOrNull != null -> double
        else                 -> content
    }
    is JsonObject    -> toAnyMap()
    is JsonArray     -> toAnyList()
}

// ── Output stream type ────────────────────────────────────────────────────────

/** Represents a single line of streamed output */
sealed class OutputLine {
    data class Stdout(val text: String) : OutputLine()
    data class Stderr(val text: String) : OutputLine()
    data class Exit(val code: Int)      : OutputLine()
}
