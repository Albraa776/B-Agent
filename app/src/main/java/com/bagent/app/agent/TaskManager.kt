package com.bagent.app.agent

import com.bagent.app.core.database.AgentStepDao
import com.bagent.app.core.database.AgentStepEntity
import com.bagent.app.core.database.AgentTaskDao
import com.bagent.app.core.database.AgentTaskEntity
import com.bagent.app.core.database.CheckpointDao
import com.bagent.app.core.database.CheckpointEntity
import com.bagent.app.core.model.AgentState
import com.bagent.app.core.util.now
import kotlinx.coroutines.flow.Flow

/** Task lifecycle, step journal and checkpoints. */
class TaskManager(
    private val taskDao: AgentTaskDao,
    private val stepDao: AgentStepDao,
    private val checkpointDao: CheckpointDao
) {
    val tasks: Flow<List<AgentTaskEntity>> = taskDao.observeAll()

    fun steps(taskId: Long): Flow<List<AgentStepEntity>> = stepDao.observeForTask(taskId)

    fun checkpoints(taskId: Long): Flow<List<CheckpointEntity>> = checkpointDao.observeForTask(taskId)

    suspend fun task(id: Long): AgentTaskEntity? = taskDao.byId(id)

    suspend fun create(sessionId: Long?, title: String, maxIterations: Int): AgentTaskEntity {
        val stamp = now()
        val id = taskDao.upsert(
            AgentTaskEntity(
                sessionId = sessionId,
                title = title,
                state = AgentState.IDLE.name,
                maxIterations = maxIterations,
                createdAt = stamp,
                updatedAt = stamp
            )
        )
        return taskDao.byId(id)!!
    }

    suspend fun setState(taskId: Long, state: AgentState) {
        taskDao.setState(taskId, state.name, now())
    }

    suspend fun setPlan(taskId: Long, plan: List<PlanStep>, currentStep: Int = 0) {
        val json = plan.joinToString(prefix = "[", postfix = "]") { step ->
            """{"index":${step.index},"description":${escape(step.description)},"status":"${step.status}"}"""
        }
        val existing = taskDao.byId(taskId) ?: return
        taskDao.upsert(existing.copy(planJson = json, currentStep = currentStep, updatedAt = now()))
    }

    suspend fun addStep(
        taskId: Long,
        sequence: Int,
        text: String,
        toolId: String = "",
        arguments: String = "",
        outcome: String = "",
        status: String = "ok"
    ) {
        stepDao.insert(
            AgentStepEntity(
                taskId = taskId,
                sequence = sequence,
                timestamp = now(),
                text = text,
                toolId = toolId,
                arguments = arguments,
                outcome = outcome,
                status = status
            )
        )
    }

    suspend fun finish(taskId: Long, result: String, error: String = "") {
        val existing = taskDao.byId(taskId) ?: return
        val stamp = now()
        taskDao.upsert(
            existing.copy(
                state = if (error.isBlank()) AgentState.COMPLETED.name else AgentState.FAILED.name,
                result = result,
                error = error,
                finishedAt = stamp,
                updatedAt = stamp
            )
        )
    }

    suspend fun checkpoint(taskId: Long, description: String, dataJson: String) {
        checkpointDao.insert(
            CheckpointEntity(
                taskId = taskId,
                createdAt = now(),
                description = description,
                dataJson = dataJson
            )
        )
    }

    suspend fun unfinished(): List<AgentTaskEntity> = taskDao.unfinished()

    suspend fun delete(taskId: Long) {
        stepDao.deleteForTask(taskId)
        checkpointDao.deleteForTask(taskId)
        taskDao.delete(taskId)
    }

    private fun escape(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
}