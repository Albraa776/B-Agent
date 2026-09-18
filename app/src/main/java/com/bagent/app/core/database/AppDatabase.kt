package com.bagent.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        KvSettingEntity::class,
        ProviderEntity::class,
        WorkspaceEntity::class,
        SessionEntity::class,
        MessageEntity::class,
        AgentTaskEntity::class,
        AgentStepEntity::class,
        ToolEntity::class,
        ToolExecutionEntity::class,
        ProcessEntity::class,
        ProjectMemoryEntity::class,
        UserRuleEntity::class,
        SkillEntity::class,
        PluginEntity::class,
        McpServerEntity::class,
        LogEntryEntity::class,
        EnvironmentCapabilityEntity::class,
        CheckpointEntity::class,
        ExtensionDependencyEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun kvSettingDao(): KvSettingDao
    abstract fun providerDao(): ProviderDao
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun agentTaskDao(): AgentTaskDao
    abstract fun agentStepDao(): AgentStepDao
    abstract fun toolDao(): ToolDao
    abstract fun toolExecutionDao(): ToolExecutionDao
    abstract fun processDao(): ProcessDao
    abstract fun projectMemoryDao(): ProjectMemoryDao
    abstract fun userRuleDao(): UserRuleDao
    abstract fun skillDao(): SkillDao
    abstract fun pluginDao(): PluginDao
    abstract fun mcpServerDao(): McpServerDao
    abstract fun logEntryDao(): LogEntryDao
    abstract fun environmentCapabilityDao(): EnvironmentCapabilityDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun extensionDependencyDao(): ExtensionDependencyDao

    companion object {
        const val NAME = "bagent.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .setJournalMode(JournalMode.TRUNCATE)
                .build()

        fun buildInMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .build()
    }
}