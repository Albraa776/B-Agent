package com.bagent.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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

@Composable
fun ProjectsScreen(vm: MainViewModel) {
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val runtime by vm.runtimeState.collectAsStateWithLifecycle()
    var newName by remember { mutableStateOf("") }
    var newPath by remember { mutableStateOf("/storage/emulated/0") }
    var selectedId by remember { mutableStateOf<Long?>(null) }

    ScreenScaffold(title = "Projects") {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Project name") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newPath,
                    onValueChange = { newPath = it },
                    label = { Text("Root path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = {
                        vm.createWorkspace(newName, newPath)
                        newName = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add project") }
            }
        }

        if (workspaces.isEmpty()) {
            item { Text("No projects yet. Add one above or on first run B Agent created a default workspace.") }
        }

        workspaces.forEach { ws ->
            item {
                val selected = selectedId == ws.id
                Section(ws.name.ifBlank { "Untitled" }) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatCard("Root", ws.rootUriOrPath, monospace = true)
                        StatCard("Type", ws.projectType)
                        if (ws.rules.isNotBlank()) StatCard("Rules", ws.rules, monospace = true)
                        StatCard("Last updated", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(ws.updatedAt)))
                        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { selectedId = if (selected) null else ws.id }) {
                                Text(if (selected) "Hide" else "Details")
                            }
                            TextButton(onClick = { vm.setTerminalCwd(ws.rootUriOrPath) }) { Text("Use as cwd") }
                            TextButton(onClick = { vm.deleteWorkspace(ws.id) }) { Text("Delete") }
                        }
                        if (selected) {
                            ProjectMemoryBlock(vm, ws.id)
                            GitStatusBlock(vm, ws.rootUriOrPath)
                        }
                    }
                }
            }
        }

        item {
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = vm::scanEnvironment) { Text("Re-scan environment") }
                Text(
                    "state: ${runtime.state.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProjectMemoryBlock(vm: MainViewModel, workspaceId: Long) {
    val memory by vm.container.memory.observe(workspaceId)
        .collectAsStateWithLifecycle(emptyList())
    Section("Memory") {
        if (memory.isEmpty()) {
            Text("No stored memory.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        memory.forEach { entry ->
            StatCard(entry.key, entry.value, monospace = true, accent = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun GitStatusBlock(vm: MainViewModel, root: String) {
    var gitStatus by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(root) {
        gitStatus = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                vm.container.terminal.run(
                    com.bagent.app.tools.terminal.CommandRequest(
                        command = "git -C \"${root}\" status --short --branch 2>&1 | head -40",
                        shellInterpret = true,
                        timeoutMs = 20_000
                    )
                ).combined.trim().ifBlank { "no output" }
            }.getOrDefault("git unavailable on this device")
        }
    }
    Section("Git status") {
        StatCard("git status", gitStatus ?: "checking…", monospace = true, accent = MaterialTheme.colorScheme.onSurface)
    }
}