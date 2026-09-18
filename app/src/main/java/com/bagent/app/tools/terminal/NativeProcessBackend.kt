package com.bagent.app.tools.terminal

import android.content.Context
import com.bagent.app.core.util.now
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Executes real processes through Android's native ProcessBuilder. The PATH is
 * composed from the standard Android binary directories plus Termux's usr/bin
 * when it is reachable, so toybox utilities (ls, cat, grep, tar, ...) work.
 */
class NativeProcessBackend(private val context: Context) : TerminalBackend {

    override val name: String = "native"
    override fun priority(): Int = 10
    override fun isAvailable(): Boolean = true

    override fun describe(): String = "Native Android process execution via ProcessBuilder"

    override fun run(request: CommandRequest): CommandResult = withContext(Dispatchers.IO) {
        val started = now()
        val pid = AtomicLong(-1L)
        val outputBuffer = StringBuilder()
        val errorBuffer = StringBuilder()
        val done = AtomicBoolean(false)

        try {
            val pb = buildProcess(request)
            val process = pb.start()
            pid.set(pidOf(process))

            val stdoutThread = Thread {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(outputBuffer) {
                        if (outputBuffer.length < MAX_CAPTURED) outputBuffer.append(line).append('\n')
                    }
                }
            }
            val stderrThread = Thread {
                process.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(errorBuffer) {
                        if (errorBuffer.length < MAX_CAPTURED) errorBuffer.append(line).append('\n')
                    }
                }
            }
            stdoutThread.start()
            stderrThread.start()

            val finished = process.waitFor(request.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
                done.set(false)
            } else {
                done.set(true)
            }
            stdoutThread.join(1_000)
            stderrThread.join(1_000)

            val exit = if (done.get()) process.exitValue() else null
            CommandResult(
                exitCode = exit,
                stdout = outputBuffer.toString().trim(),
                stderr = errorBuffer.toString().trim(),
                startTime = started,
                durationMs = now() - started,
                pid = if (pid.get() >= 0) pid.get() else null,
                timedOut = !done.get()
            )
        } catch (e: Exception) {
            CommandResult(
                exitCode = null,
                stdout = outputBuffer.toString().trim(),
                stderr = errorBuffer.toString().trim(),
                startTime = started,
                durationMs = now() - started,
                error = e.message ?: "process launch failed"
            )
        }
    }

    override fun start(request: CommandRequest): RunningProcess? {
        val pb = buildProcess(request)
        return try {
            val process = pb.start()
            NativeRunningProcess(
                ownerScope = null,
                process = process,
                processPid = pidOf(process),
                command = request.command,
                cwd = request.cwd
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun buildProcess(request: CommandRequest): ProcessBuilder {
        val parts: List<String> = if (request.shellInterpret) {
            listOf(DEFAULT_SHELL, "-c", (listOf(request.command) + request.args).joinToString(" "))
        } else {
            listOf(request.command) + request.args
        }
        val pb = ProcessBuilder(parts)
        val path = buildPath()
        pb.directory(request.cwd?.let { File(it) })
        val env = pb.environment()
        env["PATH"] = path
        env["HOME"] = context.filesDir.absolutePath
        env["TMPDIR"] = context.cacheDir.absolutePath
        env.putAll(request.env)
        return pb
    }

    private fun buildPath(): String {
        val dirs = mutableListOf(
            "${PREFIX_BIN}/bin", "/system/bin", "/system/xbin", "/vendor/bin", "/vendor/xbin"
        )
        // App-owned binaries (e.g. bundled sharp commands)
        dirs.add(File(context.filesDir, "bin").absolutePath)
        return dirs.distinct().filter { File(it).isDirectory }.joinToString(":")
    }

    private fun pidOf(process: Process): Long =
        try {
            runCatching { process.pid().toLong() }.getOrDefault(-1L)
        } catch (e: Exception) {
            -1L
        }

    companion object {
        private const val DEFAULT_SHELL = "/system/bin/sh"
        private const val PREFIX_BIN = "/data/data/com.termux/files/usr"
        private const val MAX_CAPTURED = 2 * 1024 * 1024
    }

    private class NativeRunningProcess(
        private val ownerScope: CoroutineScope?,
        private val process: Process,
        processPid: Long,
        override val command: String,
        override val cwd: String?
    ) : RunningProcess {
        override val id: Long = NET.nextAndGet()
        override val startedAt: Long = now()
        override val pid: Long = if (processPid >= 0) processPid else runCatching { process.pid().toLong() }.getOrDefault(-1L)
        override fun stop() {
            process.destroy()
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
        }

        private val emitter = MutableSharedFlow<String>(extraBufferCapacity = 512)
        override fun output(): Flow<String> = emitter

        private val alive = AtomicBoolean(true)
        override val isAlive: Boolean get() = alive.get()

        init {
            val pump = { stream: java.io.InputStream ->
                Thread {
                    runCatching {
                        stream.bufferedReader().forEachLine { line -> emitter.tryEmit(line) }
                    }
                }.start()
            }
            pump(process.inputStream)
            pump(process.errorStream)
            ownerScope?.launch(Dispatchers.IO) {
                process.waitFor()
                alive.set(false)
            }
        }

        override suspend fun await(): Int? = withContext(Dispatchers.IO) {
            val finished = process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)
            if (finished) process.exitValue() else {
                process.destroy()
                null
            }
        }

        companion object {
            private val NET = AtomicLong(1L)
        }
    }
}