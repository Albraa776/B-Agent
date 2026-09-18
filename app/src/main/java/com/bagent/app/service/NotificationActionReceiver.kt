package com.bagent.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bagent.app.BAgentApp

/** Handles notification and alarm actions such as stopping the running agent. */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as? BAgentApp)?.container ?: return
        when (intent.action) {
            AgentForegroundService.ACTION_STOP -> {
                container.runtime.stop()
                container.terminal.stopAll()
            }
            ACTION_PAUSE -> container.runtime.stop()
            ACTION_START -> AgentForegroundService.start(context)
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.bagent.app.action.PAUSE_AGENT"
        const val ACTION_START = "com.bagent.app.action.START_AGENT"
    }
}