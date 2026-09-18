package com.bagent.app.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface KvSettingDao {
    @Query("SELECT * FROM kv_settings WHERE `key` = :key")
    suspend fun get(key: String): KvSettingEntity?

    @Query("SELECT value FROM kv_settings WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: KvSettingEntity)
}

@Dao
interface ProviderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provider: ProviderEntity): Long

    @Query("SELECT * FROM providers ORDER BY name")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY id")
    suspend fun all(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun byId(id: Long): ProviderEntity?

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE providers SET isDefault = 0")
    suspend fun clearDefaults()

    @Query("UPDATE providers SET isDefault = 1 WHERE id = :id")
    suspend fun markDefault(id: Long)
}

@Dao
interface WorkspaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(workspace: WorkspaceEntity): Long

    @Query("SELECT * FROM workspaces ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatest(): Flow<WorkspaceEntity?>

    @Query("SELECT * FROM workspaces WHERE id = :id")
    suspend fun byId(id: Long): WorkspaceEntity?

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity): Long

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC LIMIT 10")
    fun observeRecent(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: Long): SessionEntity?

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE sessions SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeForSession(sessionId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun allForSession(sessionId: Long): List<MessageEntity>

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)
}

@Dao
interface AgentTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: AgentTaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tasks: List<AgentTaskEntity>)

    @Query("SELECT * FROM agent_tasks ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<AgentTaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE id = :id")
    suspend fun byId(id: Long): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE state IN ('THINKING','PLANNING','EXECUTING_TOOL','WAITING_FOR_PROCESS','OBSERVING','RECOVERING','WAITING_FOR_PERMISSION')")
    suspend fun unfinished(): List<AgentTaskEntity>

    @Query("DELETE FROM agent_tasks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE agent_tasks SET state = :state, updatedAt = :now WHERE id = :id")
    suspend fun setState(id: Long, state: String, now: Long)
}

@Dao
interface AgentStepDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(step: AgentStepEntity): Long

    @Query("SELECT * FROM agent_steps WHERE taskId = :taskId ORDER BY sequence ASC")
    fun observeForTask(taskId: Long): Flow<List<AgentStepEntity>>

    @Query("SELECT * FROM agent_steps WHERE taskId = :taskId ORDER BY sequence DESC LIMIT :limit")
    suspend fun recentForTask(taskId: Long, limit: Int): List<AgentStepEntity>

    @Query("DELETE FROM agent_steps WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: Long)
}

@Dao
interface ToolDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tool: ToolEntity)

    @Query("SELECT * FROM tools ORDER BY category, name")
    fun observeAll(): Flow<List<ToolEntity>>

    @Query("SELECT * FROM tools WHERE enabled = 1")
    suspend fun allEnabled(): List<ToolEntity>

    @Query("SELECT * FROM tools WHERE id = :id")
    suspend fun byId(id: String): ToolEntity?

    @Query("UPDATE tools SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE tools SET configurationJson = :config WHERE id = :id")
    suspend fun setConfiguration(id: String, config: String)

    @Query("DELETE FROM tools WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ToolExecutionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(execution: ToolExecutionEntity): Long

    @Query("SELECT * FROM tool_executions WHERE taskId = :taskId ORDER BY createdAt ASC")
    fun observeForTask(taskId: Long): Flow<List<ToolExecutionEntity>>

    @Query("SELECT * FROM tool_executions ORDER BY createdAt DESC LIMIT 50")
    fun observeRecent(): Flow<List<ToolExecutionEntity>>
}

@Dao
interface ProcessDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(process: ProcessEntity): Long

    @Query("SELECT * FROM processes ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<ProcessEntity>>

    @Query("DELETE FROM processes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM processes WHERE status = 'finished'")
    suspend fun clearFinished()
}

@Dao
interface ProjectMemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: ProjectMemoryEntity): Long

    @Query("SELECT * FROM project_memory WHERE workspaceId = :wsId ORDER BY key")
    fun observeForWorkspace(wsId: Long): Flow<List<ProjectMemoryEntity>>

    @Query("SELECT * FROM project_memory WHERE workspaceId = :wsId ORDER BY key")
    suspend fun allForWorkspace(wsId: Long): List<ProjectMemoryEntity>

    @Query("SELECT * FROM project_memory WHERE workspaceId = :wsId AND `key` = :key")
    suspend fun byKey(wsId: Long, key: String): ProjectMemoryEntity?

    @Query("DELETE FROM project_memory WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM project_memory WHERE workspaceId = :wsId")
    suspend fun deleteForWorkspace(wsId: Long)
}

@Dao
interface UserRuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: UserRuleEntity): Long

    @Query("SELECT * FROM user_rules WHERE enabled = 1 ORDER BY priority DESC")
    suspend fun allEnabled(): List<UserRuleEntity>

    @Query("SELECT * FROM user_rules ORDER BY priority DESC")
    fun observeAll(): Flow<List<UserRuleEntity>>

    @Query("DELETE FROM user_rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE user_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}

@Dao
interface SkillDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(skill: SkillEntity)

    @Query("SELECT * FROM skills ORDER BY name")
    fun observeAll(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills WHERE enabled = 1")
    suspend fun allEnabled(): List<SkillEntity>

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE skills SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE skills SET configJson = :config WHERE id = :id")
    suspend fun setConfig(id: String, config: String)
}

@Dao
interface PluginDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plugin: PluginEntity)

    @Query("SELECT * FROM plugins ORDER BY name")
    fun observeAll(): Flow<List<PluginEntity>>

    @Query("SELECT * FROM plugins WHERE enabled = 1")
    suspend fun allEnabled(): List<PluginEntity>

    @Query("DELETE FROM plugins WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE plugins SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}

@Dao
interface McpServerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: McpServerEntity)

    @Query("SELECT * FROM mcp_servers ORDER BY name")
    fun observeAll(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_servers WHERE enabled = 1")
    suspend fun allEnabled(): List<McpServerEntity>

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE mcp_servers SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}

@Dao
interface LogEntryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: LogEntryEntity): Long

    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC LIMIT 500")
    fun observeRecent(): Flow<List<LogEntryEntity>>

    @Query("SELECT * FROM log_entries WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT 200")
    fun observeForSession(sessionId: Long): Flow<List<LogEntryEntity>>

    @Query("DELETE FROM log_entries WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface EnvironmentCapabilityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(capability: EnvironmentCapabilityEntity)

    @Query("SELECT * FROM environment_capabilities ORDER BY name")
    fun observeAll(): Flow<List<EnvironmentCapabilityEntity>>

    @Query("SELECT * FROM environment_capabilities")
    suspend fun all(): List<EnvironmentCapabilityEntity>

    @Query("DELETE FROM environment_capabilities")
    suspend fun clearAll()
}

@Dao
interface CheckpointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(checkpoint: CheckpointEntity): Long

    @Query("SELECT * FROM checkpoints WHERE taskId = :taskId ORDER BY createdAt DESC")
    fun observeForTask(taskId: Long): Flow<List<CheckpointEntity>>

    @Query("DELETE FROM checkpoints WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: Long)
}

@Dao
interface ExtensionDependencyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(dependency: ExtensionDependencyEntity)

    @Query("SELECT * FROM extension_dependencies WHERE extensionType = :type AND extensionId = :id")
    suspend fun forExtension(type: String, id: String): List<ExtensionDependencyEntity>

    @Query("DELETE FROM extension_dependencies WHERE extensionType = :type AND extensionId = :id")
    suspend fun deleteForExtension(type: String, id: String)
}