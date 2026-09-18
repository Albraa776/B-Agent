package com.bagent.app.tools

import com.bagent.app.agent.permissions.Authorization
import com.bagent.app.agent.permissions.PermissionManager
import com.bagent.app.core.database.ToolExecutionDao
import com.bagent.app.core.database.ToolExecutionEntity
import com.bagent.app.core.logging.BLogger
import com.bagent.app.core.model.ToolCallRequest
import com.bagent.app.core.util.JsonUtil
import com.bagent.app.core.util.Preview
import com.bagent.app.core.util.now
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Executes validated, permitted tool calls. Malformed arguments are rejected
 * (and surfaced back to the model for correction), dangerous actions require
 * authorization, and every call is timed, logged and persisted.
 */
class ToolExecutor(
    private val registry: ToolRegistry,
    private val permissions: PermissionManager,
    private val executionsDao: ToolExecutionDao,
    private val logger: BLogger
) {
    suspend fun execute(call: ToolCallRequest, env: ToolEnv): ToolResult {
        val tool = registry.resolve(call.name)
            ?: return ToolResult.Failure("unknown tool: ${call.name}")

        val args = parseArguments(call.argumentsJson)
            ?: return recordFailure(tool, env, call.argumentsJson, "invalid JSON arguments for ${call.name}")

        validate(tool, args)?.let { reason ->
            return recordFailure(tool, env, call.argumentsJson, reason, status = "rejected")
        }

        val auth = permissions.authorize(tool.requiredPermission, tool.description)
        if (auth is Authorization.Denied) {
            return recordFailure(tool, env, call.argumentsJson, "permission denied: ${tool.requiredPermission.name}", status = "denied", approval = "denied")
        }
        val grantedLevel = (auth as? Authorization.Granted)?.level ?: "automatic"

        val config = registry.configurationOf(tool.id)
        val scopedEnv = ToolEnv(
            env.context, env.terminal, env.settings, env.logger, env.scope,
            env.workspace, env.sessionId, env.taskId, config
        )

        val started = now()
        val result = try {
            withTimeout(tool.timeoutMs) { tool.execute(args, scopedEnv) }
        } catch (e: TimeoutCancellationException) {
            ToolResult.Failure("tool ${tool.name} timed out after ${tool.timeoutMs}ms")
        } catch (e: Exception) {
            ToolResult.Failure("tool ${tool.name} crashed: ${e.message ?: e::class.simpleName}")
        }
        val durationMs = now() - started

        record(tool, env, call.argumentsJson, result, durationMs, grantedLevel)
        return result
    }

    private fun recordFailure(
        tool: ToolBase,
        env: ToolEnv,
        arguments: String,
        reason: String,
        status: String = "failed",
        approval: String = "automatic"
    ): ToolResult {
        record(tool, env, arguments, ToolResult.Failure(reason), 0L, approval)
        return ToolResult.Failure(reason)
    }

    private fun parseArguments(json: String): JsonObject? =
        JsonUtil.parseElement(json)?.jsonObject

    private fun validate(tool: ToolBase, args: JsonObject): String? {
        val schema = tool.parameters ?: return null
        val required: JsonArray = schema["required"]?.jsonArray ?: return null
        for (key in required) {
            val name = key.jsonPrimitive.contentOrNull ?: continue
            val value = args[name]
            if (value == null || (value is JsonPrimitive && value.contentOrNull?.isBlank() == true)) {
                return "missing required argument '$name' for ${tool.name}"
            }
        }
        return null
    }

    private suspend fun record(
        tool: ToolBase,
        env: ToolEnv,
        arguments: String,
        result: ToolResult,
        durationMs: Long,
        approval: String
    ) {
        val out = when (result) {
            is ToolResult.Success -> result.output
            is ToolResult.Failure -> result.error
        }
        val status = when (result) {
            is ToolResult.Success -> "ok"
            is ToolResult.Failure -> "failed"
        }
        logger.tool(
            toolId = tool.id,
            result = Preview.of(out, 500),
            durationMs = durationMs,
            status = status,
            approval = approval,
            sessionId = env.sessionId,
            taskId = env.taskId
        )
        runCatching {
            executionsDao.insert(
                ToolExecutionEntity(
                    toolId = tool.id,
                    sessionId = env.sessionId,
                    taskId = env.taskId,
                    arguments = Preview.of(arguments, 800),
                    resultSummary = Preview.of(out, 800),
                    durationMs = durationMs,
                    status = status,
                    error = if (result is ToolResult.Failure) result.error.take(500) else "",
                    approval = approval,
                    createdAt = now()
                )
            )
        }
    }
}