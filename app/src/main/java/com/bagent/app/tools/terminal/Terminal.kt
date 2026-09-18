package com.bagent.app.tools.terminal

import kotlinx.coroutines.flow.Flow

/**
 * Best-effort process id lookup. Android does not expose `Process.pid()`,
 * so reflection is attempted and -1 is returned when unavailable.
 */
internal fun processPid(process: Process): Long = runCatching {
    val method = process.javaClass.getDeclaredMethod("pid").apply { isAccessible = true }
    (method.invoke(process) as? Long) ?: -1L
}.getOrDefault(-1L)

/** A command to execute. */
data class CommandRequest(
    val command: String,
    val args: List<String> = emptyList(),
    val cwd: String? = null,
    val env: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 120_000L,
    /** When true the command is interpreted by a shell (pipelines, &&, redirects). */
    val shellInterpret: Boolean = false
)

/** Result of a finished command. */
data class CommandResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val startTime: Long,
    val durationMs: Long,
    val pid: Long? = null,
    val timedOut: Boolean = false,
    val error: String = ""
) {
    val success: Boolean get() = exitCode == 0 && !timedOut && error.isEmpty()
    val combined: String get() = if (stdout.isBlank()) stderr else if (stderr.isBlank()) stdout else "$stdout\n$stderr"
}

/** A long-running process with streaming output. */
interface RunningProcess {
    val id: Long
    val pid: Long?
    val command: String
    val cwd: String?
    val startedAt: Long
    fun output(): Flow<String>
    fun stop()
    suspend fun await(): Int?
    val isAlive: Boolean
}

/**
 * A process execution backend. Android provides several: native Java process
 * execution, Termux runtimes, and root. The strongest available backend is
 * selected automatically and this selection is reported honestly.
 */
interface TerminalBackend {
    val name: String
    /** Higher is preferred: root > termux > native. */
    fun priority(): Int
    fun isAvailable(): Boolean
    fun describe(): String
    suspend fun run(request: CommandRequest): CommandResult
    fun start(request: CommandRequest): RunningProcess?
}