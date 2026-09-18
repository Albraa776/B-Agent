package com.bagent.app.core.network

import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A single server-sent event. */
data class SseEvent(val event: String, val data: String)

/** Shared HTTP plumbing: OkHttp client, streaming SSE reader and retries. */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun jsonBody(json: String) = json.toRequestBody(jsonType)

    /**
     * Blocks while reading an SSE stream from [response], invoking [onEvent]
     * for every dispatched event. Must be called on a background dispatcher.
     */
    fun streamSse(response: Response, onEvent: (SseEvent) -> Unit) {
        val body = response.body ?: return
        val source: BufferedSource = body.source()
        val eventBuilder = StringBuilder()
        var eventType = "message"
        while (true) {
            val raw = source.readUtf8Line() ?: break
            if (raw.isEmpty()) {
                if (eventBuilder.isNotEmpty()) {
                    onEvent(SseEvent(eventType, eventBuilder.toString()))
                    eventBuilder.clear()
                    eventType = "message"
                }
                continue
            }
            when {
                raw.startsWith("event:") -> eventType = raw.substringAfter(':').trim()
                raw.startsWith("data:") -> {
                    val data = raw.substringAfter(':').trim()
                    if (data == "[DONE]") {
                        onEvent(SseEvent(eventType, "[DONE]"))
                        eventBuilder.clear()
                        continue
                    }
                    if (eventBuilder.isNotEmpty()) eventBuilder.append('\n')
                    eventBuilder.append(data)
                }
                raw.startsWith(":") -> {
                    // comment, ignore
                }
            }
        }
        if (eventBuilder.isNotEmpty()) {
            onEvent(SseEvent(eventType, eventBuilder.toString()))
        }
    }

    fun buildRequest(url: String, headers: Map<String, String>): Request.Builder {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder
    }

    /** Executes with a simple retry-with-backoff strategy, returning a closed Response body string. */
    @Throws(IOException::class)
    fun executeWithRetry(
        request: Request,
        retries: Int,
        onRetry: (() -> Unit)? = null
    ): Response {
        var attempt = 0
        while (true) {
            attempt++
            val call: Call = client.newCall(request)
            val response = call.execute()
            if (response.isSuccessful) return response
            response.body?.close()
            if (attempt > retries) return response
            Thread.sleep((attempt * 1_000L).coerceAtMost(5_000L))
            onRetry?.invoke()
        }
    }

    fun enqueue(request: Request, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = onError(e.message ?: "network error")
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) onSuccess(it.body?.string() ?: "") else onError("HTTP ${it.code}")
                }
            }
        })
    }

    fun parseHeaders(json: JsonObject?): Map<String, String> {
        val map = linkedMapOf<String, String>()
        json?.forEach { (k, v) ->
            val s = v.toString().trim('"').trim()
            if (s.isNotEmpty()) map[k] = s
        }
        return map
    }
}