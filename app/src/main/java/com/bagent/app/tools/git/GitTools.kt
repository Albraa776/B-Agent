package com.bagent.app.tools.git

import com.bagent.app.core.model.PermAction
import com.bagent.app.core.model.RiskLevel
import com.bagent.app.core.util.JsonUtil
import com.bagent.app.core.util.Redactor
import com.bagent.app.tools.ToolBase
import com.bagent.app.tools.ToolEnv
import com.bagent.app.tools.ToolResult
import com.bagent.app.tools.terminal.CommandRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Git tools executed through the environment's git binary (native or Termux).
 * Health checks honestly report when git is not available.
 */
class GitTools {

    fun all(): List<ToolBase> = listOf(
        GitStatus(), GitDiff(), GitLog(), GitBranch(), GitAdd(), GitCommit(),
        GitCheckout(), GitRestore(), GitShow(), GitGrep(), GitStash(), GitRemote()
    )

    private fun cwd(env: ToolEnv): String? = env.workspace?.let {
        com.bagent.app.tools.filesystem.FsService(env.context).workspaceDir(it).absolutePath
    } ?: runCatching { env.context.filesDir.absolutePath }.getOrNull()

    private fun gitAvailable(env: ToolEnv): Boolean =
        runCatching { env.terminal.run(CommandRequest("git", listOf("--version"), timeoutMs = 15_000)).success }.getOrDefault(false)

    private fun runGit(env: ToolEnv, args: List<String>, timeoutMs: Long = 60_000): ToolResult {
        if (!gitAvailable(env)) return ToolResult.Failure("git is not available in this environment")
        val cwd = cwd(env)
        val result = env.terminal.run(CommandRequest("git", args, cwd = cwd, timeoutMs = timeoutMs))
        return if (result.success) {
            ToolResult.Success(Redactor.redact(result.stdout.ifBlank { result.stderr }))
        } else {
            val msg = if (result.error.isNotBlank()) result.error else result.stderr.ifBlank { "git failed (exit ${result.exitCode})" }
            ToolResult.Failure(Redactor.redact(msg))
        }
    }

