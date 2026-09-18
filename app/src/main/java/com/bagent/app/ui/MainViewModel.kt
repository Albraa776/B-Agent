package com.bagent.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bagent.app.BAgentApp
import com.bagent.app.agent.RuntimeState
import com.bagent.app.agent.permissions.Approval
import com.bagent.app.core.database.MessageEntity
import com.bagent.app.core.platform.Diagnosis
import com.bagent.app.core.util.JsonUtil
import com.bagent.app.core.util.now
import com.bagent.app.core.model.ProviderConfig
import com.bagent.app.core.model.ProviderType
import com.bagent.app.core.model.Role
import com.bagent.app.core.model.PermAction
import com.bagent.app.core.database.McpServerEntity
import com.bagent.app.core.database.SkillEntity
import com.bagent.app.core.database.WorkspaceEntity
import com.bagent.app.tools.terminal.CommandRequest
import com.bagent.app.tools.terminal.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Single view model exposing the real app container to every screen. */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    val container get() = (getApplication<Application>() as BAgentApp).container

    val runtimeState: StateFlow<RuntimeState> = container.runtime.state

    val sessions = container.sessions.sessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tasks = container.tasks.tasks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val capabilities = container.environment.capabilities.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tools = container.registry.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val skills = container.skills.skills.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val plugins = container.plugins.plugins.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val mcpServers = container.mcp.servers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val logs = container.logger.recent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val workspaces = container.database.workspaceDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _providers = MutableStateFlow<List<ProviderConfig>>(emptyList())
    val providers: StateFlow<List<ProviderConfig>> = _providers.asStateFlow()

    private val _selectedSessionId = MutableStateFlow<Long?>(null)
    val selectedSessionId: StateFlow<Long?> = _selectedSessionId.asStateFlow()

    val messages: StateFlow<List<MessageEntity>> = _selectedSessionId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else container.sessions.messages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _diagnostics = MutableStateFlow<List<Diagnosis>>(emptyList())
    val diagnostics: StateFlow<List<Diagnosis>> = _diagnostics.asStateFlow()

    private val _diagnosticsRunning = MutableStateFlow(false)
    val diagnosticsRunning: StateFlow<Boolean> = _diagnosticsRunning.asStateFlow()

    private val _providerTestResult = MutableStateFlow<String?>(null)
    val providerTestResult: StateFlow<String?> = _providerTestResult.asStateFlow()

    private val _terminalOutput = MutableStateFlow<List<String>>(emptyList())
    val terminalOutput: StateFlow<List<String>> = _terminalOutput.asStateFlow()

    private val _terminalHistory = MutableStateFlow<List<String>>(emptyList())
    val terminalHistory: StateFlow<List<String>> = _terminalHistory.asStateFlow()

    private val _terminalCwd = MutableStateFlow<String?>(null)
    val terminalCwd: StateFlow<String?> = _terminalCwd.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        refreshProviders()
        viewModelScope.launch {
            val recent = container.sessions.recent.first()
            _selectedSessionId.value = recent.firstOrNull()?.id
        }
    }

    fun refreshProviders() {
        viewModelScope.launch { _providers.value = container.providers.configs() }
    }

    fun selectSession(id: Long?) {
        _selectedSessionId.value = id
    }

    fun newSession(title: String = "New task") {
        viewModelScope.launch {
            val workspace = container.database.workspaceDao().observeLatest().first()
            val provider = container.providers.defaultConfig()
            val session = container.sessions.create(
                title = title,
                workspaceId = workspace?.id,
                providerId = provider?.id,
                model = provider?.model ?: ""
            )
            _selectedSessionId.value = session.id
        }
    }

    fun deleteSession(id: Long) {
        viewModelScope.launch {
            container.sessions.delete(id)
            if (_selectedSessionId.value == id) {
                _selectedSessionId.value = container.sessions.recent.first().firstOrNull()?.id
            }
        }
    }

    fun sendPrompt(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            var sessionId = _selectedSessionId.value
            if (sessionId == null) {
                val workspace = container.database.workspaceDao().observeLatest().first()
                val provider = container.providers.defaultConfig()
                val session = container.sessions.create(
                    title = trimmed.lineSequence().first().take(48),
                    workspaceId = workspace?.id,
                    providerId = provider?.id,
                    model = provider?.model ?: ""
                )
                sessionId = session.id
                _selectedSessionId.value = session.id
            }
            com.bagent.app.service.AgentForegroundService.start(getApplication())
            container.runtime.send(sessionId!!, trimmed)
        }
    }

    fun stopAgent() = container.runtime.stop()

    fun answerPermission(approval: Approval) = container.runtime.answerPermission(approval)

    fun dismissTerminalState() = container.runtime.dismissTerminalState()

    // ---- Providers ----

    fun saveProvider(config: ProviderConfig, onDone: (String) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { container.providers.save(config) }
                .onSuccess {
                    refreshProviders()
                    onDone("Saved")
                }
                .onFailure { onDone("Failed: ${it.message}") }
        }
    }

    fun newProviderTemplate(): ProviderConfig = ProviderConfig(
        id = 0L,
        name = "OpenAI",
        type = ProviderType.OPENAI,
        baseUrl = "https://api.openai.com",
        model = "gpt-4o-mini",
        apiKey = "",
        headersJson = JsonUtil.parseObject("{}")!!,
        contextLimit = 128_000,
        timeoutSec = 120,
        stream = true,
        maxRetries = 2,
        enabled = true,
        isDefault = _providers.value.isEmpty(),
        reasoning = ""
    )

    fun deleteProvider(id: Long) {
        viewModelScope.launch {
            container.providers.delete(id)
            refreshProviders()
        }
    }

    fun setDefaultProvider(id: Long) {
        viewModelScope.launch {
            container.providers.setDefault(id)
            refreshProviders()
        }
    }

    fun setSessionProvider(sessionId: Long, providerId: Long, model: String) {
        viewModelScope.launch {
            val session = container.sessions.session(sessionId) ?: return@launch
            container.database.sessionDao().upsert(
                session.copy(
                    providerId = providerId,
                    model = model,
                    updatedAt = now()
                )
            )
        }
    }

    fun testProvider(id: Long) {
        val config = container.providers.config(id) ?: return
        viewModelScope.launch {
            _busy.value = true
            _providerTestResult.value = "Testing ${config.name}…"
            _providerTestResult.value = runCatching { container.providers.test(config) }
                .fold({ it }, { "Failed: ${it.message}" })
            _busy.value = false
        }
    }

    fun clearProviderTestResult() {
        _providerTestResult.value = null
    }

    // ---- Diagnostics ----

    fun runDiagnostics() {
        if (_diagnosticsRunning.value) return
        viewModelScope.launch {
            _diagnosticsRunning.value = true
            _diagnostics.value = runCatching { container.diagnostics.run() }
                .getOrElse {
                    listOf(
                        Diagnosis(
                            "internal", "Diagnostics engine", Diagnosis.Status.FAIL,
                            it.message ?: "crashed", "Report this failure."
                        )
                    )
                }
            _diagnosticsRunning.value = false
            container.environment.scanNow()
        }
    }

    // ---- Terminal ----

    fun runTerminal(command: String, shell: Boolean) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _terminalHistory.value = (_terminalHistory.value + trimmed).takeLast(100)
            _terminalOutput.value = _terminalOutput.value + "$ ${trimmed}"
            _busy.value = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    container.terminal.run(
                        CommandRequest(
                            command = trimmed,
                            cwd = _terminalCwd.value,
                            shellInterpret = shell,
                            timeoutMs = 120_000
                        )
                    )
                }.getOrElse {
                    CommandResult(null, "", it.message ?: "failed", now(), 0L, error = it.message ?: "failed")
                }
            }
            val block = buildString {
                if (result.stdout.isNotBlank()) appendLine(result.stdout.trimEnd())
                if (result.stderr.isNotBlank()) appendLine(result.stderr.trimEnd())
                if (result.stdout.isBlank() && result.stderr.isBlank() && result.error.isNotBlank()) {
                    appendLine(result.error)
                }
                appendLine("[exit ${result.exitCode ?: "-"} • ${result.durationMs}ms • ${container.terminal.currentBackend().name}]")
            }
            _terminalOutput.value = _terminalOutput.value + block
            _busy.value = false
        }
    }

    fun setTerminalCwd(path: String) {
        _terminalCwd.value = path.ifBlank { null }
    }

    fun clearTerminal() {
        _terminalOutput.value = emptyList()
    }

    fun stopAllProcesses() {
        container.terminal.stopAll()
    }

    // ---- Workspaces ----

    fun createWorkspace(name: String, path: String) {
        viewModelScope.launch {
            val stamp = now()
            container.database.workspaceDao().upsert(
                WorkspaceEntity(
                    name = name.ifBlank { "Workspace" },
                    rootUriOrPath = path,
                    createdAt = stamp,
                    updatedAt = stamp
                )
            )
        }
    }

    fun deleteWorkspace(id: Long) {
        viewModelScope.launch { container.database.workspaceDao().delete(id) }
    }

    fun setDefaultWorkspace(id: Long) {
        viewModelScope.launch { container.settings.setDefaultWorkspace(id) }
    }

    // ---- Skills ----

    fun installSkillMarkdown(markdown: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { container.skills.installFromMarkdown(markdown) }
                .onSuccess { onResult("Installed skill: ${it.name}") }
                .onFailure { onResult("Failed: ${it.message}") }
        }
    }

    fun installSkillFromUrl(url: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { container.skills.installFromUrl(url) }
                .onSuccess { onResult("Installed skill: ${it.name}") }
                .onFailure { onResult("Failed: ${it.message}") }
        }
    }

    fun toggleSkill(skill: SkillEntity) {
        viewModelScope.launch { container.skills.setEnabled(skill.id, !skill.enabled) }
    }

    fun removeSkill(id: String) {
        viewModelScope.launch { container.skills.uninstall(id) }
    }

    // ---- Plugins ----

    fun installPluginJson(json: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { container.plugins.installFromJson(json) }
                .onSuccess { onResult("Installed plugin: ${it.name}") }
                .onFailure { onResult("Failed: ${it.message}") }
        }
    }

    fun togglePlugin(id: String, enabled: Boolean) {
        viewModelScope.launch { container.plugins.setEnabled(id, enabled) }
    }

    fun removePlugin(id: String) {
        viewModelScope.launch { container.plugins.uninstall(id) }
    }

    // ---- MCP ----

    fun addMcpServer(name: String, url: String, token: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val id = name.ifBlank { url }.lowercase().replace(Regex("[^a-z0-9]+"), "-")
            val entity = McpServerEntity(
                id = id,
                name = name.ifBlank { url },
                url = url,
                enabled = true,
                authJson = if (token.isBlank()) "{}" else """{"Authorization":"Bearer $token"}""",
                installedAt = now()
            )
            runCatching {
                container.mcp.upsert(entity)
                container.mcp.connect(entity)
            }.onSuccess { onResult("Connected: $it") }
                .onFailure { onResult("Saved, connection failed: ${it.message}") }
        }
    }

    fun connectMcp(id: String) {
        viewModelScope.launch {
            val server = container.mcp.enabled().firstOrNull { it.id == id } ?: return@launch
            runCatching { container.mcp.connect(server) }
        }
    }

    fun removeMcp(id: String) {
        viewModelScope.launch { container.mcp.delete(id) }
    }

    // ---- Settings ----

    fun setAutoApprove(action: PermAction, enabled: Boolean) {
        viewModelScope.launch { container.settings.setAutoApproveFor(action, enabled) }
    }

    fun setLanguage(value: String) {
        viewModelScope.launch { container.settings.setLanguage(value) }
    }

    fun setProviderFallback(enabled: Boolean) {
        viewModelScope.launch { container.settings.setProviderFallback(enabled) }
    }

    fun setContextBudget(value: Int) {
        viewModelScope.launch { container.settings.setContextBudget(value) }
    }

    fun setMaxIterations(value: Int) {
        viewModelScope.launch { container.settings.setMaxIterations(value) }
    }

    fun scanEnvironment() {
        container.environment.scanNow()
    }
}

/** Small helper used by screens that render role labels. */
fun MessageEntity.roleLabel(): String = when (role) {
    Role.USER.name -> "You"
    Role.ASSISTANT.name -> "B Agent"
    Role.TOOL.name -> toolName ?: "tool"
    else -> role
}