package com.bagent.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.bagent.app.ui.components.StatCard
import java.io.File

/** A real, honest filesystem browser rooted at any device path. */
@Composable
fun FilesScreen(vm: MainViewModel) {
    var path by remember { mutableStateOf("/storage/emulated/0") }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    androidx.compose.runtime.LaunchedEffect(path, refreshTick) {
        val dir = File(path)
        error = when {
            !dir.exists() -> "does not exist: $path"
            !dir.isDirectory -> "not a directory: $path"
            !dir.canRead() -> "not readable: $path"
            else -> null
        }
        entries = if (error == null) {
            runCatching {
                val list = dir.listFiles()?.toList() ?: emptyList()
                list.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            }.getOrDefault(emptyList())
        } else emptyList()
    }

    ScreenScaffold(title = "Files") {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(onClick = {
                    val parent = File(path).parentFile
                    if (parent != null) path = parent.absolutePath
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                }
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { vm.setTerminalCwd(path) }) { Text("Use as terminal cwd") }
                TextButton(onClick = { refreshTick++ }) { Text("Refresh") }
            }
        }

        error?.let {
            item { StatCard("Error", it, accent = MaterialTheme.colorScheme.error, monospace = true) }
        }

        if (entries.isEmpty() && error == null) {
            item { Text("Empty directory", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        items(entries, key = { it.absolutePath }) { file ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (file.isDirectory) Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                    TextButton(
                        onClick = { if (file.isDirectory) path = file.absolutePath },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(file.name, fontFamily = FontFamily.Monospace, maxLines = 1)
                    }
                    Text(
                        if (file.isFile) humanSize(file.length()) else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}