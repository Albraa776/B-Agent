package com.bagent.app.agent

import com.bagent.app.core.database.ProjectMemoryDao
import com.bagent.app.core.database.ProjectMemoryEntity
import com.bagent.app.core.util.now
import kotlinx.coroutines.flow.Flow

/** Durable per-workspace memory the agent can read and write across sessions. */
class MemoryManager(private val dao: ProjectMemoryDao) {

    fun observe(workspaceId: Long): Flow<List<ProjectMemoryEntity>> = dao.observeForWorkspace(workspaceId)

    suspend fun all(workspaceId: Long): List<ProjectMemoryEntity> = dao.allForWorkspace(workspaceId)

    suspend fun recall(workspaceId: Long, key: String): String? = dao.byKey(workspaceId, key)?.value

    suspend fun remember(workspaceId: Long, key: String, value: String, namespace: String = "project") {
        val existing = dao.byKey(workspaceId, key)
        dao.upsert(
            ProjectMemoryEntity(
                id = existing?.id ?: 0L,
                workspaceId = workspaceId,
                namespace = namespace,
                key = key,
                value = value,
                updatedAt = now()
            )
        )
    }

    suspend fun forget(id: Long) = dao.delete(id)

    suspend fun clear(workspaceId: Long) = dao.deleteForWorkspace(workspaceId)

    suspend fun summary(workspaceId: Long): String =
        all(workspaceId).joinToString("\n") { "- ${it.key}: ${it.value}" }
}