    private fun genericGitTool(
        id: String,
        desc: String,
        perm: PermAction = PermAction.EXECUTE_COMMANDS,
        risk: RiskLevel = RiskLevel.MEDIUM,
        buildArgs: (JsonObject) -> List<String>
    ): ToolBase {
        return object : ToolBase() {
            override val id = id
            override val name = id
            override val category = "git"
            override val description = desc
            override val requiredPermission = perm
            override val risk = risk
            override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"repository":{"type":"string","description":"repository sub-path (default: workspace root)"},"args":{"type":"array","items":{"type":"string"},"description":"extra git arguments"}}}""")
            override fun checkHealth(env: ToolEnv): String = if (gitAvailable(env)) "ok" else "git binary not found in this environment"
            override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
                val repo = args["repository"]?.jsonPrimitive?.contentOrNull
                val extra = runCatching {
                    (args["args"]?.jsonPrimitive?.contentOrNull ?: "[]").let { JsonUtil.parseElement(it)?.let { e -> e.jsonArray.map { it.jsonPrimitive.contentOrNull ?: "" } } ?: emptyList() }
                }.getOrDefault(emptyList())
                return runGit(env, buildArgs(args) + extra, timeoutMs = 120_000)
            }
        }
    }

    inner class GitStatus : ToolBase() {
        override val id = "git_status"
        override val name = "git_status"
        override val category = "git"
        override val description = "Show the current repository status of the workspace."
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"porcelain":{"type":"boolean"}}}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val porcelain = args["porcelain"]?.jsonPrimitive?.contentOrNull == "true"
            return runGit(env, if (porcelain) listOf("status", "--porcelain") else listOf("status"))
        }
    }

    inner class GitDiff : ToolBase() {
        override val id = "git_diff"
        override val name = "git_diff"
        override val category = "git"
        override val description = "Show unstaged or staged file diffs."
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"staged":{"type":"boolean"},"path":{"type":"string"}}}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val staged = args["staged"]?.jsonPrimitive?.contentOrNull == "true"
            val path = args["path"]?.jsonPrimitive?.contentOrNull
            val base = if (staged) listOf("diff", "--cached") else listOf("diff")
            return runGit(env, base + listOfNotNull(path), timeoutMs = 120_000)
        }
    }

    inner class GitLog : ToolBase() {
        override val id = "git_log"
        override val name = "git_log"
        override val category = "git"
        override val description = "Show commit history."
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"count":{"type":"integer"}}}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val count = args["count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 20
            return runGit(env, listOf("log", "--oneline", "-n", count.toString()))
        }
    }

    inner class GitBranch : ToolBase() {
        override val id = "git_branch"
        override val name = "git_branch"
        override val category = "git"
        override val description = "List branches, or create a new branch."
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"name":{"type":"string","description":"new branch name (creates it)"}}}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val name = args["name"]?.jsonPrimitive?.contentOrNull
            return runGit(env, if (name.isNullOrBlank()) listOf("branch") else listOf("branch", name))
        }
    }

    inner class GitAdd : ToolBase() {
        override val id = "git_add"
        override val name = "git_add"
        override val category = "git"
        override val description = "Stage files for commit."
        override val requiredPermission = PermAction.EXECUTE_COMMANDS
        override val risk = RiskLevel.MEDIUM
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"paths":{"type":"array","items":{"type":"string"}}},"required":["paths"]}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val paths = runCatching {
                (args["paths"]?.jsonPrimitive?.contentOrNull ?: "[]").let { JsonUtil.parseElement(it)?.let { e -> e.jsonArray.map { it.jsonPrimitive.contentOrNull ?: "" } } ?: emptyList() }
            }.getOrDefault(emptyList())
            if (paths.isEmpty()) return ToolResult.Failure("no paths given")
            return runGit(env, listOf("add") + paths)
        }
    }

    inner class GitCommit : ToolBase() {
        override val id = "git_commit"
        override val name = "git_commit"
        override val category = "git"
        override val description = "Create a commit with a message."
        override val requiredPermission = PermAction.EXECUTE_COMMANDS
        override val risk = RiskLevel.MEDIUM
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"message":{"type":"string"}},"required":["message"]}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val message = args["message"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Failure("missing message")
            return runGit(env, listOf("commit", "-m", message))
        }
    }

    inner class GitCheckout : ToolBase() {
        override val id = "git_checkout"
        override val name = "git_checkout"
        override val category = "git"
        override val description = "Switch branch or restore file(s)."
        override val requiredPermission = PermAction.EXECUTE_COMMANDS
        override val risk = RiskLevel.HIGH
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"ref":{"type":"string"},"files":{"type":"array","items":{"type":"string"}}}}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val ref = args["ref"]?.jsonPrimitive?.contentOrNull
            val files = runCatching {
                (args["files"]?.jsonPrimitive?.contentOrNull ?: "[]").let { JsonUtil.parseElement(it)?.let { e -> e.jsonArray.map { it.jsonPrimitive.contentOrNull ?: "" } } ?: emptyList() }
            }.getOrDefault(emptyList())
            return runGit(env, listOf("checkout") + listOfNotNull(ref) + files)
        }
    }

    inner class GitRestore : ToolBase() {
        override val id = "git_restore"
        override val name = "git_restore"
        override val category = "git"
        override val description = "Restore working tree files or staged state."
        override val requiredPermission = PermAction.EXECUTE_COMMANDS
        override val risk = RiskLevel.HIGH
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"staged":{"type":"boolean"},"path":{"type":"string","description":"file or directory to restore"}},"required":["path"]}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val staged = args["staged"]?.jsonPrimitive?.contentOrNull == "true"
            val path = args["path"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Failure("missing path")
            return runGit(env, listOf("restore") + if (staged) listOf("--staged", path) else listOf(path))
        }
    }

    inner class GitShow : ToolBase() {
        override val id = "git_show"
        override val name = "git_show"
        override val category = "git"
        override val description = "Show the contents or patch of an object (commit/blob)."
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"object":{"type":"string"}},"required":["object"]}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val obj = args["object"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Failure("missing object")
            return runGit(env, listOf("show", "--stat", "--oneline", obj))
        }
    }

    inner class GitGrep : ToolBase() {
        override val id = "git_grep"
        override val name = "git_grep"
        override val category = "git"
        override val description = "Search tracked files for content."
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"pattern":{"type":"string"}},"required":["pattern"]}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val pattern = args["pattern"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Failure("missing pattern")
            return runGit(env, listOf("grep", "-n", pattern))
        }
    }

    inner class GitStash : ToolBase() {
        override val id = "git_stash"
        override val name = "git_stash"
        override val category = "git"
        override val description = "Stash or pop workspace changes."
        override val requiredPermission = PermAction.EXECUTE_COMMANDS
        override val risk = RiskLevel.MEDIUM
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"action":{"type":"string","enum":["list","push","pop","drop"]}}}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "list"
            return runGit(env, listOf("stash", action))
        }
    }

    inner class GitRemote : ToolBase() {
        override val id = "git_remote"
        override val name = "git_remote"
        override val category = "git"
        override val description = "Show configured remotes."
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{}}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            return runGit(env, listOf("remote", "-v"))
        }
    }
}