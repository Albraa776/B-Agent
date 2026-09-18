package com.bagent.app.core.model

import kotlinx.serialization.json.JsonObject

/** AI provider categories supported by the ProviderManager. */
enum class ProviderType { OPENAI, ANTHROPIC, GEMINI, OPENROUTER, OPENAI_COMPAT, LOCAL }

/** Roles used in agent conversations. */
enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

/** Fine-grained agent runtime states. The UI reflects the real state. */
enum class AgentState {
    IDLE, THINKING, PLANNING, WAITING_FOR_PERMISSION, EXECUTING_TOOL,
    WAITING_FOR_PROCESS, OBSERVING, RECOVERING, PAUSED, COMPLETED,
    FAILED, CANCELLED, BLOCKED
}

/** Risk classification of tool actions. */
enum class RiskLevel { SAFE, LOW, MEDIUM, HIGH, CRITICAL }

/** High-level permission categories the permission engine understands. */
enum class PermAction {
    READ_FILES, WRITE_FILES, DELETE_FILES, EXECUTE_COMMANDS, NETWORK_ACCESS,
    INSTALL_PACKAGES, ACCESSIBILITY_CONTROL, SYSTEM_SETTINGS, PROCESS_CONTROL,
    ROOT_OPERATIONS
}

/** How a capability was rated during environment discovery. */
enum class CapRating { UNKNOWN, AVAILABLE, MISSING, BLOCKED }

/**
 * A multi-part message content part. Text parts are the default; image parts
 * are used when the selected model supports vision.
 */
sealed class ContentPart {
    data class Text(val text: String) : ContentPart()
    data class Image(val bytesBase64: String, val mediaType: String) : ContentPart()
}

/** A message in an agent session. */
data class ChatMessage(
    val role: Role,
    val parts: List<ContentPart>,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val assistantToolCalls: List<ToolCallRequest> = emptyList()
) {
    val text: String get() = parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
}

/** A tool call requested by the model. */
data class ToolCallRequest(
    val id: String,
    val name: String,
    val argumentsJson: String
)

/** A tool schema advertised to the model for function calling. */
data class ToolSchema(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

/** A complete request to an LLM provider. */
data class ProviderRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSchema> = emptyList(),
    val system: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null
)

/** Aggregated (final) provider output. */
data class ProviderResult(
    val text: String,
    val toolCalls: List<ToolCallRequest> = emptyList()
)

/** Streaming events emitted by a provider while a request runs. */
sealed class ProviderEvent {
    data class Delta(val text: String) : ProviderEvent()
    data class Reasoning(val text: String) : ProviderEvent()
    data class ToolCallDelta(
        val index: Int,
        val id: String,
        val name: String,
        val argsDelta: String
    ) : ProviderEvent()
    object Done : ProviderEvent()
    data class Error(val message: String) : ProviderEvent()
}

/** A named provider configuration persisted in the database. */
data class ProviderConfig(
    val id: Long,
    val name: String,
    val type: ProviderType,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val headersJson: JsonObject,
    val contextLimit: Int,
    val timeoutSec: Int,
    val stream: Boolean,
    val maxRetries: Int,
    val enabled: Boolean,
    val isDefault: Boolean,
    val reasoning: String
)

/** A workspace root may be a native path or a SAF content Uri. */
data class Workspace(
    val id: Long,
    val name: String,
    val rootUriOrPath: String,
    val projectType: String,
    val rules: String,
    val ignoredPaths: String,
    val preferredProviderId: Long?,
    val preferredModel: String,
    val createdAt: Long,
    val updatedAt: Long
)