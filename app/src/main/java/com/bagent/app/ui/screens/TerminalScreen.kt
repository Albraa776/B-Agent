package com.bagent.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bagent.app.ui.MainViewModel
import com.bagent.app.ui.components.StatCard

/** Real terminal output with command input, history and process controls. */
@Composable
fun TerminalScreen(vm: MainViewModel) {
    val output by vm.terminalOutput.collectAsStateWithLifecycle()
    val cwd by vm.terminalCwd.collectAsStateWithLifecycle()
    val history by vm.terminalHistory.collectAsStateWithLifecycle()
    val running by vm.container.terminal.running.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf("") }
    var historyIndex by remember { mutableStateOf(-1) }
    val listState = rememberLazyListState()

    LaunchedEffect(output.size) {
        if (output.isNotEmpty()) listState.scrollToItem(output.size - 1)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Backend: ${vm.container.terminal.currentBackend().name} • ${vm.container.terminal.currentBackend().describe()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = vm::clearTerminal) { Text("Clear") }
                TextButton(onClick = vm::stopAllProcesses) { Text("Kill all") }
            }
        }

        if (running.isNotEmpty()) {
            item {
                StatCard(
                    "Running processes (${running.size})",
                    running.joinToString("\n") { "#${it.pid} ${it.command}" },
                    monospace = true,
                    accent = MaterialTheme.colorScheme.tertiary
                )
            }
        }

        itemsIndexedSafe(output) { index, line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                fontFamily = FontFamily.Monospace,
                color = if (line.startsWith("$ ") || line.startsWith("["))
                    MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { vm.setTerminalCwd(cwd ?: "/storage/emulated/0") }) {
                        Text(if (cwd == null) "Set cwd" else "cwd: $cwd")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("command (e.g. ls -la, echo hi)") },
                        singleLine = true
                    )
                    OutlinedButton(
                        onClick = {
                            vm.runTerminal(input, shell = true)
                            input = ""
                            historyIndex = -1
                        }
                    ) { Text("Run") }
                }
                if (history.isNotEmpty()) {
                    Text(
                        "history: ${history.takeLast(8).joinToString("  |  ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedSafe(
    items: List<String>,
    itemContent: @Composable (index: Int, item: String) -> Unit
) {
    itemsIndexed(items) { index, item -> itemContent(index, item) }
}