package com.bagent.app.providers

import com.bagent.app.core.model.ChatMessage
import com.bagent.app.core.model.ContentPart
import com.bagent.app.core.model.ProviderConfig
import com.bagent.app.core.model.ProviderEvent
import com.bagent.app.core.model.ProviderRequest
import com.bagent.app.core.model.Role
import com.bagent.app.core.network.Http
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** A streaming chat provider bound to a persisted configuration. */
interface ChatProvider {
    val config: ProviderConfig
    suspend fun stream(request: ProviderRequest, onEvent: (ProviderEvent) -> Unit)
}

/** Shared request/response plumbing for concrete providers. */
abstract class BaseProvider(
    override val config: ProviderConfig,
    protected val apiKey: String
) : ChatProvider {

    /** Joins the configured base URL with a versioned API path. */
    protected fun endpoint(path: String): String {
        var base = config.baseUrl.trim().trimEnd('/')
        var suffix = path.trimStart('/')
        for (version in listOf("v1beta", "v1")) {
            if (base.endsWith("/$version") && suffix.startsWith("$version/")) {
                suffix = suffix.removePrefix("$version/")
            }
        }
        return "$base/$suffix"
    }

    protected fun headers(vararg extra: Pair<String, String>): Map<String, String> {
        val map = linkedMapOf(
            "Content-Type" to "application/json",
            "Accept" to "text/event-stream"
        )
        map.putAll(Http.parseHeaders(config.headersJson))
        extra.forEach { map[it.first] = it.second }
        return map
    }

    /** Executes a streaming request with bounded retries. Null means "already reported". */
    protected fun execute(
        request: Request,
        retries: Int,
        onEvent: (ProviderEvent) -> Unit
    ): Response? {
        var attempt = 0
        var lastMessage = "unknown error"
        while (attempt <= retries) {
            attempt++
            try {
                val response = Http.client.newCall(request).execute()
                if (response.isSuccessful) return response
                val code = response.code
                val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
                response.close()
                lastMessage = "HTTP $code: ${body.take(400)}"
                if (code in 400..499 && code != 429) break
            } catch (e: IOException) {
                lastMessage = "network error: ${e.message}"
            }
            if (attempt <= retries) Thread.sleep((attempt * 1_000L).coerceAtMost(4_000L))
        }
        onEvent(ProviderEvent.Error(lastMessage))
        return null
    }

    protected fun textOf(message: ChatMessage): String =
        message.parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }

    protected fun imagesOf(message: ChatMessage): List<ContentPart.Image> =
        message.parts.filterIsInstance<ContentPart.Image>()

    protected fun toolSchemaJson(
        name: String,
        description: String,
        parameters: JsonObject
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("parameters", parameters)
    }

    /** A stable, unique-enough id for providers that do not supply tool call ids. */
    protected fun syntheticToolId(name: String, index: Int): String = "call_${name}_$index"

    protected fun systemMessage(request: ProviderRequest): ChatMessage? =
        request.messages.firstOrNull { it.role == Role.SYSTEM }

    protected fun chatMessages(request: ProviderRequest): List<ChatMessage> =
        request.messages.filter { it.role != Role.SYSTEM }

    protected fun buildJson(element: kotlinx.serialization.json.JsonElement?): kotlinx.serialization.json.JsonElement =
        element ?: JsonPrimitive("")
}

/** Helpers shared by the JSON-shaped (OpenAI / Anthropic / Gemini) builders. */
internal fun JsonObject.str(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

internal fun JsonObject.int(key: String): Int? =
    this[key]?.let { runCatching { it.jsonPrimitive.content.toInt() }.getOrNull() }

internal fun JsonObject.obj(key: String): JsonObject? =
    this[key]?.let { runCatching { it.jsonObject }.getOrNull() }

internal fun JsonObject.arr(key: String): kotlinx.serialization.json.JsonArray? =
    this[key]?.let { runCatching { it.jsonArray }.getOrNull() }

internal fun JsonObject.boolean(key: String): Boolean =
    this[key]?.let { runCatching { it.jsonPrimitive.content.toBoolean() }.getOrNull() } ?: false

internal fun textPartJson(text: String) = buildJsonObject { put("text", text) }

internal fun jsonArrayOf(items: List<kotlinx.serialization.json.JsonElement>) =
    buildJsonArray { items.forEach { add(it) } }