package com.bagent.app.agent

import com.bagent.app.core.database.MessageEntity
import com.bagent.app.core.database.PluginEntity
import com.bagent.app.core.database.ProjectMemoryEntity
import com.bagent.app.core.database.SkillEntity
import com.bagent.app.core.database.UserRuleEntity
import com.bagent.app.core.database.WorkspaceEntity
import com.bagent.app.core.model.ChatMessage
import com.bagent.app.core.model.Role
import com.bagent.app.core.model.ToolSchema
import com.bagent.app.core.settings.SettingsRepository

/** Result of assembling one provider request. */
data class BuiltContext(
    val system: String,
    val messages: List<ChatMessage>,
    val estimatedTokens: Int,
    val droppedMessages: Int
)

/**
 * Builds the system prompt and trimmed conversation sent to the model.
 * Honest, capability-aware, and budget bounded so context never silently
 * overflows the selected model's window.
 */
class ContextBuilder(private val settings: SettingsRepository) {

    data class Input(
        val workspace: WorkspaceEntity?,
        val rules: List<UserRuleEntity>,
        val memory: List<ProjectMemoryEntity>,
        val skills: List<SkillEntity>,
        val plugins: List<PluginEntity>,
        val environmentSummary: String,
        val mcpSummary: String,
        val history: List<MessageEntity>,
        val tools: List<ToolSchema>,
        val language: String,
        val budgetTokens: Int
    )

    fun build(input: Input): BuiltContext {
        val system = systemPrompt(input)
        val systemTokens = estimate(system)
        val budget = (input.budgetTokens - systemTokens).coerceAtLeast(2_000)

        val converted = input.history.map { it.toChatMessage() }
        val kept = ArrayDeque<ChatMessage>()
        var used = 0
        var dropped = 0
        for (message in converted.asReversed()) {
            val cost = estimate(message)
            if (used + cost > budget && kept.isNotEmpty()) {
                dropped++
                continue
            }
            kept.addFirst(message)
            used += cost
        }
        while (kept.isNotEmpty() && kept.first().role == Role.TOOL) kept.removeFirst()

        return BuiltContext(
            system = system,
            messages = kept.toList(),
            estimatedTokens = systemTokens + used,
            droppedMessages = dropped
        )
    }

    private fun systemPrompt(input: Input): String = buildString {
        appendLine("# B Agent")
        appendLine(
            "You are B Agent, an autonomous agent that runs natively on an Android device. " +
                "You act through real tools (terminal, filesystem, git, network, Android UI automation) and you report only what actually happened."
        )
        appendLine()

        appendLine("## Operating principles")
        appendLine("1. Never invent results. Every claim must come from a tool output you actually received.")
        appendLine("2. If a capability is missing or a command is unavailable, say so plainly and offer the closest real alternative.")
        appendLine("3. Prefer the smallest action that makes progress; verify before and after changes.")
        appendLine("4. Read a file before editing it. When editing, change only what is needed.")
        appendLine("5. If the same action fails twice, change approach instead of retrying blindly.")
        appendLine("6. Secrets and tokens must never be echoed back; treat redacted output as final.")
        appendLine("7. Respect permissions: some tools require user approval and may be denied.")
        appendLine("8. Use tools when they are needed; when the task is informational, answer directly.")
        appendLine("9. Keep the user informed with short progress notes between tool calls.")
        appendLine("10. Only claim a task complete after verifying the outcome with a tool when verification is possible.")
        appendLine()

        appendLine("## Response language")
        appendLine("Write user-facing prose in: ${input.language}.")
        appendLine()

        appendLine("## Environment")
        appendLine(input.environmentSummary.ifBlank { "Environment discovery has not produced results yet." })
        appendLine()

        input.workspace?.let { ws ->
            appendLine("## Active workspace")
            appendLine("- name: ${ws.name}")
            appendLine("- root: ${ws.rootUriOrPath}")
            appendLine("- project type: ${ws.projectType}")
            if (ws.ignoredPaths.isNotBlank()) appendLine("- ignored paths: ${ws.ignoredPaths}")
            if (ws.rules.isNotBlank()) appendLine("- workspace rules: ${ws.rules}")
            appendLine()
        }

        if (input.rules.isNotEmpty()) {
            appendLine("## User rules (highest priority first)")
            input.rules.sortedByDescending { it.priority }.forEach { appendLine("- [${it.scope}] ${it.name}: ${it.rule}") }
            appendLine()
        }

        if (input.memory.isNotEmpty()) {
            appendLine("## Project memory")
            input.memory.take(40).forEach { appendLine("- ${it.key}: ${it.value}") }
            appendLine()
        }

        val enabledSkills = input.skills.filter { it.enabled }
        if (enabledSkills.isNotEmpty()) {
            appendLine("## Skills")
            appendLine("Skills are on-demand instruction packs. Use the `skill` tool to load one before using it.")
            enabledSkills.forEach { skill ->
                appendLine("- ${skill.id}: ${skill.name} ${skill.version} - ${skill.description}")
            }
            appendLine()
        }

        val enabledPlugins = input.plugins.filter { it.enabled }
        if (enabledPlugins.isNotEmpty()) {
            appendLine("## Installed plugins")
            enabledPlugins.forEach { plugin ->
                appendLine("- ${plugin.name} ${plugin.version} by ${plugin.author.ifBlank { "unknown" }}")
            }
            appendLine()
        }

        if (input.mcpSummary.isNotBlank()) {
            appendLine("## MCP servers and tools")
            appendLine(input.mcpSummary)
            appendLine()
        }

        appendLine("## Tool protocol")
        appendLine("- Tools are called through the provider's native function-calling interface.")
        appendLine("- Tool results are fed back to you as tool messages; read them carefully before continuing.")
        appendLine("- Advertised tools currently available: " +
            input.tools.joinToString(", ") { it.name }.ifBlank { "none" } + ".")
        appendLine("- If a needed tool is not available, explain the gap instead of pretending.")
    }

    private fun estimate(text: String): Int = (text.length / 4) + 8

    private fun estimate(message: ChatMessage): Int =
        (message.text.length / 4) + (message.assistantToolCalls.sumOf { it.argumentsJson.length / 4 }) + 12
}