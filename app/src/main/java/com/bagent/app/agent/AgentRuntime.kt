package com.bagent.app.agent

import com.bagent.app.agent.permissions.Approval
import com.bagent.app.agent.permissions.Authorization
import com.bagent.app.agent.permissions.PermissionManager
import com.bagent.app.core.database.PluginDao
import com.bagent.app.core.database.SkillDao
import com.bagent.app.core.database.UserRuleDao
import com.bagent.app.core.database.WorkspaceDao
import com.bagent.app.core.logging.BLogger
import com.bagent.app.core.model.AgentState
import com.bagent.app.core.model.ChatMessage
import com.bagent.app.core.model.ContentPart
import com.bagent.app.core.model.ProviderEvent
import com.bagent.app.core.model.ProviderRequest
import com.bagent.app.core.model.Role
import com.bagent.app.core.model.ToolCallRequest
import com.bagent.app.core.platform.EnvironmentManager
import com.bagent.app.core.settings.SettingsRepository
import com.bagent.app.core.util.now
import com.bagent.app.providers.ProviderManager
import com.bagent.app.tools.ToolEnv
import com.bagent.app.tools.ToolExecutor
import com.bagent.app.tools.ToolRegistry
import com.bagent.app.tools.ToolResult
import com.bagent.app.tools.terminal.TerminalService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * The autonomous agent loop. It is the single owner of runtime state: it plans,
 * calls the provider, executes tools (through the permission engine), feeds
 * results back, and finishes only when the model stops requesting tools or a
 * hard limit is reached.
 */
