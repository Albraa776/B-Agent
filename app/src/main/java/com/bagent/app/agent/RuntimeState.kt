package com.bagent.app.agent

import com.bagent.app.core.model.AgentState
import com.bagent.app.core.model.PermAction
import com.bagent.app.core.model.RiskLevel

/** One step of a task plan. */
data class PlanStep(
    val index: Int,
    val description: String,
    val status: String = "pending"
)

/** An unresolved permission request surfaced to the UI for a decision. */
data class PendingPermission(
    val requestId: Long,
    val toolName: String,
    val action: PermAction,
    val risk: RiskLevel,
    val reason: String
)

/** Full, observable agent runtime state. Every field reflects real activity. */
data class RuntimeState(
    val state: AgentState = AgentState.IDLE,
    val sessionId: Long? = null,
    val taskId: Long? = null,
    val iteration: Int = 0,
    val maxIterations: Int = 100,
    val plan: List<PlanStep> = emptyList(),
    val streamingText: String = "",
    val reasoningText: String = "",
    val currentTool: String? = null,
    val pendingPermission: PendingPermission? = null,
    val lastError: String = "",
    val queuedPrompts: List<String> = emptyList(),
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val tokensIn: Long = 0L,
    val tokensOut: Long = 0L
) {
    val isRunning: Boolean
        get() = state in setOf(
            AgentState.THINKING, AgentState.PLANNING, AgentState.EXECUTING_TOOL,
            AgentState.WAITING_FOR_PROCESS, AgentState.OBSERVING, AgentState.RECOVERING,
            AgentState.WAITING_FOR_PERMISSION
        )
}