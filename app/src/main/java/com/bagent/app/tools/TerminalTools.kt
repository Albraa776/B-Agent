package com.bagent.app.tools

import android.content.Context
import com.bagent.app.core.model.PermAction
import com.bagent.app.core.model.RiskLevel
import com.bagent.app.core.util.JsonUtil
import com.bagent.app.core.util.Redactor
import com.bagent.app.tools.terminal.CommandRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Real command execution, process inspection and binary detection tools. */
class TerminalTools(context: Context) {

    fun all(): List<ToolBase> = listOf(
        RunCommand(context), ListProcesses(), StopProcess(), CheckBinary()
    )

    inner class RunCommand(private val context: Context) : ToolBase() {
        override val id = "run_command"
        override val name = "run_command"
        override val category = "terminal"
        override val description = "Execute a real shell command in the workspace or Android environment (via the strongest available backend)."
        override val requiredPermission = PermAction.EXECUTE_COMMANDS
        override val risk = RiskLevel.HIGH
        override val timeoutMs = Long.MAX_VALUE
        override val parameters = JsonUtil.parseObject(
            """{"type":"object","properties":{"command":{"type":"string","description":"command and arguments"},"cwd":{"type":"string","description":"working directory (default: workspace root)"},"timeoutMs":{"type":"integer"},"shell":{"type":"boolean","description":"interpret via shell (default true)"}},"required":["command"]}"""
        )

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val command = args["command"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Failure("missing command")
            val cwd = args["cwd"]?.jsonPrimitive?.contentOrNull
            val timeout = (args["timeoutMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: env.settings.commandTimeoutSec.run { kotlinx.coroutines.flow.first { it } * 1000L }).coerceAtLeast(5_000L)
            val shell = args["shell"]?.jsonPrimitive?.contentOrNull != "false"
            val resolvedCwd = cwd?.takeIf { it.isNotBlank() } ?: env.workspace?.let { ws ->
                com.bagent.app.tools.filesystem.FsService(context).workspaceDir(ws).absolutePath
            }
            val result = env.terminal.run(
                CommandRequest(command, emptyList(), cwd = resolvedCwd, timeoutMs = timeout, shellInterpret = shell)
            )
            val output = result.combined
            return if (result.success) {
                ToolResult.Success(buildJsonObject {
                    put("exit", result.exitCode)
                    put("pid", result.pid)
                    put("durationMs", result.durationMs)
                    put("stdout", result.stdout.take(40_000))
                    put("stderr", result.stderr.take(10_000))
                    put("timedOut", false)
                }.toString())
            } else {
                ToolResult.Failure(buildJsonObject {
                    put("exit", result.exitCode)
                    put("error", result.error.take(2000))
                    put("stderr", result.stderr.take(8000))
                    put("stdout", result.stdout.take(8000))
                    put("timedOut", result.timedOut)
                }.toString())
            }
        }
    }

    inner class ListProcesses : ToolBase() {
        override val id = "list_processes"
        override val name = "list_processes"
        override val category = "terminal"
        override val description = "List processes started through B Agent's terminal backends."
        override val risk = RiskLevel.LOW
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{}}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val running = env.terminal.running.value
            return ToolResult.Success(buildJsonObject {
                put("count", running.size)
                put("processes", buildJsonArray {
                    running.forEach { p ->
                        add(buildJsonObject {
                            put("id", p.id)
                            put("pid", p.pid)
                            put("command", Redactor.redact(p.command))
                            put("startedAt", p.startedAt)
                            put("alive", p.isAlive)
                        })
                    }
                })
            }.toString())
        }
    }

    inner class StopProcess : ToolBase() {
        override val id = "stop_process"
        override val name = "stop_process"
        override val category = "terminal"
        override val description = "Stop a process started through B Agent by its id."
        override val requiredPermission = PermAction.PROCESS_CONTROL
        override val risk = RiskLevel.HIGH
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val id = args["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return ToolResult.Failure("missing id")
            env.terminal.stopProcess(id)
            return ToolResult.Success("requested stop for process $id")
        }
    }

    inner class CheckBinary : ToolBase() {
        override val id = "check_binary"
        override val name = "check_binary"
        override val category = "terminal"
        override val description = "Check whether an executable (git, python, node, ffmpeg…) is available in the environment."
        override val risk = RiskLevel.LOW
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val name = args["name"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Failure("missing name")
            val result = env.terminal.run(CommandRequest("sh", listOf("-c", "command -v $name || which $name"), timeoutMs = 15_000, shellInterpret = true))
            val found = result.success && result.stdout.isNotBlank()
            return ToolResult.Success(buildJsonObject {
                put("name", name)
                put("found", found)
                put("path", result.stdout.take(300))
                put("error", result.error)
            }.toString())
        }
    }
}