class AgentRuntime(
    private val appContext: android.content.Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val sessions: SessionManager,
    private val tasks: TaskManager,
    private val memory: MemoryManager,
    private val providers: ProviderManager,
    private val registry: ToolRegistry,
    private val executor: ToolExecutor,
    private val permissions: PermissionManager,
    private val environment: EnvironmentManager,
    private val logger: BLogger,
    private val terminal: TerminalService,
    private val skillsDao: SkillDao,
    private val pluginsDao: PluginDao,
    private val rulesDao: UserRuleDao,
    private val workspaceDao: WorkspaceDao
) {
    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private val contextBuilder = ContextBuilder(settings)
    private val planner = Planner(providers, settings)
    private val inputs = Channel<Input>(Channel.UNLIMITED)
    private val requestCounter = AtomicLong(0)
    private var pendingApproval: CompletableDeferred<Approval>? = null
    private var activeRun: Job? = null

    /** MCP (or other) extra context appended to the system prompt. */
    var mcpSummaryProvider: suspend () -> String = { "" }

    private data class Input(
        val sessionId: Long,
        val text: String,
        val images: List<ContentPart.Image>
    )

    init {
        permissions.setRequester { action, risk, reason ->
            requestApproval(action, risk, reason)
        }
        scope.launch {
            for (input in inputs) {
                val job = scope.launch { runOne(input) }
                activeRun = job
                job.join()
                activeRun = null
            }
        }
    }

    /** Queues a user prompt. If the agent is busy it is processed next. */
    fun send(sessionId: Long, text: String, images: List<ContentPart.Image> = emptyList()) {
        inputs.trySend(Input(sessionId, text, images))
        _state.update {
            if (it.isRunning) it.copy(queuedPrompts = it.queuedPrompts + text) else it
        }
    }

    /** Cancels the active run and clears queued prompts. */
    fun stop() {
        activeRun?.cancel()
        while (inputs.tryReceive().isSuccess) { /* drain */ }
        _state.update { it.copy(state = AgentState.CANCELLED, queuedPrompts = emptyList(), currentTool = null) }
    }

    fun answerPermission(approval: Approval) {
        pendingApproval?.complete(approval)
    }

    fun dismissTerminalState() {
        if (!_state.value.isRunning) _state.update { it.copy(state = AgentState.IDLE) }
    }

    private suspend fun requestApproval(
        action: com.bagent.app.core.model.PermAction,
        risk: com.bagent.app.core.model.RiskLevel,
        reason: String
    ): Approval {
        val requestId = requestCounter.incrementAndGet()
        val deferred = CompletableDeferred<Approval>()
        pendingApproval = deferred
        _state.update {
            it.copy(
                state = AgentState.WAITING_FOR_PERMISSION,
                pendingPermission = PendingPermission(requestId, action.name, action, risk, reason)
            )
        }
        val result = withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { deferred.await() } ?: Approval.DENY
        pendingApproval = null
        _state.update { it.copy(pendingPermission = null) }
        return result
    }

    private suspend fun runOne(input: Input) {
        val maxIterations = settings.maxIterations.first()
        val session = sessions.session(input.sessionId)
            ?: sessions.create("New task", null, null, "")
        val sessionId = session.id
        val task = tasks.create(sessionId, input.text.lineSequence().first().take(80), maxIterations)
        val taskId = task.id
        var sequence = 0
        var iteration = 0
        var finalText = ""

        _state.update {
            it.copy(
                state = AgentState.THINKING,
                sessionId = sessionId,
                taskId = taskId,
                iteration = 0,
                maxIterations = maxIterations,
                streamingText = "",
                reasoningText = "",
                lastError = "",
                currentTool = null,
                plan = emptyList(),
                startedAt = now(),
                updatedAt = now()
            )
        }

        sessions.addUserMessage(sessionId, input.text, input.images)
        tasks.addStep(taskId, sequence++, "User request: ${input.text.take(500)}")

        try {
            _state.update { it.copy(state = AgentState.PLANNING) }
            val plan = planner.plan(input.text, session.providerId, session.model)
            if (plan.isNotEmpty()) {
                tasks.setPlan(taskId, plan)
                _state.update { it.copy(plan = plan) }
                tasks.addStep(taskId, sequence++, "Plan: " + plan.joinToString(" | ") { it.description })
            }

            while (iteration < maxIterations) {
                iteration++
                _state.update {
                    it.copy(state = AgentState.THINKING, iteration = iteration, streamingText = "", reasoningText = "")
                }

                val built = buildContext(sessionId, session.workspaceId, session.providerId, session.model)
                val schemas = registry.activeSchemas()
                val request = ProviderRequest(
                    model = session.model,
                    messages = built.messages,
                    tools = schemas,
                    system = built.system,
                    temperature = null,
                    maxTokens = null
                )

                val accumulator = StreamAccumulator()
                providers.stream(request, session.providerId) { event ->
                    when (event) {
                        is ProviderEvent.Delta -> {
                            accumulator.text.append(event.text)
                            _state.update { it.copy(streamingText = accumulator.text.toString()) }
                        }
                        is ProviderEvent.Reasoning -> {
                            accumulator.reasoning.append(event.text)
                            _state.update { it.copy(reasoningText = accumulator.reasoning.toString()) }
                        }
                        is ProviderEvent.ToolCallDelta -> accumulator.onToolDelta(event)
                        is ProviderEvent.Error -> _state.update { it.copy(lastError = event.message) }
                        ProviderEvent.Done -> Unit
                    }
                }

                val assistantText = accumulator.text.toString().trim()
                val toolCalls = accumulator.toolCalls()
                sessions.addAssistantMessage(sessionId, assistantText, toolCalls)
                tasks.addStep(
                    taskId, sequence++,
                    if (assistantText.isBlank()) "Assistant requested ${toolCalls.size} tool call(s)"
                    else "Assistant: ${assistantText.take(400)}"
                )

                if (toolCalls.isEmpty()) {
                    finalText = assistantText
                    break
                }

                val workspace = session.workspaceId?.let { workspaceDao.byId(it) }
                for (call in toolCalls) {
                    _state.update { it.copy(state = AgentState.EXECUTING_TOOL, currentTool = call.name) }
                    tasks.addStep(
                        taskId, sequence++,
                        "Tool: ${call.name}",
                        toolId = call.name,
                        arguments = call.argumentsJson
                    )
                    val env = ToolEnv(
                        context = appContext,
                        terminal = terminal,
                        settings = settings,
                        logger = logger,
                        scope = scope,
                        workspace = workspace,
                        sessionId = sessionId,
                        taskId = taskId
                    )
                    val result = executor.execute(call, env)
                    val resultText = when (result) {
                        is ToolResult.Success -> result.output
                        is ToolResult.Failure -> "ERROR: ${result.error}"
                    }
                    val status = if (result is ToolResult.Success) "ok" else "failed"
                    sessions.addToolResult(sessionId, call.id, call.name, call.argumentsJson, resultText, status)
                    tasks.addStep(
                        taskId, sequence++,
                        "Result: ${call.name}",
                        toolId = call.name,
                        outcome = resultText.take(400),
                        status = status
                    )
                    _state.update { it.copy(currentTool = null) }
                }
            }

            if (finalText.isBlank() && iteration >= maxIterations) {
                finalText = "Stopped after reaching the maximum of $maxIterations iterations without a final answer."
            }
            tasks.finish(taskId, finalText)
            _state.update {
                it.copy(
                    state = AgentState.COMPLETED,
                    streamingText = finalText.ifBlank { it.streamingText },
                    currentTool = null,
                    updatedAt = now()
                )
            }
        } catch (e: CancellationException) {
            tasks.setState(taskId, AgentState.CANCELLED)
            tasks.addStep(taskId, sequence, "Run cancelled by user", status = "cancelled")
            logger.warn("run cancelled: task=$taskId", sessionId = sessionId, taskId = taskId)
            _state.update { it.copy(state = AgentState.CANCELLED, currentTool = null) }
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e::class.simpleName ?: "unknown error"
            tasks.finish(taskId, "", message)
            tasks.addStep(taskId, sequence, "Run failed: $message", status = "failed")
            logger.error("run failed: task=$taskId", err = message, sessionId = sessionId, taskId = taskId)
            _state.update {
                it.copy(state = AgentState.FAILED, lastError = message, currentTool = null, updatedAt = now())
            }
        }
    }

    private suspend fun buildContext(
        sessionId: Long,
        workspaceId: Long?,
        providerId: Long?,
        model: String
    ): BuiltContext {
        val workspace = workspaceId?.let { workspaceDao.byId(it) }
            ?: workspaceDao.observeLatest().first()
        val budget = settings.contextBudget.first()
        val history = sessions.history(sessionId)
        val envSummary = environment.capabilities.first()
            .joinToString("\n") { "- ${it.name}: ${if (it.available) "available" else "unavailable"} (${it.detail})" }
        val providerLimit = providerId?.let { providers.config(it)?.contextLimit } ?: 128_000
        return contextBuilder.build(
            ContextBuilder.Input(
                workspace = workspace,
                rules = rulesDao.allEnabled(),
                memory = workspace?.let { memory.all(it.id) } ?: emptyList(),
                skills = skillsDao.allEnabled(),
                plugins = pluginsDao.allEnabled(),
                environmentSummary = envSummary,
                mcpSummary = runCatching { mcpSummaryProvider() }.getOrDefault(""),
                history = history,
                tools = registry.activeSchemas(),
                language = settings.language.first(),
                budgetTokens = minOf(budget, providerLimit)
            )
        )
    }

    private class ToolCallBuilder {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
    }

    private class StreamAccumulator {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        private val builders = LinkedHashMap<Int, ToolCallBuilder>()

        fun onToolDelta(event: ProviderEvent.ToolCallDelta) {
            val builder = builders.getOrPut(event.index) { ToolCallBuilder() }
            if (event.id.isNotBlank()) builder.id = event.id
            if (event.name.isNotBlank()) builder.name = event.name
            builder.args.append(event.argsDelta)
        }

        fun toolCalls(): List<ToolCallRequest> = builders.values
            .filter { it.name.isNotBlank() }
            .map { builder ->
                ToolCallRequest(
                    id = builder.id.ifBlank { "call_${builder.name}_${builder.args.length}" },
                    name = builder.name,
                    argumentsJson = builder.args.toString().ifBlank { "{}" }
                )
            }
    }

    companion object {
        private const val PERMISSION_TIMEOUT_MS = 10 * 60 * 1000L
    }
}