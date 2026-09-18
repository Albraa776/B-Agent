package com.bagent.app.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "kv_settings")
data class KvSettingEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long = 0L
)

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val type: String,
    val baseUrl: String,
    val model: String,
    val apiKeyEnc: String = "",
    val headersJson: String = "{}",
    val contextLimit: Int = 128000,
    val timeoutSec: Int = 120,
    val stream: Boolean = true,
    val maxRetries: Int = 2,
    val enabled: Boolean = true,
    val isDefault: Boolean = false,
    val reasoning: String = "",
    val createdAt: Long = 0L
)

@Entity(tableName = "workspaces")
data class WorkspaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val rootUriOrPath: String = "",
    val projectType: String = "general",
    val rules: String = "",
    val ignoredPaths: String = ".git,node_modules,.gradle,build,.idea,.venv,__pycache__",
    val preferredProviderId: Long? = null,
    val preferredModel: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val workspaceId: Long? = null,
    val providerId: Long? = null,
    val model: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

@Entity(tableName = "messages", indices = [Index(value = ["sessionId"])])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val role: String,
    val content: String = "",
    val partsJson: String = "[]",
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolCallsJson: String = "[]",
    val status: String = "ok",
    val createdAt: Long = 0L
)

@Entity(tableName = "agent_tasks", indices = [Index(value = ["sessionId"])])
data class AgentTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long?,
    val title: String,
    val state: String = "IDLE",
    val planJson: String = "[]",
    val currentStep: Int = 0,
    val maxIterations: Int = 100,
    val result: String = "",
    val error: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L
)

@Entity(tableName = "agent_steps", indices = [Index(value = ["taskId"])])
data class AgentStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val taskId: Long,
    val sequence: Int,
    val timestamp: Long,
    val text: String,
    val toolId: String = "",
    val arguments: String = "",
    val outcome: String = "",
    val status: String = "ok"
)

@Entity(tableName = "tools")
data class ToolEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val category: String,
    val version: String = "1.0.0",
    val enabled: Boolean = true,
    val installed: Boolean = true,
    val configurationJson: String = "{}",
    val permission: String = "READ_FILES",
    val risk: String = "LOW",
    val backend: String = "",
    val timeoutMs: Long = 60_000L,
    val metadataJson: String = "{}"
)

@Entity(tableName = "tool_executions")
data class ToolExecutionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val toolId: String,
    val sessionId: Long? = null,
    val taskId: Long? = null,
    val arguments: String = "",
    val resultSummary: String = "",
    val durationMs: Long = 0L,
    val status: String = "ok",
    val error: String = "",
    val approval: String = "automatic",
    val createdAt: Long = 0L
)

@Entity(tableName = "processes")
data class ProcessEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val pid: Long,
    val command: String,
    val cwd: String,
    val status: String = "running",
    val startedAt: Long = 0L,
    val exitCode: Int? = null,
    val durationMs: Long? = null
)

@Entity(tableName = "project_memory")
data class ProjectMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val workspaceId: Long,
    val namespace: String = "project",
    val key: String,
    val value: String,
    val updatedAt: Long = 0L
)

@Entity(tableName = "user_rules")
data class UserRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val rule: String,
    val scope: String = "global",
    val enabled: Boolean = true,
    val priority: Int = 100
)

@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val enabled: Boolean = true,
    val instructions: String = "",
    val permissionsJson: String = "[]",
    val configJson: String = "{}",
    val source: String = "",
    val installedAt: Long = 0L
)

@Entity(tableName = "plugins")
data class PluginEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String = "1.0.0",
    val author: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val manifestJson: String = "{}",
    val permissionsJson: String = "[]",
    val dependenciesJson: String = "[]",
    val providesJson: String = "[]",
    val source: String = "",
    val installedAt: Long = 0L
)

@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val authJson: String = "{}",
    val envJson: String = "{}",
    val configJson: String = "{}",
    val capabilitiesJson: String = "[]",
    val installedAt: Long = 0L
)

@Entity(tableName = "log_entries", indices = [Index(value = ["sessionId"]), Index(value = ["taskId"])])
data class LogEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long,
    val level: String = "info",
    val sessionId: Long? = null,
    val taskId: Long? = null,
    val toolId: String = "",
    val message: String,
    val durationMs: Long? = null,
    val status: String = "",
    val error: String = "",
    val approval: String = "",
    val redacted: Boolean = false
)

@Entity(tableName = "environment_capabilities")
data class EnvironmentCapabilityEntity(
    @PrimaryKey val name: String,
    val available: Boolean = false,
    val detail: String = "",
    val updatedAt: Long = 0L
)

@Entity(tableName = "checkpoints")
data class CheckpointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val taskId: Long,
    val createdAt: Long,
    val description: String = "",
    val dataJson: String = "{}"
)

@Entity(tableName = "extension_dependencies")
data class ExtensionDependencyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val extensionType: String,
    val extensionId: String,
    val depType: String,
    val depId: String,
    val resolved: Boolean = false,
    val version: String = "",
    val detail: String = ""
)