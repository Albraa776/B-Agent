package com.bagent.app.tools

import com.bagent.app.core.database.ToolDao
import com.bagent.app.core.database.ToolEntity
import com.bagent.app.core.model.ToolSchema
import com.bagent.app.core.util.JsonUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Dynamic tool catalog. Tools can be registered, unregistered, enabled,
 * disabled and reconfigured at runtime without rebuilding the application.
 */
class ToolRegistry(private val dao: ToolDao) {

    private val registered = ConcurrentHashMap<String, ToolBase>()

    val all: Flow<List<ToolEntity>> = dao.observeAll()

    fun register(tool: ToolBase, persist: Boolean = true) {
        registered[tool.id] = tool
        if (persist) {
            runCatching {
                dao.upsert(
                    ToolEntity(
                        id = tool.id,
                        name = tool.name,
                        description = tool.description,
                        category = tool.category,
                        version = tool.version,
                        enabled = true,
                        installed = true,
                        permission = tool.requiredPermission.name,
                        risk = tool.risk.name,
                        backend = tool.backend,
                        timeoutMs = tool.timeoutMs,
                        metadataJson = tool.parameters?.toString() ?: "{}"
                    )
                )
            }
        }
    }

    fun unregister(toolId: String, removeFromDb: Boolean = true) {
        registered.remove(toolId)
        if (removeFromDb) runCatching { dao.delete(toolId) }
    }

    suspend fun enable(toolId: String, enabled: Boolean) {
        runCatching { dao.setEnabled(toolId, enabled) }
    }

    suspend fun updateConfiguration(toolId: String, config: JsonObject) {
        runCatching { dao.setConfiguration(toolId, config.toString()) }
    }

    fun resolve(toolId: String): ToolBase? = registered[toolId]

    val ids: Set<String> get() = registered.keys

    fun allRegistered(): List<ToolBase> = registered.values.toList()

    /** Schemas to advertise to the model: only enabled, persisted tools. */
    suspend fun activeSchemas(): List<ToolSchema> {
        val enabledIds = runCatching { dao.allEnabled().map { it.id } }.getOrDefault(registered.keys.toList())
        return registered.values
            .filter { it.id in enabledIds || enabledIds.isEmpty() }
            .map { tool ->
                val parameters = tool.parameters ?: buildJsonObject {
                    put("type", "object")
                }
                ToolSchema(tool.name, tool.description, ensureObjectSchema(parameters))
            }
            .sortedBy { it.name }
    }

    /** Configuration of a persisted tool (or empty object). */
    suspend fun configurationOf(toolId: String): JsonObject {
        val entity = runCatching { dao.byId(toolId) }.getOrNull() ?: return buildJsonObject {}
        return JsonUtil.parseObject(entity.configurationJson) ?: buildJsonObject {}
    }

    private fun ensureObjectSchema(parameters: JsonObject): JsonObject {
        if (parameters.containsKey("type") && parameters.containsKey("properties")) return parameters
        return buildJsonObject {
            put("type", "object")
            put("properties", parameters.get("properties") ?: buildJsonObject {})
        }
    }
}