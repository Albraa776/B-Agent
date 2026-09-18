package com.bagent.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bagent.app.agent.permissions.Approval
import com.bagent.app.core.model.AgentState
import com.bagent.app.ui.MainViewModel
import com.bagent.app.ui.components.Section
import com.bagent.app.ui.components.PermissionSheet
import com.bagent.app.ui.components.StatCard
import com.bagent.app.ui.components.StatusChip
import com.bagent.app.ui.roleLabel

/** The main agent screen: conversation, streaming output, tools, permissions. */
@Composable
fun AgentScreen(vm: MainViewModel) {
    val runtime by vm.runtimeState.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val selectedId by vm.selectedSessionId.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, runtime.streamingText) {
        if (messages.isNotEmpty() || runtime.streamingText.isNotEmpty()) {
            listState.scrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(0))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                StatusChip(runtime.state)
                if (runtime.isRunning || runtime.state == AgentState.WAITING_FOR_PERMISSION) {
                    TextButton(onClick = vm::stopAgent) { Text("Stop") }
                }
            }
        }

        item { SessionSelector(vm, sessions = sessions, selectedId = selectedId) }

        if (runtime.plan.isNotEmpty()) {
            item {
                Section("Plan") {
                    runtime.plan.forEach { step ->
                        StatCard(
                            label = "Step ${step.index} • ${step.status}",
                            value = step.description,
                            accent = if (step.status == "completed") MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        if (runtime.currentTool != null) {
            item {
                StatCard("Current tool", runtime.currentTool!!, monospace = true)
            }
        }

        items(messages, key = { it.id }) { message ->
            val label = message.roleLabel()
            val color = when (label) {
                "You" -> MaterialTheme.colorScheme.primary
                "B Agent" -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontFamily = FontFamily.Monospace
                )
                if (message.content.isNotBlank()) {
                    Text(
                        message.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (message.role == "TOOL") MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                        fontFamily = if (message.role in setOf("TOOL", "ASSISTANT")) FontFamily.Monospace else FontFamily.SansSerif
                    )
                }
            }
        }

        if (runtime.streamingText.isNotBlank()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "B Agent",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        runtime.streamingText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        if (runtime.state == AgentState.FAILED && runtime.lastError.isNotBlank()) {
            item {
                StatCard("Error", runtime.lastError, accent = MaterialTheme.colorScheme.error, monospace = true)
            }
        }

        runtime.pendingPermission?.let { pending ->
            item {
                PermissionSheet(
                    title = "Permission needed: ${pending.toolName}",
                    reason = pending.reason,
                    onAllowOnce = { vm.answerPermission(Approval.ALLOW) },
                    onAllowAlways = { vm.answerPermission(Approval.ALLOW_ALWAYS) },
                    onAllowSession = { vm.answerPermission(Approval.ALLOW_SESSION) },
                    onDeny = { vm.answerPermission(Approval.DENY) }
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Tell B Agent what to do…") },
                    maxLines = 4
                )
                Button(
                    onClick = {
                        vm.sendPrompt(input)
                        input = ""
                    },
                    enabled = input.isNotBlank()
                ) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
private fun SessionSelector(
    vm: MainViewModel,
    sessions: List<com.bagent.app.core.database.SessionEntity>,
    selectedId: Long?
) {
    if (sessions.isEmpty()) {
        Button(onClick = { vm.newSession() }) { Text("New session +") }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Session", style = MaterialTheme.typography.labelSmall)
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(sessions.take(20), key = { it.id }) { session ->
                FilterChip(
                    selected = session.id == selectedId,
                    onClick = { vm.selectSession(session.id) },
                    label = { Text(session.title.take(24)) }
                )
            }
        }
    }
}