package com.bagent.app.providers

import com.bagent.app.core.model.ChatMessage
import com.bagent.app.core.model.ProviderConfig
import com.bagent.app.core.model.ProviderEvent
import com.bagent.app.core.model.ProviderRequest
import com.bagent.app.core.model.ProviderType
import com.bagent.app.core.model.Role
import com.bagent.app.core.network.Http
import com.bagent.app.core.util.JsonUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * OpenAI-compatible chat provider. Also drives OpenRouter, generic
 * OpenAI-compatible endpoints and local servers (Ollama / llama.cpp / vLLM).
 */
class OpenAiProvider(
    config: ProviderConfig,
    apiKey: String
) : BaseProvider(config, apiKey) {

    override suspend fun stream(request: ProviderRequest, onEvent: (ProviderEvent) -> Unit) =
        withContext(Dispatchers.IO) {
            val url = endpoint("v1/chat/completions")

            val body = buildJsonObject {
                put("model", request.model.ifBlank { config.model })
                put("stream", true)
                request.temperature?.let { put("temperature", it) }
                request.maxTokens?.let { put("max_tokens", it) }
                put("messages", messagesJson(request))
                if (request.tools.isNotEmpty()) {
                    put("tools", buildJsonArray {
                        request.tools.forEach { t ->
                            add(buildJsonObject {
                                put("type", "function")
                                put("function", toolSchemaJson(t.name, t.description, t.parameters))
                            })
                        }
                    })
                    put("tool_choice", "auto")
                }
                if (config.type == ProviderType.OPENAI && config.reasoning.lowercase() in listOf("low", "medium", "high")) {
                    put("reasoning_effort", config.reasoning.lowercase())
                }
            }

            val extra = mutableListOf<Pair<String, String>>()
            if (apiKey.isNotBlank()) extra += "Authorization" to "Bearer $apiKey"
            if (config.type == ProviderType.OPENROUTER) {
                extra += "HTTP-Referer" to "https://github.com/Albraa776/B-Agent"
                extra += "X-Title" to "B Agent"
            }
            val httpRequest = Http.buildRequest(url, headers(*extra.toTypedArray()))
                .post(Http.jsonBody(body.toString()))
                .build()

            val response = execute(httpRequest, config.maxRetries, onEvent) ?: return@withContext
            try {
                Http.streamSse(response) { sse ->
                    if (sse.data == "[DONE]") return@streamSse
                    val chunk = JsonUtil.parseObject(sse.data) ?: return@streamSse
                    val choice = chunk.arr("choices")?.firstOrNull()?.let { runCatching { it.jsonObject }.getOrNull() }
                        ?: return@streamSse
                    val delta = choice.obj("delta") ?: return@streamSse
                    delta.str("content")?.takeIf { it.isNotEmpty() }?.let {
                        onEvent(ProviderEvent.Delta(it))
                    }
                    delta.str("reasoning_content")?.takeIf { it.isNotEmpty() }?.let {
                        onEvent(ProviderEvent.Reasoning(it))
                    }
                    delta.arr("tool_calls")?.forEach { call ->
                        val obj = runCatching { call.jsonObject }.getOrNull() ?: return@forEach
                        val fn = obj.obj("function")
                        onEvent(
                            ProviderEvent.ToolCallDelta(
                                index = obj.int("index") ?: 0,
                                id = obj.str("id") ?: "",
                                name = fn?.str("name") ?: "",
                                argsDelta = fn?.str("arguments") ?: ""
                            )
                        )
                    }
                }
            } finally {
                response.close()
            }
            onEvent(ProviderEvent.Done)
        }

    private fun messagesJson(request: ProviderRequest): JsonArray = buildJsonArray {
        val system = request.system?.takeIf { it.isNotBlank() }
            ?: request.messages.firstOrNull { it.role == Role.SYSTEM }?.let { textOf(it) }
        if (!system.isNullOrBlank()) {
            add(buildJsonObject { put("role", "system"); put("content", system) })
        }
        request.messages.filter { it.role != Role.SYSTEM }.forEach { m ->
            when (m.role) {
                Role.USER -> add(userJson(m))
                Role.ASSISTANT -> add(assistantJson(m))
                Role.TOOL -> add(toolJson(m))
                Role.SYSTEM -> Unit
            }
        }
    }

    private fun userJson(message: ChatMessage): JsonElement {
        val images = imagesOf(message)
        if (images.isEmpty()) {
            return buildJsonObject { put("role", "user"); put("content", textOf(message)) }
        }
        return buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                if (textOf(message).isNotBlank()) add(textPartJson(textOf(message)))
                images.forEach { img ->
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject {
                            put("url", "data:${img.mediaType};base64,${img.bytesBase64}")
                        })
                    })
                }
            })
        }
    }

    private fun assistantJson(message: ChatMessage): JsonElement = buildJsonObject {
        put("role", "assistant")
        put("content", if (message.text.isNotBlank()) JsonPrimitive(message.text) else JsonNull)
        if (message.assistantToolCalls.isNotEmpty()) {
            put("tool_calls", buildJsonArray {
                message.assistantToolCalls.forEach { call ->
                    add(buildJsonObject {
                        put("id", call.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", call.name)
                            put("arguments", call.argumentsJson.ifBlank { "{}" })
                        })
                    })
                }
            })
        }
    }

    private fun toolJson(message: ChatMessage): JsonElement = buildJsonObject {
        put("role", "tool")
        put("tool_call_id", message.toolCallId ?: "")
        put("content", message.text)
    }
}