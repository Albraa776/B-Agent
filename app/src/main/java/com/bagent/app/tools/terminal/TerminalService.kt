package com.bagent.app.tools.terminal

import android.content.Context
import com.bagent.app.core.database.ProcessDao
import com.bagent.app.core.database.ProcessEntity
import com.bagent.app.core.logging.BLogger
import com.bagent.app.core.settings.SettingsRepository
import com.bagent.app.core.util.Redactor
import com.bagent.app.core.util.now
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/** Info about a tracked process shown in the UI. */
data class RunningProcessInfo(
    val handle: RunningProcess
) {
    val id: Long get() = handle.id
    val command: String get() = handle.command
    val pid: Long? get() = handle.pid
    val startedAt: Long get() = handle.startedAt
    val isAlive: Boolean get() = handle.isAlive
}

/**
 * Selects and exposes the strongest available terminal backend
 * (root > termux > native) and tracks running processes.
 */
class TerminalService(
    private val context: Context,
    private val settings: SettingsRepository,
    private val processDao: ProcessDao,
    private val logger: BLogger,
    private val scope: CoroutineScope
) {
    private val native = NativeProcessBackend(context)
    private val termux = TermuxBackend(context)
    private val root = RootBackend(isEnabled = { settings.rootConfigured.first() })

    private val _running = MutableStateFlow<List<RunningProcessInfo>>(emptyList())
    val running: StateFlow<List<RunningProcessInfo>> = _running

    private val _selected = MutableStateFlow<TerminalBackend>(native)
    val selected: Flow<TerminalBackend> = _selected

    suspend fun refreshSelection() {
        val rootConfigured = settings.rootConfigured.firstOrNull() ?: false
        val candidate = when {
            root.isAvailable() && rootConfigured -> root
            termux.isAvailable() -> termux
            else -> native
        }
        _selected.value = candidate
    }

    fun currentBackend(): TerminalBackend = _selected.value

    suspend fun run(request: CommandRequest): CommandResult {
        refreshSelection()
        val backend = _selected.value
        var result = backend.run(request)
        logger.tool(
            toolId = "terminal.${backend.name}",
            result = if (result.success) "exit ${result.exitCode}: ${Redactor.redact(result.stdout.take(300))}"
            else "exit ${result.exitCode}: ${Redactor.redact(result.combined.take(300))}",
            durationMs = result.durationMs,
            status = if (result.success) "ok" else "failed",
            error = result.error
        )
        if (result.pid != null) persist(result, backend.name)
        return result
    }

    suspend fun start(request: CommandRequest): RunningProcess? {
        refreshSelection()
        val backend = _selected.value
        val handle = backend.start(request) ?: return null
        val info = RunningProcessInfo(handle)
        scope.launch {
            persistEntity(
                ProcessEntity(
                    pid = handle.pid ?: -1L,
                    command = Redactor.redact(handle.command).take(300),
                    cwd = handle.cwd ?: "",
                    status = "running",
                    startedAt = handle.startedAt
                )
            )
        }
        _running.value = _running.value + info
        return handle
    }

    fun stopProcess(id: Long) {
        _running.value.firstOrNull { it.id == id }?.let { info ->
            info.handle.stop()
        }
        _running.value = _running.value.filterNot { it.id == id }
    }

    fun stopAll() {
        _running.value.forEach { it.handle.stop() }
        _running.value = emptyList()
    }

    private fun persist(result: CommandResult, backendName: String) {
        scope.launch {
            persistEntity(
                ProcessEntity(
                    pid = result.pid ?: -1L,
                    command = Redactor.redact((result.stdout.take(80))).let { if (it.isBlank()) backendName else it }.take(300),
                    cwd = "",
                    status = if (result.success) "finished" else "failed",
                    startedAt = result.startTime,
                    exitCode = result.exitCode,
                    durationMs = result.durationMs
                )
            )
        }
    }

    private suspend fun persistEntity(entity: ProcessEntity) {
        runCatching { processDao.upsert(entity) }
    }
}