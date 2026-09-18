package com.bagent.app.core.logging

import com.bagent.app.core.database.LogEntryDao
import com.bagent.app.core.database.LogEntryEntity
import com.bagent.app.core.util.Redactor
import com.bagent.app.core.util.now
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Persistent, searchable, redacted agent logging. */
class BLogger(
    private val dao: LogEntryDao,
    private val scope: CoroutineScope
) {
    val recent: Flow<List<LogEntryEntity>> = dao.observeRecent()

    fun info(message: String, sessionId: Long? = null, taskId: Long? = null, toolId: String = "") =
        log("info", message, sessionId, taskId, toolId)

    fun warn(message: String, sessionId: Long? = null, taskId: Long? = null, toolId: String = "") =
        log("warn", message, sessionId, taskId, toolId)

    fun error(message: String, err: String = "", sessionId: Long? = null, taskId: Long? = null, toolId: String = "") =
        log("error", message, sessionId, taskId, toolId, error = err)

    fun tool(
        toolId: String,
        result: String,
        durationMs: Long,
        status: String,
        error: String = "",
        approval: String = "automatic",
        sessionId: Long? = null,
        taskId: Long? = null
    ) = log("tool", result, sessionId, taskId, toolId, durationMs, status, error, approval)

    fun log(
        level: String,
        message: String,
        sessionId: Long? = null,
        taskId: Long? = null,
        toolId: String = "",
        durationMs: Long? = null,
        status: String = "",
        error: String = "",
        approval: String = ""
    ) {
        scope.launch {
            runCatching {
                dao.insert(
                    LogEntryEntity(
                        timestamp = now(),
                        level = level,
                        sessionId = sessionId,
                        taskId = taskId,
                        toolId = toolId,
                        message = Redactor.redact(message),
                        durationMs = durationMs,
                        status = status,
                        error = Redactor.redact(error),
                        approval = approval
                    )
                )
            }
        }
    }
}