package com.bagent.app.mcp

import com.bagent.app.core.database.McpServerDao
import com.bagent.app.core.database.McpServerEntity
import com.bagent.app.core.logging.BLogger
import com.bagent.app.core.model.PermAction
import com.bagent.app.core.model.RiskLevel
import com.bagent.app.tools.ToolBase
import com.bagent.app.tools.ToolEnv
import com.bagent.app.tools.ToolRegistry
import com.bagent.app.tools.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** A registered tool that proxies a call to an MCP server. */
class McpTool(
    private val client: McpClient,
    override val id: String,
    private val serverLabel: String,
    private val def: McpToolDef
) : ToolBase() {
    override val name: String = id
    override val category: String = "mcp"
    override val version: String = "1.0.0"
    override val description: String =
        "[MCP:$serverLabel] ${def.description.ifBlank { def.name }}"
    override val requiredPermission: PermAction = PermAction.NETWORK_ACCESS
    override val risk: RiskLevel = RiskLevel.LOW
    override val timeoutMs: Long = 120_000L
    override val backend: String = "mcp"
    override val parameters: JsonObject = def.inputSchema

    override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult =
        runCatching { client.callTool(def.name, args) }
            .fold({ ToolResult.Success(it) }, { ToolResult.Failure("MCP tool ${def.name} failed: ${it.message}") })
}

/**
 * Manages MCP server connections and mirrors their tools into the tool
 * registry so the model can call them like any built-in tool.
 */
class McpManager(
    private val dao: McpServerDao,
    private val registry: ToolRegistry,
    private val logger: BLogger
) {
    private data class Connection(val client: McpClient, val toolIds: List<String>, val summary: String)

    private val connections = ConcurrentHashMap<String, Connection>()

    val servers: Flow<List<McpServerEntity>> = dao.observeAll()

    suspend fun enabled(): List<McpServerEntity> = dao.allEnabled()

    /** Connects a server, lists its tools and registers them. Returns a summary. */
    suspend fun connect(server: McpServerEntity): String {
        disconnect(server.id)
        val client = McpClient(server)
        client.initialize()
        val tools = client.listTools()
        val label = server.name.ifBlank { server.id }
        val toolIds = mutableListOf<String>()
        tools.forEach { def ->
            var id = "mcp_${sanitize(label)}_${sanitize(def.name)}"
            if (id.length > 60) id = id.take(60)
            var unique = id
            var suffix = 2
            while (registry.resolve(unique) != null) {
                unique = "${id.take(56)}_$suffix"
                suffix++
            }
            registry.register(McpTool(client, unique, label, def))
            toolIds += unique
        }
        val summary = "$label: ${tools.size} tool(s)"
        connections[server.id] = Connection(client, toolIds, summary)
        logger.info("MCP connected: $summary")
        return summary
    }

    suspend fun disconnect(serverId: String) {
        connections.remove(serverId)?.toolIds?.forEach { registry.unregister(it) }
    }

    fun disconnectAll() {
        connections.keys.toList().forEach { id ->
            connections.remove(id)?.toolIds?.forEach { registry.unregister(it) }
        }
    }

    suspend fun connectAll(): List<String> {
        val results = mutableListOf<String>()
        enabled().forEach { server ->
            results += runCatching { connect(server) }
                .getOrElse { error ->
                    val message = "${server.name.ifBlank { server.id }}: failed (${error.message})"
                    logger.warn("MCP connect failed: $message")
                    message
                }
        }
        return results
    }

    suspend fun test(server: McpServerEntity): String = runCatching {
        McpClient(server).probe()
    }.getOrElse { "failed: ${it.message}" }

    fun summary(): String = connections.values.joinToString("\n") { "- ${it.summary}" }

    fun isConnected(serverId: String): Boolean = connections.containsKey(serverId)

    suspend fun upsert(server: McpServerEntity) = dao.upsert(server)

    suspend fun delete(id: String) {
        disconnect(id)
        dao.delete(id)
    }

    private fun sanitize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9_-]"), "_").trim('_').ifBlank { "x" }
}