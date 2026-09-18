package com.bagent.app.skills

import com.bagent.app.core.database.SkillDao
import com.bagent.app.core.database.SkillEntity
import com.bagent.app.core.model.PermAction
import com.bagent.app.core.model.RiskLevel
import com.bagent.app.core.network.Http
import com.bagent.app.core.util.JsonUtil
import com.bagent.app.core.util.now
import com.bagent.app.tools.ToolBase
import com.bagent.app.tools.ToolEnv
import com.bagent.app.tools.ToolRegistry
import com.bagent.app.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Installs, stores and serves Markdown skills (instructions + optional tool
 * permissions). Skills are loaded on demand through the `skill` tool so a
 * large library does not consume the context window.
 */
class SkillManager(
    private val dao: SkillDao,
    private val registry: ToolRegistry
) {
    val skills: Flow<List<SkillEntity>> = dao.observeAll()

    suspend fun enabled(): List<SkillEntity> = dao.allEnabled()

    suspend fun install(skill: SkillEntity) {
        dao.upsert(skill)
        registerTool()
    }

    /** Installs a skill from a Markdown document with optional YAML front matter. */
    suspend fun installFromMarkdown(text: String, source: String = "manual"): SkillEntity {
        val (meta, body) = splitFrontMatter(text)
        val name = meta["name"] ?: body.lineSequence().firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")?.trim() ?: "Untitled skill"
        val description = meta["description"] ?: ""
        val version = meta["version"] ?: "1.0.0"
        val permissions = meta["permissions"]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val id = meta["id"] ?: slug(name)
        val entity = SkillEntity(
            id = id,
            name = name,
            version = version,
            description = description,
            enabled = true,
            instructions = body.trim(),
            permissionsJson = permissions.joinToString(prefix = "[", postfix = "]") { "\"$it\"" },
            source = source,
            installedAt = now()
        )
        dao.upsert(entity)
        registerTool()
        return entity
    }

    suspend fun installFromUrl(url: String): SkillEntity = withContext(Dispatchers.IO) {
        val request = Http.buildRequest(url, mapOf("Accept" to "text/markdown, text/plain, */*"))
            .get().build()
        val body = Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("download failed: HTTP ${response.code}")
            response.body?.string() ?: error("empty skill document")
        }
        installFromMarkdown(body, source = url)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)

    /** Makes sure the on-demand `skill` tool exists in the registry. */
    suspend fun ensureRegistered() = registerTool()

    suspend fun uninstall(id: String) {
        dao.delete(id)
        registry.unregister(SKILL_TOOL_ID, removeFromDb = false)
        if (dao.allEnabled().isNotEmpty()) registerTool()
    }

    private suspend fun registerTool() {
        if (registry.resolve(SKILL_TOOL_ID) == null) {
            registry.register(SkillTool())
        }
    }

    private fun splitFrontMatter(text: String): Pair<Map<String, String>, String> {
        if (!text.trimStart().startsWith("---")) return emptyMap<String, String>() to text
        val lines = text.trimStart().removePrefix("---").lines()
        val meta = mutableMapOf<String, String>()
        val body = StringBuilder()
        var inFrontMatter = true
        lines.forEach { line ->
            if (inFrontMatter && line.trim() == "---") {
                inFrontMatter = false
                return@forEach
            }
            if (inFrontMatter) {
                val index = line.indexOf(':')
                if (index > 0) {
                    meta[line.substring(0, index).trim().lowercase(Locale.ROOT)] =
                        line.substring(index + 1).trim().trim('"')
                }
            } else {
                body.appendLine(line)
            }
        }
        return meta to body.toString()
    }

    private fun slug(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** Lists available skills or returns the full instructions of one skill. */
    inner class SkillTool : ToolBase() {
        override val id = SKILL_TOOL_ID
        override val name = SKILL_TOOL_ID
        override val category = "skills"
        override val description =
            "List installed skills, or load one skill's full instructions by name/id."
        override val requiredPermission = PermAction.READ_FILES
        override val risk = RiskLevel.SAFE
        override val parameters = JsonUtil.parseObject(
            """{"type":"object","properties":{"name":{"type":"string","description":"skill id or name; omit to list all"}}}"""
        )

        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val query = args["name"]?.let { runCatching { it.toString().trim('"') }.getOrNull() }
            val all = runCatching { dao.allEnabled() }.getOrElse { emptyList() }
            if (query.isNullOrBlank()) {
                if (all.isEmpty()) return ToolResult.Success("No skills installed.")
                val list = all.joinToString("\n") { "- ${it.id} (${it.name} ${it.version}): ${it.description}" }
                return ToolResult.Success("Installed skills:\n$list")
            }
            val match = all.firstOrNull {
                it.id.equals(query, true) || it.name.equals(query, true) ||
                    it.name.contains(query, true)
            } ?: return ToolResult.Failure("no skill named '$query'")
            return ToolResult.Success(
                buildJsonObject {
                    put("id", match.id)
                    put("name", match.name)
                    put("description", match.description)
                    put("instructions", match.instructions)
                }.toString()
            )
        }
    }

    companion object {
        const val SKILL_TOOL_ID = "skill"
    }
}