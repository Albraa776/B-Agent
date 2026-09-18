package com.bagent.app.agent

import com.bagent.app.core.database.MessageDao
import com.bagent.app.core.database.MessageEntity
import com.bagent.app.core.database.SessionDao
import com.bagent.app.core.database.SessionEntity
import com.bagent.app.core.model.ChatMessage
import com.bagent.app.core.model.ContentPart
import com.bagent.app.core.model.Role
import com.bagent.app.core.model.ToolCallRequest
import com.bagent.app.core.util.JsonUtil
import com.bagent.app.core.util.Redactor
import com.bagent.app.core.util.now
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Converts a stored message row into the provider-facing message model. */
fun MessageEntity.toChatMessage(): ChatMessage {
    val parts = when (role) {
        Role.USER.name -> parseParts(partsJson).ifEmpty { listOf(ContentPart.Text(content)) }
        else -> listOf(ContentPart.Text(content))
    }
    return ChatMessage(
        role = runCatching { Role.valueOf(role) }.getOrDefault(Role.USER),
        parts = parts,
        toolCallId = toolCallId,
        toolName = toolName,
        assistantToolCalls = if (role == Role.ASSISTANT.name) parseToolCalls(toolCallsJson) else emptyList()
    )
}

private fun parseParts(json: String): List<ContentPart> {
    val array = runCatching { JsonUtil.parseElement(json)?.jsonArray }.getOrNull() ?: return emptyList()
    return array.mapNotNull { element ->
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
        when (obj["type"]?.toString()?.trim('"')) {
            "text" -> ContentPart.Text(obj["text"]?.toString()?.trim('"') ?: "")
            "image" -> ContentPart.Image(
                bytesBase64 = obj["data"]?.toString()?.trim('"') ?: "",
                mediaType = obj["mediaType"]?.toString()?.trim('"') ?: "image/jpeg"
            )
            else -> null
        }
    }
}

private fun parseToolCalls(json: String): List<ToolCallRequest> {
    val array = runCatching { JsonUtil.parseElement(json)?.jsonArray }.getOrNull() ?: return emptyList()
    return array.mapNotNull { element ->
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
        val name = obj["name"]?.toString()?.trim('"') ?: return@mapNotNull null
        ToolCallRequest(
            id = obj["id"]?.toString()?.trim('"') ?: name,
            name = name,
            argumentsJson = obj["arguments"]?.toString() ?: "{}"
        )
    }
}

/** Owns sessions and message persistence. */
class SessionManager(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao
) {
    val sessions: Flow<List<SessionEntity>> = sessionDao.observeAll()
    val recent: Flow<List<SessionEntity>> = sessionDao.observeRecent()

    fun messages(sessionId: Long): Flow<List<MessageEntity>> = messageDao.observeForSession(sessionId)

    suspend fun session(id: Long): SessionEntity? = sessionDao.byId(id)

    suspend fun create(
        title: String,
        workspaceId: Long?,
        providerId: Long?,
        model: String
    ): SessionEntity {
        val stamp = now()
        val id = sessionDao.upsert(
            SessionEntity(
                title = title.ifBlank { "New task" },
                workspaceId = workspaceId,
                providerId = providerId,
                model = model,
                createdAt = stamp,
                updatedAt = stamp
            )
        )
        return sessionDao.byId(id)!!
    }

    suspend fun rename(id: Long, title: String) {
        val existing = sessionDao.byId(id) ?: return
        sessionDao.upsert(existing.copy(title = title, updatedAt = now()))
    }

    suspend fun delete(id: Long) {
        messageDao.deleteForSession(id)
        sessionDao.delete(id)
    }

    suspend fun history(sessionId: Long): List<MessageEntity> = messageDao.allForSession(sessionId)

    suspend fun addUserMessage(
        sessionId: Long,
        text: String,
        images: List<ContentPart.Image> = emptyList()
    ): MessageEntity {
        val partsJson = if (images.isEmpty()) {
            buildJsonArray { }.toString()
        } else {
            buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", text) })
                images.forEach { img ->
                    add(buildJsonObject {
                        put("type", "image")
                        put("mediaType", img.mediaType)
                        put("data", img.bytesBase64)
                    })
                }
            }.toString()
        }
        return insert(sessionId, Role.USER, text, partsJson = partsJson)
    }

    suspend fun addAssistantMessage(
        sessionId: Long,
        text: String,
        toolCalls: List<ToolCallRequest>
    ): MessageEntity {
        val toolCallsJson = buildJsonArray {
            toolCalls.forEach { call ->
                add(buildJsonObject {
                    put("id", call.id)
                    put("name", call.name)
                    put("arguments", call.argumentsJson)
                })
            }
        }.toString()
        return insert(sessionId, Role.ASSISTANT, text, toolCallsJson = toolCallsJson)
    }

    suspend fun addToolResult(
        sessionId: Long,
        toolCallId: String,
        toolName: String,
        argumentsJson: String,
        resultText: String,
        status: String
    ): MessageEntity = insert(
        sessionId,
        Role.TOOL,
        resultText,
        toolCallId = toolCallId,
        toolName = toolName,
        toolArgs = argumentsJson,
        status = status
    )

    private suspend fun insert(
        sessionId: Long,
        role: Role,
        content: String,
        partsJson: String = "[]",
        toolCallId: String? = null,
        toolName: String? = null,
        toolArgs: String? = null,
        toolCallsJson: String = "[]",
        status: String = "ok"
    ): MessageEntity {
        val entity = MessageEntity(
            sessionId = sessionId,
            role = role.name,
            content = Redactor.redact(content),
            partsJson = partsJson,
            toolCallId = toolCallId,
            toolName = toolName,
            toolArgs = toolArgs,
            toolCallsJson = toolCallsJson,
            status = status,
            createdAt = now()
        )
        val id = messageDao.insert(entity)
        sessionDao.touch(sessionId, now())
        return entity.copy(id = id)
    }
}