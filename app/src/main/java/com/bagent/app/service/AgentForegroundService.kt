package com.bagent.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.bagent.app.BAgentApp
import com.bagent.app.R
import com.bagent.app.core.model.AgentState
import com.bagent.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps a long-running agent task alive in the foreground and mirrors the real
 * runtime state into an ongoing notification with a stop action.
 */
class AgentForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat(buildNotification("Starting B Agent…", true))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                (application as BAgentApp).container.runtime.stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startForegroundCompat(buildNotification("B Agent is ready", true))
        observeRuntime()
        return START_STICKY
    }

    private fun observeRuntime() {
        collectJob?.cancel()
        collectJob = scope.launch {
            val container = (application as BAgentApp).container
            container.runtime.state.collectLatest { state ->
                val title = when (state.state) {
                    AgentState.IDLE -> "B Agent is ready"
                    AgentState.THINKING -> "Thinking…"
                    AgentState.PLANNING -> "Planning the task…"
                    AgentState.EXECUTING_TOOL -> "Running ${state.currentTool ?: "tool"}…"
                    AgentState.WAITING_FOR_PERMISSION -> "Waiting for your approval"
                    AgentState.OBSERVING -> "Reviewing results…"
                    AgentState.RECOVERING -> "Recovering from an error…"
                    AgentState.WAITING_FOR_PROCESS -> "Waiting for a process…"
                    AgentState.PAUSED -> "Paused"
                    AgentState.COMPLETED -> "Task completed"
                    AgentState.FAILED -> "Task failed"
                    AgentState.CANCELLED -> "Task cancelled"
                    AgentState.BLOCKED -> "Blocked"
                }
                val detail = when {
                    state.pendingPermission != null -> "Permission required: ${state.pendingPermission!!.reason.take(80)}"
                    state.currentTool != null -> state.currentTool
                    state.lastError.isNotBlank() -> state.lastError.take(100)
                    state.streamingText.isNotBlank() -> state.streamingText.takeLast(120)
                    else -> "Task #${state.taskId ?: "-"} • iteration ${state.iteration}/${state.maxIterations}"
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification("$title\n${detail.take(140)}", state.isRunning))
                if (!state.isRunning && state.state in TERMINAL_STATES) {
                    // Leave the final state visible; the user dismisses it or opens the app.
                }
            }
        }
    }

    override fun onDestroy() {
        collectJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notification_channel_desc) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getBroadcast(
            this, 1,
            Intent(this, NotificationActionReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("B Agent")
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
    }

    companion object {
        const val CHANNEL_ID = "bagent_agent"
        const val NOTIFICATION_ID = 4711
        const val ACTION_STOP = "com.bagent.app.action.STOP_AGENT"

        private val TERMINAL_STATES = setOf(
            AgentState.COMPLETED, AgentState.FAILED, AgentState.CANCELLED, AgentState.IDLE
        )

        fun start(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AgentForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}