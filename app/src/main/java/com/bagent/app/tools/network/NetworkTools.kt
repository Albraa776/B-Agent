package com.bagent.app.tools.network

import com.bagent.app.core.model.PermAction
import com.bagent.app.core.model.RiskLevel
import com.bagent.app.core.network.Http
import com.bagent.app.core.util.JsonUtil
import com.bagent.app.core.util.Redactor
import com.bagent.app.tools.ToolBase
import com.bagent.app.tools.ToolEnv
import com.bagent.app.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/** Network inspection tools. Outbound network is gated by the permission engine. */
class NetworkTools {

    fun all(): List<ToolBase> = listOf(
        HttpGet(), HttpHead(), HttpPost(), NetStatus()
    )

    private fun headersOf(args: JsonObject): Map<String, String> {
        val raw = args["headers"]?.toString() ?: return emptyMap()
        return Http.parseHeaders(JsonUtil.parseObject(raw))
    }

    inner class HttpGet : ToolBase() {
        override val id = "http_get"
        override val name = "http_get"
        override val category = "network"
        override val description = "Perform a GET request and return the response body (capped)."
        override val requiredPermission = PermAction.NETWORK_ACCESS
        override val risk = RiskLevel.LOW
        override val parameters = JsonUtil.parseObject(
            """{"type":"object","properties":{"url":{"type":"string"},"headers":{"type":"object","description":"optional header map"},"maxBytes":{"type":"integer"}},"required":["url"]}"""
        )
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult = withContext(Dispatchers.IO) {
            val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return@withContext ToolResult.Failure("missing url")
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext ToolResult.Failure("unsupported scheme")
            val max = (args["maxBytes"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 500_000).coerceIn(1_000, 5_000_000)
            val request = Http.buildRequest(url, headersOf(args)).get().build()
            try {
                val response = Http.executeWithRetry(request, 1)
                val code = response.code
                val body = response.body?.string() ?: ""
                response.close()
                val truncated = body.length > max
                ToolResult.Success(buildJsonObject {
                    put("status", code)
                    put("contentType", response.header("Content-Type") ?: "")
                    put("truncated", truncated)
                    put("body", Redactor.redact(body.take(max)))
                }.toString())
            } catch (e: Exception) {
                ToolResult.Failure("request failed: ${e.message}")
            }
        }
    }

    inner class HttpHead : ToolBase() {
        override val id = "http_head"
        override val name = "http_head"
        override val category = "network"
        override val description = "Perform a HEAD request and return response headers."
        override val requiredPermission = PermAction.NETWORK_ACCESS
        override val risk = RiskLevel.LOW
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"url":{"type":"string"},"headers":{"type":"object"}},"required":["url"]}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult = withContext(Dispatchers.IO) {
            val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return@withContext ToolResult.Failure("missing url")
            val request = Http.buildRequest(url, headersOf(args)).head().build()
            try {
                val response = Http.executeWithRetry(request, 1)
                val code = response.code
                val headers = buildJsonObject {
                    response.headers.forEach { (name, value) -> put(name, Redactor.redact(value)) }
                }
                response.close()
                ToolResult.Success(buildJsonObject {
                    put("status", code)
                    put("headers", headers)
                }.toString())
            } catch (e: Exception) {
                ToolResult.Failure("request failed: ${e.message}")
            }
        }
    }

    inner class HttpPost : ToolBase() {
        override val id = "http_post"
        override val name = "http_post"
        override val category = "network"
        override val description = "Perform a POST request with a JSON or text body."
        override val requiredPermission = PermAction.NETWORK_ACCESS
        override val risk = RiskLevel.MEDIUM
        override val parameters = JsonUtil.parseObject(
            """{"type":"object","properties":{"url":{"type":"string"},"body":{"type":"string"},"contentType":{"type":"string"},"headers":{"type":"object"},"maxBytes":{"type":"integer"}},"required":["url","body"]}"""
        )
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult = withContext(Dispatchers.IO) {
            val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return@withContext ToolResult.Failure("missing url")
            val body = args["body"]?.jsonPrimitive?.contentOrNull ?: ""
            val contentType = args["contentType"]?.jsonPrimitive?.contentOrNull ?: "application/json"
            val max = (args["maxBytes"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 500_000).coerceIn(1_000, 5_000_000)
            val request = Http.buildRequest(url, headersOf(args))
                .post(body.toRequestBody(contentType.toMediaType()))
                .build()
            try {
                val response = Http.executeWithRetry(request, 1)
                val code = response.code
                val respBody = response.body?.string() ?: ""
                response.close()
                val truncated = respBody.length > max
                ToolResult.Success(buildJsonObject {
                    put("status", code)
                    put("truncated", truncated)
                    put("body", Redactor.redact(respBody.take(max)))
                }.toString())
            } catch (e: Exception) {
                ToolResult.Failure("request failed: ${e.message}")
            }
        }
    }

    inner class NetStatus : ToolBase() {
        override val id = "net_status"
        override val name = "net_status"
        override val category = "network"
        override val description = "Report whether the device has active network connectivity."
        override val risk = RiskLevel.LOW
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{}}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult = withContext(Dispatchers.IO) {
            val cm = env.context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            val connected = caps != null
            val internet = connected && caps!!.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ToolResult.Success(buildJsonObject {
                put("connected", connected)
                put("internet", internet)
                put("metered", caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false)
            }.toString())
        }
    }
}