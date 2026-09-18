package com.bagent.app.plugins

import com.bagent.app.core.database.ExtensionDependencyDao
import com.bagent.app.core.database.ExtensionDependencyEntity
import com.bagent.app.core.database.PluginDao
import com.bagent.app.core.database.PluginEntity
import com.bagent.app.core.model.PermAction
import com.bagent.app.core.model.RiskLevel
import com.bagent.app.core.network.Http
import com.bagent.app.core.util.JsonUtil
import com.bagent.app.core.util.Redactor
import com.bagent.app.core.util.now
import com.bagent.app.tools.ToolBase
import com.bagent.app.tools.ToolEnv
import com.bagent.app.tools.ToolRegistry
import com.bagent.app.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Declarative plugin system. A plugin is a JSON manifest that can provide
 * HTTP-backed tools and declare binary/permission dependencies. Provided tools
 * are real, executable tools registered into the tool registry.
 */
class PluginManager(
    private val dao: PluginDao,
    private val dependencyDao: ExtensionDependencyDao,
    private val registry: ToolRegistry
) {
    val plugins: Flow<List<PluginEntity>> = dao.observeAll()

    suspend fun installFromJson(json: String, source: String = "manual"): PluginEntity {
        val manifest = JsonUtil.parseObject(json) ?: error("plugin manifest is not a JSON object")
        val id = manifest.str("id") ?: error("plugin manifest is missing 'id'")
        val name = manifest.str("name") ?: id
        val version = manifest.str("version") ?: "1.0.0"
        val provides: JsonArray = runCatching { manifest["provides"]?.jsonArray }.getOrNull() ?: buildJsonArray {}
        val permissions: JsonArray = runCatching { manifest["permissions"]?.jsonArray }.getOrNull() ?: buildJsonArray {}
        val dependencies: JsonArray = runCatching { manifest["dependencies"]?.jsonArray }.getOrNull() ?: buildJsonArray {}

        val entity = PluginEntity(
            id = id,
            name = name,
            version = version,
            author = manifest.str("author") ?: "",
            description = manifest.str("description") ?: "",
            enabled = true,
            manifestJson = json,
            permissionsJson = permissions.toString(),
            dependenciesJson = dependencies.toString(),
            providesJson = provides.toString(),
            source = source,
            installedAt = now()
        )
        dao.upsert(entity)
        registerProvidedTools(entity)

        dependencyDao.deleteForExtension("plugin", id)
        dependencies.forEach { dep ->
            val value = runCatching { dep.jsonPrimitive.content }.getOrNull() ?: return@forEach
            dependencyDao.upsert(
                ExtensionDependencyEntity(
                    extensionType = "plugin",
                    extensionId = id,
                    depType = "binary",
                    depId = value,
                    resolved = false
                )
            )
        }
        return entity
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.setEnabled(id, enabled)
        val entity = daoAll().firstOrNull { it.id == id } ?: return
        if (enabled) registerProvidedTools(entity) else unregisterProvidedTools(entity)
    }

    suspend fun uninstall(id: String) {
        daoAll().firstOrNull { it.id == id }?.let { unregisterProvidedTools(it) }
        dependencyDao.deleteForExtension("plugin", id)
        dao.delete(id)
    }

    suspend fun loadEnabled() {
        daoAll().filter { it.enabled }.forEach { registerProvidedTools(it) }
    }

    suspend fun dependencies(id: String): List<ExtensionDependencyEntity> =
        dependencyDao.forExtension("plugin", id)

    suspend fun resolveDependencies(id: String, checker: suspend (String) -> Boolean): List<ExtensionDependencyEntity> {
        val deps = dependencyDao.forExtension("plugin", id)
        return deps.map { dep ->
            val ok = runCatching { checker(dep.depId) }.getOrDefault(false)
            val updated = dep.copy(resolved = ok)
            dependencyDao.upsert(updated)
            updated
        }
    }

    private suspend fun daoAll(): List<PluginEntity> = dao.observeAll().first()

    private suspend fun registerProvidedTools(plugin: PluginEntity) {
        val provides = runCatching { JsonUtil.parseElement(plugin.providesJson)?.jsonArray }.getOrNull() ?: return
        provides.forEach { element ->
            val spec = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
            val type = spec.str("type") ?: "tool"
            if (type != "tool") return@forEach
            val toolName = spec.str("name") ?: return@forEach
            val id = "plugin_${sanitize(plugin.name)}_${sanitize(toolName)}"
            if (registry.resolve(id) != null) return@forEach
            registry.register(PluginHttpTool(plugin, id, spec))
        }
    }

    private suspend fun unregisterProvidedTools(plugin: PluginEntity) {
        val provides = runCatching { JsonUtil.parseElement(plugin.providesJson)?.jsonArray }.getOrNull() ?: return
        provides.forEach { element ->
            val spec = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
            val toolName = spec.str("name") ?: return@forEach
            registry.unregister("plugin_${sanitize(plugin.name)}_${sanitize(toolName)}")
        }
    }

    private fun sanitize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9_-]"), "_").trim('_').ifBlank { "x" }

    /** Executes a declarative HTTP tool defined by a plugin manifest. */
    inner class PluginHttpTool(
        private val plugin: PluginEntity,
        override val id: String,
        private val spec: JsonObject
    ) : ToolBase() {
        private val http = runCatching { spec["http"]?.jsonObject }.getOrNull() ?: buildJsonObject {}
        override val name: String = id
        override val category: String = "plugin"
        override val description: String =
            "[plugin:${plugin.name}] ${spec.str("description") ?: "Plugin tool"}"
        override val risk: RiskLevel =
            runCatching { RiskLevel.valueOf(spec.str("risk") ?: "LOW") }.getOrDefault(RiskLevel.LOW)
        override val requiredPermission: PermAction =
            runCatching { PermAction.valueOf(spec.str("permission") ?: "NETWORK_ACCESS") }
                .getOrDefault(PermAction.NETWORK_ACCESS)
        override val timeoutMs: Long = (spec.str("timeoutMs")?.toLongOrNull() ?: 60_000L)
        override val backend: String = "plugin"
        override val parameters: JsonObject =
            runCatching { spec["parameters"]?.jsonObject }.getOrNull() ?: buildJsonObject { put("type", "object") }

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult = withContext(Dispatchers.IO) {
            val method = (http.str("method") ?: "GET").uppercase()
            val template = http.str("url")
                ?: return@withContext ToolResult.Failure("plugin tool $id has no http.url")
            val url = substitute(template, args)
            val headers = Http.parseHeaders(runCatching { http["headers"]?.jsonObject }.getOrNull())
            val bodyTemplate = http.str("bodyTemplate")
            val request = Http.buildRequest(url, headers).let { builder ->
                when (method) {
                    "GET" -> builder.get()
                    "POST", "PUT", "PATCH" -> {
                        val body = if (!bodyTemplate.isNullOrBlank()) substitute(bodyTemplate, args) else args.toString()
                        builder.method(method, Http.jsonBody(body))
                    }
                    "DELETE" -> builder.delete()
                    else -> builder.method(method, null)
                }
            }.build()
            runCatching {
                Http.client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    val truncated = text.length > 20_000
                    ToolResult.Success(
                        buildJsonObject {
                            put("status", response.code)
                            put("truncated", truncated)
                            put("body", Redactor.redact(text.take(20_000)))
                        }.toString()
                    )
                }
            }.getOrElse { ToolResult.Failure("plugin tool $id failed: ${it.message}") }
        }

        private fun substitute(template: String, args: JsonObject): String {
            var out = template
            args.forEach { (key, value) ->
                val raw = runCatching { value.jsonPrimitive.content }.getOrNull() ?: value.toString()
                out = out.replace("{$key}", raw)
            }
            return out
        }
    }
}

private fun JsonObject.str(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }