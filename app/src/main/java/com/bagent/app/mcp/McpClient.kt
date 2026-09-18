package com.bagent.app.mcp

import com.bagent.app.core.database.McpServerEntity
import com.bagent.app.core.network.Http
import com.bagent.app.core.util.JsonUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import java.util.concurrent.atomic.AtomicInteger

/** A tool advertised by an MCP server. */
data class McpToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

/**
 * Minimal, real MCP client over the Streamable HTTP transport (JSON-RPC 2.0).
 * Supports initialize / tools list / tools call and both JSON and SSE replies.
 */
class McpClient(private val server: McpServerEntity) {

    private var sessionId: String? = null
    private val nextId = AtomicInteger(1)

    suspend fun initialize(): JsonObject = withContext(Dispatchers.IO) {
        val result = rpc(
            "initialize",
            buildJsonObject {
                put("protocolVersion", "2024-11-05")
                put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject {
                    put("name", "B Agent")
                    put("version", "1.0.0")
                })
            }
        )
        runCatching { notify("notifications/initialized", buildJsonObject {}) }
        result
    }

    suspend fun listTools(): List<McpToolDef> = withContext(Dispatchers.IO) {
        val result = rpc("tools/list", buildJsonObject {})
        val tools: JsonArray = runCatching { result["tools"]?.jsonArray }.getOrNull() ?: return@withContext emptyList()
        tools.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val name = runCatching { obj["name"]!!.jsonPrimitive.content }.getOrNull() ?: return@mapNotNull null
            McpToolDef(
                name = name,
                description = runCatching { obj["description"]!!.jsonPrimitive.content }.getOrDefault(""),
                inputSchema = runCatching { obj["inputSchema"]!!.jsonObject }
                    .getOrElse { JsonUtil.parseObject("""{"type":"object","properties":{}}""")!! }
            )
        }
    }

    suspend fun callTool(name: String, arguments: JsonObject): String = withContext(Dispatchers.IO) {
        val result = rpc(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            }
        )
        val content = runCatching { result["content"]?.jsonArray }.getOrNull()
        if (content == null) result.toString()
        else content.joinToString("\n") { element ->
            val obj = runCatching { element.jsonObject }.getOrNull()
            when {
                obj == null -> element.toString()
                obj["type"]?.jsonPrimitive?.content == "text" ->
                    runCatching { obj["text"]!!.jsonPrimitive.content }.getOrDefault("")
                else -> element.toString()
            }
        }.ifBlank { result.toString() }
    }

    private fun rpc(method: String, params: JsonObject): JsonObject {
        val id = nextId.getAndIncrement()
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val response = post(payload)
        val error = runCatching { response["error"]?.jsonObject }.getOrNull()
        if (error != null) {
            val message = runCatching { error["message"]!!.jsonPrimitive.content }.getOrDefault("MCP error")
            throw IllegalStateException("$method failed: $message")
        }
        return runCatching { response["result"]?.jsonObject }.getOrNull() ?: buildJsonObject {}
    }

    private fun notify(method: String, params: JsonObject) {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }
        post(payload)
    }

    private fun post(payload: JsonObject): JsonObject {
        val builder = Request.Builder()
            .url(server.url)
            .post(Http.jsonBody(payload.toString()))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        runCatching { JsonUtil.parseObject(server.authJson) }.getOrNull()?.forEach { (key, value) ->
            val headerValue = runCatching { value.jsonPrimitive.content }.getOrNull() ?: return@forEach
            if (key.equals("authorization", true) && !headerValue.startsWith("Bearer", true)) {
                builder.header("Authorization", "Bearer $headerValue")
            } else {
                builder.header(key, headerValue)
            }
        }
        Http.client.newCall(builder.build()).execute().use { response ->
            response.header("Mcp-Session-Id")?.let { sessionId = it }
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful && body.isBlank()) {
                throw IllegalStateException("MCP HTTP ${response.code}")
            }
            val json = extractJson(body)
                ?: throw IllegalStateException("MCP returned an unreadable response (HTTP ${response.code})")
            return json
        }
    }

    /** Accepts either a raw JSON body or an SSE stream containing JSON data lines. */
    private fun extractJson(body: String): JsonObject? {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) return runCatching { JsonUtil.parseObject(trimmed) }.getOrNull()
        val dataLine = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .firstOrNull { it.startsWith("{") && !it.contains("\"method\"") }
            ?: trimmed.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .firstOrNull { it.startsWith("{") }
        return dataLine?.let { runCatching { JsonUtil.parseObject(it) }.getOrNull() }
    }

    /** Convenience probe used by diagnostics and the UI. */
    suspend fun probe(): String {
        initialize()
        val tools = listTools()
        return "${tools.size} tool(s)"
    }

    companion object {
        fun mcpPayload(id: Int, method: String, params: JsonObject): JsonObject = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
    }
}

@Suppress("unused")
private fun emptyParams() = buildJsonObject {}