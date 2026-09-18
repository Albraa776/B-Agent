package com.bagent.app

import android.app.Application
import android.os.Environment
import com.bagent.app.agent.AgentRuntime
import com.bagent.app.agent.MemoryManager
import com.bagent.app.agent.SessionManager
import com.bagent.app.agent.TaskManager
import com.bagent.app.agent.permissions.PermissionManager
import com.bagent.app.core.database.AppDatabase
import com.bagent.app.core.database.WorkspaceEntity
import com.bagent.app.core.logging.BLogger
import com.bagent.app.core.platform.DiagnosticsEngine
import com.bagent.app.core.platform.EnvironmentManager
import com.bagent.app.core.security.KeystoreCrypto
import com.bagent.app.core.settings.SettingsRepository
import com.bagent.app.core.util.now
import com.bagent.app.mcp.McpManager
import com.bagent.app.plugins.PluginManager
import com.bagent.app.providers.ProviderManager
import com.bagent.app.skills.SkillManager
import com.bagent.app.tools.ToolExecutor
import com.bagent.app.tools.ToolRegistry
import com.bagent.app.tools.accessibility.AccessibilityTools
import com.bagent.app.tools.android.AndroidTools
import com.bagent.app.tools.filesystem.FilesystemTools
import com.bagent.app.tools.filesystem.FsService
import com.bagent.app.tools.git.GitTools
import com.bagent.app.tools.network.NetworkTools
import com.bagent.app.tools.terminal.TerminalService
import com.bagent.app.tools.TerminalTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Manual dependency container. All services share one lifecycle scope. */
class AppContainer(private val app: Application) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.build(app) }
    val settings: SettingsRepository by lazy { SettingsRepository(app) }
    val crypto: KeystoreCrypto by lazy { KeystoreCrypto() }
    val logger: BLogger by lazy { BLogger(database.logEntryDao(), scope) }

    val permissions: PermissionManager by lazy { PermissionManager(settings) }
    val registry: ToolRegistry by lazy { ToolRegistry(database.toolDao(), scope) }
    val terminal: TerminalService by lazy {
        TerminalService(app, settings, database.processDao(), logger, scope)
    }
    val executor: ToolExecutor by lazy {
        ToolExecutor(registry, permissions, database.toolExecutionDao(), logger)
    }
    val environment: EnvironmentManager by lazy {
        EnvironmentManager(app, database.environmentCapabilityDao(), scope)
    }
    val providers: ProviderManager by lazy {
        ProviderManager(database.providerDao(), settings, crypto, logger)
    }
    val sessions: SessionManager by lazy {
        SessionManager(database.sessionDao(), database.messageDao())
    }
    val tasks: TaskManager by lazy {
        TaskManager(database.agentTaskDao(), database.agentStepDao(), database.checkpointDao())
    }
    val memory: MemoryManager by lazy { MemoryManager(database.projectMemoryDao()) }
    val mcp: McpManager by lazy { McpManager(database.mcpServerDao(), registry, logger) }
    val skills: SkillManager by lazy { SkillManager(database.skillDao(), registry) }
    val plugins: PluginManager by lazy {
        PluginManager(database.pluginDao(), database.extensionDependencyDao(), registry)
    }
    val diagnostics: DiagnosticsEngine by lazy {
        DiagnosticsEngine(app, terminal, environment, providers, crypto)
    }
    val runtime: AgentRuntime by lazy {
        AgentRuntime(
            appContext = app,
            scope = scope,
            settings = settings,
            sessions = sessions,
            tasks = tasks,
            memory = memory,
            providers = providers,
            registry = registry,
            executor = executor,
            permissions = permissions,
            environment = environment,
            logger = logger,
            terminal = terminal,
            skillsDao = database.skillDao(),
            pluginsDao = database.pluginDao(),
            rulesDao = database.userRuleDao(),
            workspaceDao = database.workspaceDao()
        )
    }

    init {
        scope.launch { bootstrap() }
    }

    private suspend fun bootstrap() {
        runCatching { registerBuiltInTools() }
        runCatching { terminal.refreshSelection() }
        runCatching { providers.refresh() }
        runCatching { providers.ensureSeeded() }
        runCatching { skills.ensureRegistered() }
        runCatching { ensureDefaultWorkspace() }
        runCatching { environment.scanOnce() }
        runCatching { plugins.loadEnabled() }
        runCatching { mcp.connectAll() }
        runtime.mcpSummaryProvider = { mcp.summary() }
    }

    private fun registerBuiltInTools() {
        val fs = FsService(app)
        val tools = buildList {
            addAll(FilesystemTools(fs).all())
            addAll(GitTools().all())
            addAll(NetworkTools().all())
            addAll(TerminalTools(app).all())
            addAll(AndroidTools(app).all())
            addAll(AccessibilityTools().all())
        }
        tools.forEach { registry.register(it) }
    }

    private suspend fun ensureDefaultWorkspace() {
        val existing = database.workspaceDao().observeLatest().first()
        if (existing != null) return
        val root = runCatching { Environment.getExternalStorageDirectory().absolutePath }
            .getOrElse { app.filesDir.absolutePath }
        val stamp = now()
        database.workspaceDao().upsert(
            WorkspaceEntity(
                name = "Default device storage",
                rootUriOrPath = root,
                projectType = "general",
                rules = "Prefer non-destructive changes. Never delete user data without confirmation.",
                createdAt = stamp,
                updatedAt = stamp
            )
        )
    }
}