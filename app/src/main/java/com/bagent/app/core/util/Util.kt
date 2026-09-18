package com.bagent.app.core.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Shared JSON instance for parsing tool arguments, schemas and configs. */
object JsonUtil {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun parseObject(text: String): JsonObject? = try {
        json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        null
    }

    fun parseElement(text: String): JsonElement? = try {
        json.parseToJsonElement(text)
    } catch (e: Exception) {
        null
    }

    fun toString(element: JsonElement): String = element.toString()
}

/** Redaction registry. Providers register live secret strings so logs strip them. */
object Redactor {
    private val secrets = linkedSetOf<String>()

    fun register(vararg values: String) {
        synchronized(this) {
            values.forEach { v ->
                if (v.isNotBlank() && v.length >= 4) secrets.add(v)
            }
        }
    }

    fun unregister(value: String) {
        synchronized(this) {
            secrets.remove(value)
        }
    }

    fun secretsSnapshot(): Set<String> = synchronized(this) { secrets.toSet() }

    fun redact(text: String): String {
        if (text.isBlank()) return text
        var out = text
        synchronized(this) {
            secrets.forEach { s ->
                if (s.length >= 4) out = out.replace(s, "***")
            }
        }
        // Generic token masking for common formats
        out = out.replace(
            Regex("(Bearer|sk-|asst_|AIza)[A-Za-z0-9_\\-.:]{8,}"),
            "$1***"
        )
        return out
    }
}

/** Parses a JSON string into a compact preview useful for tool-result summaries. */
object Preview {
    fun of(argumentsJson: String, max: Int = 1200): String {
        if (argumentsJson.isBlank()) return ""
        val el = JsonUtil.parseElement(argumentsJson) ?: return argumentsJson.take(max)
        var text = when (el) {
            is JsonPrimitive -> el.content
            else -> JsonUtil.json.encodeToString(JsonElement.serializer(), el)
        }
        if (text.length > max) text = text.take(max) + "…"
        return text
    }
}

fun now(): Long = System.currentTimeMillis()