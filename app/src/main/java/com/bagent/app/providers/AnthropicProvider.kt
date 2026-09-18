package com.bagent.app.providers

import com.bagent.app.core.model.ChatMessage
import com.bagent.app.core.model.ProviderConfig
import com.bagent.app.core.model.ProviderEvent
import com.bagent.app.core.model.ProviderRequest
import com.bagent.app.core.model.Role
import com.bagent.app.core.network.Http
import com.bagent.app.core.util.JsonUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Native Anthropic Messages API provider with streaming and tool use. */
class AnthropicProvider(
    config: ProviderConfig,
    apiKey: String
) : BaseProvider(config, apiKey) {

    override suspend fun stream(request: ProviderRequest, onEvent: (ProviderEvent) -> Unit) =
        withContext(Dispatchers.IO) {
            val url = endpoint("v1/messages")
            val body = buildJsonObject {
                put("model", request.model.ifBlank { config.model })
                put("max_tokens", request.maxTokens ?: 4096)
                put("stream", true)
                request.temperature?.let { put("temperature", it.coerceIn(0.0, 1.0)) }
                val system = request.system ?: request.messages.firstOrNull { it.role == Role.SYSTEM }?.let { textOf(it) }
                if (!system.isNullOrBlank()) put("system", system)
                put("messages", messagesJson(request))
                if (request.tools.isNotEmpty()) {
                    put("tools", buildJsonArray {
                        request.tools.forEach { t ->
                            add(buildJsonObject {
                                put("name", t.name)
                                put("description", t.description)
                                put("input_schema", t.parameters)
                            })
                        }
                    })
                }
            }
            val httpRequest = Http.buildRequest(
                url,
                headers(
                    "x-api-key" to apiKey,
                    "anthropic-version" to "2023-06-01",
                    "anthropic-beta" to "prompt-caching-2024-07-31"
                )
            ).post(Http.jsonBody(body.toString())).build()

            val response = execute(httpRequest, config.maxRetries, onEvent) ?: return@withContext
            try {
                Http.streamSse(response) { sse ->
                    val obj = JsonUtil.parseObject(sse.data) ?: return@streamSse
                    when (obj.str("type")) {
                        "content_block_start" -> {
                            val block = obj.obj("content_block")
                            if (block?.str("type") == "tool_use") {
                                onEvent(
                                    ProviderEvent.ToolCallDelta(
                                        index = obj.int("index") ?: 0,
                                        id = block.str("id") ?: "",
                                        name = block.str("name") ?: "",
                                        argsDelta = ""
                                    )
                                )
                            }
                        }
                        "content_block_delta" -> {
                            val index = obj.int("index") ?: 0
                            val delta = obj.obj("delta") ?: return@streamSse
                            when (delta.str("type")) {
                                "text_delta" -> delta.str("text")?.let { onEvent(ProviderEvent.Delta(it)) }
                                "thinking_delta" -> delta.str("thinking")?.let { onEvent(ProviderEvent.Reasoning(it)) }
                                "input_json_delta" ->
                                    onEvent(ProviderEvent.ToolCallDelta(index, "", "", delta.str("partial_json") ?: ""))
                            }
                        }
                        "error" -> onEvent(ProviderEvent.Error(obj.obj("error")?.str("message") ?: "anthropic error"))
                        else -> Unit
                    }
                }
            } finally {
                response.close()
            }
            onEvent(ProviderEvent.Done)
        }

    private fun messagesJson(request: ProviderRequest): JsonArray = buildJsonArray {
        val msgs = chatMessages(request)
        var i = 0
        while (i < msgs.size) {
            val m = msgs[i]
            when (m.role) {
                Role.USER -> add(userJson(m))
                Role.ASSISTANT -> add(assistantJson(m))
                Role.TOOL -> {
                    val blocks = mutableListOf<JsonElement>()
                    while (i < msgs.size && msgs[i].role == Role.TOOL) {
                        val t = msgs[i]
                        blocks += buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", t.toolCallId ?: "")
                            put("content", t.text)
                        }
                        i++
                    }
                    add(buildJsonObject { put("role", "user"); put("content", jsonArrayOf(blocks)) })
                    continue
                }
                Role.SYSTEM -> Unit
            }
            i++
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
                        put("type", "image")
                        put("source", buildJsonObject {
                            put("type", "base64")
                            put("media_type", img.mediaType)
                            put("data", img.bytesBase64)
                        })
                    })
                }
            })
        }
    }

    private fun assistantJson(message: ChatMessage): JsonElement = buildJsonObject {
        put("role", "assistant")
        put("content", buildJsonArray {
            if (message.text.isNotBlank()) add(textPartJson(message.text))
            message.assistantToolCalls.forEach { call ->
                add(buildJsonObject {
                    put("type", "tool_use")
                    put("id", call.id)
                    put("name", call.name)
                    put("input", JsonUtil.parseElement(call.argumentsJson) ?: JsonUtil.parseObject("{}")!!)
                })
            }
        })
    }
}