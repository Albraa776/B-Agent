package com.bagent.app.tools.terminal

import android.content.Context
import android.content.pm.PackageManager
import com.bagent.app.core.util.now
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Optional Termux integration. Uses Termux binaries when the runtime directory
 * is reachable; otherwise honestly reports that Termux exists but is not
 * accessible, so the user can enable the integration in Termux settings.
 */
class TermuxBackend(private val context: Context) : TerminalBackend {

    override val name: String = "termux"

    override fun priority(): Int = 50

    private fun termuxBinDir(): File = File("/data/data/com.termux/files/usr/bin")

    private fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo("com.termux", 0)
        true
    }.getOrDefault(false)

    override fun isAvailable(): Boolean {
        val bin = termuxBinDir()
        return isInstalled() && bin.isDirectory && bin.canExecute()
    }

    override fun describe(): String {
        if (!isInstalled()) return "Termux is not installed"
        return if (isAvailable()) "Termux runtime accessible at ${termuxBinDir().path}" else "Termux installed but its runtime is not reachable from this app"
    }

    override fun run(request: CommandRequest): CommandResult = withContext(Dispatchers.IO) {
        val started = now()
        if (!isAvailable()) {
            return@withContext CommandResult(null, "", "", started, 0L, error = describe())
        }
        val parts: List<String> = if (request.shellInterpret) {
            listOf(termuxBinDir().absolutePath + "/bash", "-c", (listOf(request.command) + request.args).joinToString(" "))
        } else {
            listOf(termuxBinDir().absolutePath + "/" + request.command) + request.args
        }
        try {
            val pb = ProcessBuilder(parts)
            pb.directory(request.cwd?.let { File(it) })
            val env = pb.environment()
            env["PATH"] = "${termuxBinDir().absolutePath}:/system/bin:/system/xbin:/vendor/bin"
            env["HOME"] = "/data/data/com.termux/files/home"
            env.putAll(request.env)
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
            CommandResult(null, "", "", started, now() - started, error = e.message ?: "termux launch failed")
        }
    }

    override fun start(request: CommandRequest): RunningProcess? {
        if (!isAvailable()) return null
        val parts: List<String> = if (request.shellInterpret) {
            listOf(termuxBinDir().absolutePath + "/bash", "-c", (listOf(request.command) + request.args).joinToString(" "))
        } else {
            listOf(termuxBinDir().absolutePath + "/" + request.command) + request.args
        }
        return try {
            val process = ProcessBuilder(parts).apply {
                directory(request.cwd?.let { File(it) })
                environment()["PATH"] = "${termuxBinDir().absolutePath}:/system/bin:/system/xbin:/vendor/bin"
            }.start()
            TermuxRunningProcess(process, request.command, request.cwd)
        } catch (e: Exception) {
            null
        }
    }

    private class TermuxRunningProcess(
        private val process: Process,
        override val command: String,
        override val cwd: String?
    ) : RunningProcess {
        override val id: Long = TID.nextAndGet()
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
        companion object { private val TID = AtomicLong(1L) }
    }
}