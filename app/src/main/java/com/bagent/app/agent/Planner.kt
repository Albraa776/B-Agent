package com.bagent.app.agent

import com.bagent.app.core.model.ChatMessage
import com.bagent.app.core.model.ContentPart
import com.bagent.app.core.model.ProviderEvent
import com.bagent.app.core.model.ProviderRequest
import com.bagent.app.core.model.Role
import com.bagent.app.core.settings.SettingsRepository
import com.bagent.app.core.util.JsonUtil
import com.bagent.app.providers.ProviderManager
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Produces a short, honest plan for a goal. If planning fails the run simply
 * proceeds without a plan rather than blocking on a planning error.
 */
class Planner(
    private val providers: ProviderManager,
    private val settings: SettingsRepository
) {
    suspend fun plan(goal: String, providerId: Long?, model: String): List<PlanStep> {
        if (goal.length < MIN_PLAN_LENGTH) return emptyList()
        return runCatching {
            val request = ProviderRequest(
                model = model,
                system = PLAN_SYSTEM,
                messages = listOf(
                    ChatMessage(
                        role = Role.USER,
                        parts = listOf(ContentPart.Text("Goal:\n$goal"))
                    )
                ),
                maxTokens = 500
            )
            val text = StringBuilder()
            var failed = false
            providers.stream(request, providerId) { event ->
                when (event) {
                    is ProviderEvent.Delta -> text.append(event.text)
                    is ProviderEvent.Error -> failed = true
                    else -> Unit
                }
            }
            if (failed) emptyList() else parseSteps(text.toString())
        }.getOrDefault(emptyList())
    }

    private fun parseSteps(raw: String): List<PlanStep> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val array = runCatching {
            JsonUtil.parseElement(raw.substring(start, end + 1))?.jsonArray
        }.getOrNull() ?: return emptyList()
        return array.take(MAX_STEPS).mapIndexedNotNull { index, element ->
            val description = when (element) {
                is JsonPrimitive -> element.contentOrNullSafe()
                else -> runCatching {
                    val obj = element.jsonObject
                    (obj["description"] ?: obj["step"] ?: obj["title"])?.jsonPrimitive?.content
                }.getOrNull()
            }
            description?.trim()?.takeIf { it.isNotBlank() }?.let { PlanStep(index + 1, it) }
        }
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? = runCatching { content }.getOrNull()

    companion object {
        private const val MIN_PLAN_LENGTH = 30
        private const val MAX_STEPS = 8
        private val PLAN_SYSTEM = """
            You are the planning component of B Agent, an Android automation agent.
            Break the user's goal into at most 8 concrete, verifiable steps.
            Respond with ONLY a JSON array of short strings (no prose, no markdown).
            Example: ["Inspect the project files","Run the build","Report errors"]
        """.trimIndent()
    }
}