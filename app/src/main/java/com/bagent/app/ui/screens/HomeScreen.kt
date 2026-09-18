package com.bagent.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bagent.app.ui.Destination
import com.bagent.app.ui.MainViewModel
import com.bagent.app.ui.components.ScreenScaffold
import com.bagent.app.ui.components.Section
import com.bagent.app.ui.components.StatCard
import com.bagent.app.ui.components.StatusChip

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onNavigate: (Destination) -> Unit
) {
    val runtime by vm.runtimeState.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val capabilities by vm.capabilities.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()

    val defaultProvider = providers.firstOrNull { it.isDefault } ?: providers.firstOrNull()
    val available = capabilities.count { it.available }

    ScreenScaffold(
        title = "B Agent"
    ) {
        item {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                StatusChip(runtime.state)
                Text(
                    runtime.streamingText.ifBlank {
                        when {
                            runtime.isRunning -> "working on task #${runtime.taskId ?: "-"}"
                            runtime.lastError.isNotBlank() -> "last run failed"
                            else -> "standing by"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.SansSerif
                )
            }
        }

        item {
            Section("Quick actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onNavigate(Destination.AGENT) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Text("Start Agent", modifier = Modifier.padding(start = 8.dp))
                    }
                    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onNavigate(Destination.TERMINAL) }, modifier = Modifier.weight(1f)) {
                            Text("Terminal")
                        }
                        OutlinedButton(onClick = { onNavigate(Destination.FILES) }, modifier = Modifier.weight(1f)) {
                            Text("Files")
                        }
                    }
                    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onNavigate(Destination.EXTENSIONS) }, modifier = Modifier.weight(1f)) {
                            Text("Extensions")
                        }
                        OutlinedButton(onClick = { onNavigate(Destination.DIAGNOSTICS) }, modifier = Modifier.weight(1f)) {
                            Text("Diagnostics")
                        }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Status", runtime.state.name.lowercase())
                StatCard("Active task", tasks.firstOrNull()?.title ?: "none")
                StatCard("Workspace", workspaces.firstOrNull()?.name ?: "none")
                StatCard("Provider", defaultProvider?.name ?: "not configured")
                StatCard("Model", defaultProvider?.model ?: "-")
                StatCard("Capabilities available", "$available / ${capabilities.size}")
            }
        }

        if (workspaces.isNotEmpty()) {
            item { Section("Projects") { } }
            workspaces.take(5).forEach { ws ->
                item {
                    StatCard(ws.name, ws.rootUriOrPath, monospace = true, accent = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        if (sessions.isNotEmpty()) {
            item { Section("Recent sessions") { } }
            sessions.sortedByDescending { it.updatedAt }.take(5).forEach { session ->
                item {
                    StatCard("$session", "")
                }
            }
        }

        item {
            OutlinedButton(
                onClick = { vm.newSession() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("New session", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}