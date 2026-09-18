package com.bagent.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bagent.app.ui.MainViewModel
import com.bagent.app.ui.components.ScreenScaffold
import com.bagent.app.ui.components.Section
import com.bagent.app.ui.components.StatCard
import com.bagent.app.ui.components.StatusChip
import com.bagent.app.core.model.AgentState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun TasksScreen(vm: MainViewModel) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val runtime by vm.runtimeState.collectAsStateWithLifecycle()
    var selectedId by remember { mutableStateOf<Long?>(null) }

    ScreenScaffold(title = "Tasks") {
        if (tasks.isEmpty()) {
            item { Text("No tasks yet. Ask the agent something to create one.") }
        }
        tasks.forEach { task ->
            item {
                val selected = selectedId == task.id
                Section(
                    "${task.title} • #${task.id}"
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            StatusChip(runCatching { AgentState.valueOf(task.state) }.getOrDefault(AgentState.IDLE))
                            Text(
                                "steps ${task.currentStep} • updated " +
                                    java.text.SimpleDateFormat("HH:mm").format(java.util.Date(task.updatedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { selectedId = if (selected) null else task.id }) {
                            Text(if (selected) "Hide details" else "Details")
                        }
                        if (selected) {
                            TaskDetail(vm, task)
                        }
                    }
                }
            }
        }
        item {
            Text(
                "runtime: ${runtime.state.name}${if (runtime.taskId != null) " on task #${runtime.taskId}" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TaskDetail(
    vm: MainViewModel,
    task: com.bagent.app.core.database.AgentTaskEntity
) {
    val steps by vm.container.tasks.steps(task.id).collectAsStateWithLifecycle(emptyList())
    val logs by vm.container.logger.recent.collectAsStateWithLifecycle(emptyList())
    val taskLogs = logs.filter { it.taskId == task.id }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val plan = runCatching {
            Json { ignoreUnknownKeys = true }.parseToJsonElement(task.planJson)
        }.getOrNull()
        if (plan is JsonArray) {
            Section("Plan") {
                plan.forEach { el ->
                    val desc = runCatching {
                        el.jsonObject["description"]?.jsonPrimitive?.content
                    }.getOrNull() ?: el.toString()
                    StatCard("step", desc, monospace = true)
                }
            }
        }
        if (task.error.isNotBlank()) {
            StatCard("Error", task.error, accent = MaterialTheme.colorScheme.error, monospace = true)
        }
        if (task.result.isNotBlank()) {
            StatCard("Result", task.result.take(600), monospace = true, accent = MaterialTheme.colorScheme.secondary)
        }
        if (steps.isNotEmpty()) {
            Section("Steps") {
                steps.reversed().take(40).forEach { step ->
                    StatCard(
                        label = "${step.sequence} • ${step.toolId.ifBlank { "message" }} • ${step.status}",
                        value = step.text.takeIf { it.isNotBlank() } ?: step.outcome,
                        monospace = true,
                        accent = if (step.status == "ok") MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        if (taskLogs.isNotEmpty()) {
            Section("Logs") {
                taskLogs.take(20).forEach { log ->
                    Text(
                        "${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(log.timestamp))} [${log.level}] ${log.message.take(200)}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}