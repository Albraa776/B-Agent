package com.bagent.app.tools

import android.content.Context
import com.bagent.app.core.database.WorkspaceEntity
import com.bagent.app.core.logging.BLogger
import com.bagent.app.core.model.PermAction
import com.bagent.app.core.model.RiskLevel
import com.bagent.app.core.settings.SettingsRepository
import com.bagent.app.tools.terminal.TerminalService
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

sealed class ToolResult {
    data class Success(val output: String, val truncated: Boolean = false) : ToolResult()
    data class Failure(val error: String) : ToolResult()
}

/** Services and context handed to a tool while it executes. */
class ToolEnv(
    val context: Context,
    val terminal: TerminalService,
    val settings: SettingsRepository,
    val logger: BLogger,
    val scope: CoroutineScope,
    val workspace: WorkspaceEntity?,
    val sessionId: Long?,
    val taskId: Long?,
    val toolConfig: JsonObject = buildJsonObject {}
)

/** A first-class, dynamically registerable agent tool. */
abstract class ToolBase {
    abstract val id: String
    abstract val name: String
    open val version: String = "1.0.0"
    abstract val category: String
    abstract val description: String

    open val risk: RiskLevel = RiskLevel.LOW
    open val requiredPermission: PermAction = PermAction.READ_FILES
    open val timeoutMs: Long = 60_000L
    open val parameters: JsonObject? = null
    open val backend: String = "builtin"

    /** Optional health probe so the capability map is honest. */
    open fun checkHealth(env: ToolEnv): String = "ok"

    abstract suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult
}