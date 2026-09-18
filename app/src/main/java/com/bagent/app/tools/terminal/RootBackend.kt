package com.bagent.app.tools.terminal

import com.bagent.app.core.util.now
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Optional root backend. Everything is gated behind the same permission engine;
 * root is only ever used when it was detected AND explicitly enabled by the user.
 */
class RootBackend(private val isEnabled: suspend () -> Boolean = { true }) : TerminalBackend {

    override val name: String = "root"

    override fun priority(): Int = 100

    override fun isAvailable(): Boolean = findSu() != null

    private fun findSu(): String? {
        val paths = listOf(
            "/system/xbin/su", "/system/bin/su", "/sbin/su",
            "/su/bin/su", "/data/local/bin/su", "/data/adb/magisk/su"
        )
        return paths.firstOrNull { File(it).isFile && File(it).canExecute() }
    }

    override fun describe(): String = "Root shell via ${findSu() ?: "su"}; requires user-enabled root operation"

    override fun run(request: CommandRequest): CommandResult = withContext(Dispatchers.IO) {
        val started = now()
        val su = findSu()
        if (su == null) {
            return@withContext CommandResult(null, "", "", started, 0L, error = "root backend unavailable")
        }
        if (!isEnabled()) {
            return@withContext CommandResult(null, "", "", started, 0L, error = "root operations are disabled by configuration")
        }
        val parts = listOf(su, "-c", (listOf(request.command) + request.args).joinToString(" "))
        try {
            val pb = ProcessBuilder(parts)
            pb.directory(request.cwd?.let { File(it) })
            pb.environment().putAll(request.env)
            val process = pb.start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val t1 = Thread { runCatching { process.inputStream.bufferedReader().forEachLine { if (stdout.length < 2_000_000) stdout.append(it).append('\n') } } }
            val t2 = Thread { runCatching { process.errorStream.bufferedReader().forEachLine { if (stderr.length < 2_000_000) stderr.append(it).append('\n') } } }
            t1.start(); t2.start()
            val finished = process.waitFor(request.timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            t1.join(1_000); t2.join(1_000)
            CommandResult(
                exitCode = if (finished) process.exitValue() else null,
                stdout = stdout.toString().trim(),
                stderr = stderr.toString().trim(),
                startTime = started,
                durationMs = now() - started,
                pid = runCatching { process.pid().toLong() }.getOrDefault(-1L).let { if (it > 0) it else null },
                timedOut = !finished
            )
        } catch (e: Exception) {
            CommandResult(null, "", "", started, now() - started, error = e.message ?: "root launch failed")
        }
    }

    override fun start(request: CommandRequest): RunningProcess? {
        val su = findSu()
        if (su == null) return null
        val parts = listOf(su, "-c", (listOf(request.command) + request.args).joinToString(" "))
        return try {
            val process = ProcessBuilder(parts).apply {
                directory(request.cwd?.let { File(it) })
                environment().putAll(request.env)
            }.start()
            RootRunningProcess(process, request.command, request.cwd)
        } catch (e: Exception) {
            null
        }
    }

    private class RootRunningProcess(
        private val process: Process,
        override val command: String,
        override val cwd: String?
    ) : RunningProcess {
        override val id: Long = RID.nextAndGet()
        override val startedAt: Long = now()
        override val pid: Long = runCatching { process.pid().toLong() }.getOrDefault(-1L)
        override fun stop() {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        private val emitter = MutableSharedFlow<String>(extraBufferCapacity = 512)
        override fun output(): Flow<String> = emitter
        private var aliveInternal = true
        override val isAlive: Boolean get() = aliveInternal
        init {
            fun pump(stream: java.io.InputStream) {
                Thread {
                    runCatching { stream.bufferedReader().forEachLine { line -> emitter.tryEmit(line) } }
                }.start()
            }
            pump(process.inputStream)
            pump(process.errorStream)
        }
        override suspend fun await(): Int? {
            aliveInternal = process.isAlive
            val finished = process.waitFor(5, TimeUnit.MINUTES)
            aliveInternal = process.isAlive
            return if (finished) process.exitValue() else null
        }
        companion object { private val RID = AtomicLong(1L) }
    }
}