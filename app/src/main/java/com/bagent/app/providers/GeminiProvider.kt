package com.bagent.app.providers

import com.bagent.app.core.model.ChatMessage
import com.bagent.app.core.model.ProviderConfig
import com.bagent.app.core.model.ProviderEvent
import com.bagent.app.core.model.ProviderRequest
import com.bagent.app.core.model.Role
import com.bagent.app.core.network.Http
import com.bagent.app.core.util.JsonUtil
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Google Gemini generateContent provider (streaming, function calling). */
class GeminiProvider(
    config: ProviderConfig,
    apiKey: String
) : BaseProvider(config, apiKey) {

    override suspend fun stream(request: ProviderRequest, onEvent: (ProviderEvent) -> Unit) =
        withContext(Dispatchers.IO) {
            val model = request.model.ifBlank { config.model }.removePrefix("models/")
            val rawUrl = endpoint("v1beta/models/$model:streamGenerateContent")
            val url = rawUrl.toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("alt", "sse")
                ?.apply { if (apiKey.isNotBlank()) addQueryParameter("key", apiKey) }
                ?.build()
                ?: run {
                    onEvent(ProviderEvent.Error("invalid Gemini base url: ${config.baseUrl}"))
                    return@withContext
                }

            val body = buildJsonObject {
                val system = request.system ?: request.messages.firstOrNull { it.role == Role.SYSTEM }?.let { textOf(it) }
                if (!system.isNullOrBlank()) {
                    put("systemInstruction", buildJsonObject {
                        put("parts", buildJsonArray { add(textPartJson(system)) })
                    })
                }
                put("contents", contentsJson(request))
                if (request.tools.isNotEmpty()) {
                    put("tools", buildJsonArray {
                        add(buildJsonObject {
                            put("functionDeclarations", buildJsonArray {
                                request.tools.forEach { t ->
                                    add(buildJsonObject {
                                        put("name", t.name)
                                        put("description", t.description)
                                        put("parameters", t.parameters)
                                    })
                                }
                            })
                        })
                    })
                }
                if (request.temperature != null || request.maxTokens != null) {
                    put("generationConfig", buildJsonObject {
                        request.temperature?.let { put("temperature", it) }
                        request.maxTokens?.let { put("maxOutputTokens", it) }
                    })
                }
            }

            val httpRequest = Http.buildRequest(url.toString(), headers())
                .post(Http.jsonBody(body.toString()))
                .build()

            val response = execute(httpRequest, config.maxRetries, onEvent) ?: return@withContext
            var toolIndex = 0
            try {
                Http.streamSse(response) { sse ->
                    val obj = JsonUtil.parseObject(sse.data) ?: return@streamSse
                    obj.obj("error")?.let {
                        onEvent(ProviderEvent.Error(it.str("message") ?: "gemini error"))
                        return@streamSse
                    }
                    val candidate = obj.arr("candidates")?.firstOrNull()?.let { runCatching { it.jsonObject }.getOrNull() }
                        ?: return@streamSse
                    candidate.obj("content")?.arr("parts")?.forEach { partEl ->
                        val part = runCatching { partEl.jsonObject }.getOrNull() ?: return@forEach
                        part.str("text")?.takeIf { it.isNotEmpty() }?.let { onEvent(ProviderEvent.Delta(it)) }
                        part.obj("functionCall")?.let { fc ->
                            val name = fc.str("name") ?: ""
                            onEvent(
                                ProviderEvent.ToolCallDelta(
                                    index = toolIndex++,
                                    id = syntheticToolId(name.ifBlank { "tool" }, toolIndex),
                                    name = name,
                                    argsDelta = fc["args"]?.toString() ?: "{}"
                                )
                            )
                        }
                    }
                }
            } finally {
                response.close()
            }
            onEvent(ProviderEvent.Done)
        }

    private fun contentsJson(request: ProviderRequest): JsonArray {
        data class Entry(val role: String, val parts: MutableList<JsonElement>)
        val entries = mutableListOf<Entry>()
        chatMessages(request).forEach { m ->
            val role = if (m.role == Role.ASSISTANT) "model" else "user"
            val parts = partsFor(m)
            if (parts.isEmpty()) return@forEach
            val last = entries.lastOrNull()
            if (last != null && last.role == role) last.parts.addAll(parts)
            else entries.add(Entry(role, parts.toMutableList()))
        }
        return buildJsonArray {
            entries.forEach { e ->
                add(buildJsonObject {
                    put("role", e.role)
                    put("parts", jsonArrayOf(e.parts))
                })
            }
        }
    }

    private fun partsFor(message: ChatMessage): List<JsonElement> {
        val parts = mutableListOf<JsonElement>()
        when (message.role) {
            Role.USER -> {
                if (message.text.isNotBlank()) parts += textPartJson(message.text)
                imagesOf(message).forEach { img ->
                    parts += buildJsonObject {
                        put("inlineData", buildJsonObject {
                            put("mimeType", img.mediaType)
                            put("data", img.bytesBase64)
                        })
                    }
                }
            }
            Role.ASSISTANT -> {
                if (message.text.isNotBlank()) parts += textPartJson(message.text)
                message.assistantToolCalls.forEach { call ->
                    parts += buildJsonObject {
                        put("functionCall", buildJsonObject {
                            put("name", call.name)
                            put("args", JsonUtil.parseElement(call.argumentsJson) ?: JsonUtil.parseObject("{}")!!)
                        })
                    }
                }
            }
            Role.TOOL -> {
                parts += buildJsonObject {
                    put("functionResponse", buildJsonObject {
                        put("name", message.toolName ?: "")
                        put("response", buildJsonObject {
                            put("result", message.text)
                        })
                    })
                }
            }
            Role.SYSTEM -> Unit
        }
        return parts
    }
}