package com.bagent.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bagent.app.BAgentApp
import kotlinx.coroutines.delay

/**
 * Runs a queued agent prompt as background work. It submits the prompt to the
 * shared runtime and waits for the run to finish, so WorkManager can retry or
 * report a real outcome instead of returning immediately.
 */
class TaskWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? BAgentApp ?: return Result.failure()
        val container = app.container
        val sessionId = inputData.getLong(KEY_SESSION_ID, -1L)
        val prompt = inputData.getString(KEY_PROMPT) ?: return Result.failure()
        if (sessionId <= 0L) return Result.failure()

        AgentForegroundService.start(applicationContext)
        container.runtime.send(sessionId, prompt)

        var waited = 0L
        while (waited < MAX_WAIT_MS) {
            delay(POLL_MS)
            waited += POLL_MS
            val state = container.runtime.state.value
            if (state.sessionId == sessionId && !state.isRunning) {
                return when (state.state) {
                    com.bagent.app.core.model.AgentState.FAILED -> Result.retry()
                    else -> Result.success()
                }
            }
        }
        return Result.retry()
    }

    companion object {
        private const val KEY_SESSION_ID = "sessionId"
        private const val KEY_PROMPT = "prompt"
        private const val POLL_MS = 1_500L
        private const val MAX_WAIT_MS = 30 * 60 * 1000L

        fun enqueue(context: Context, sessionId: Long, prompt: String) {
            val data = Data.Builder()
                .putLong(KEY_SESSION_ID, sessionId)
                .putString(KEY_PROMPT, prompt)
                .build()
            val request = OneTimeWorkRequestBuilder<TaskWorker>()
                .setInputData(data)
                .addTag("bagent-task")
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("bagent-$sessionId", ExistingWorkPolicy.KEEP, request)
        }
    }
}