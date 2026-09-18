package com.bagent.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

@Composable
fun ExtensionsScreen(vm: MainViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Tools", "Skills", "Plugins", "MCP", "Providers")

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { index, label ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(label, fontSize = 11.sp) }
                )
            }
        }
        when (tab) {
            0 -> ToolsTab(vm)
            1 -> SkillsTab(vm)
            2 -> PluginsTab(vm)
            3 -> McpTab(vm)
            4 -> ProvidersTab(vm)
        }
    }
}

@Composable
private fun ToolsTab(vm: MainViewModel) {
    val tools by vm.tools.collectAsStateWithLifecycle()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text("${tools.size} registered tools", style = MaterialTheme.typography.labelSmall) }
        items(tools, key = { it.id }) { tool ->
            StatCard(
                label = "${tool.category} • ${tool.name} v${tool.version} • ${tool.permission} ${tool.risk}",
                value = tool.description,
                monospace = true,
                accent = if (tool.enabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SkillsTab(vm: MainViewModel) {
    val skills by vm.skills.collectAsStateWithLifecycle()
    var markdown by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(
                value = markdown,
                onValueChange = { markdown = it },
                label = { Text("Paste a Markdown skill (with --- front matter)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 6
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("…or a skill URL") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = {
                    if (url.isNotBlank()) {
                        vm.installSkillFromUrl(url) { result = it }
                    } else {
                        vm.installSkillMarkdown(markdown) { result = it }
                    }
                }) { Text("Install") }
            }
        }
        result?.let {
            item { StatCard("Result", it, accent = MaterialTheme.colorScheme.primary, monospace = true) }
        }
        items(skills, key = { it.id }) { skill ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatCard(label = "${skill.name} ${skill.version} • ${skill.source}", value = skill.description, modifier = Modifier.weight(1f))
                    Column(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Switch(checked = skill.enabled, onCheckedChange = { vm.toggleSkill(skill) })
                    }
                    TextButton(onClick = { vm.removeSkill(skill.id) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun PluginsTab(vm: MainViewModel) {
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    var json by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(
                value = json,
                onValueChange = { json = it },
                label = { Text("Paste a plugin JSON manifest") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 8
            )
        }
        item {
            Button(
                onClick = { vm.installPluginJson(json) { result = it } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Install plugin") }
        }
        result?.let {
            item { StatCard("Result", it, accent = MaterialTheme.colorScheme.primary, monospace = true) }
        }
        items(plugins, key = { it.id }) { plugin ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatCard(
                    label = "${plugin.name} ${plugin.version} • ${plugin.author} • deps ${plugin.dependenciesJson}",
                    value = plugin.description,
                    monospace = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = plugin.enabled, onCheckedChange = { vm.togglePlugin(plugin.id, !plugin.enabled) })
                    TextButton(onClick = { vm.removePlugin(plugin.id) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun McpTab(vm: MainViewModel) {
    val servers by vm.mcpServers.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Server name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Streamable HTTP URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Bearer token (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.addMcpServer(name, url, token) { result = it } }, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
            }
        }
        result?.let {
            item { StatCard("Result", it, accent = MaterialTheme.colorScheme.primary, monospace = true) }
        }
        items(servers, key = { it.id }) { server ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatCard(
                    label = "${server.name} • ${if (server.enabled) "enabled" else "disabled"}",
                    value = server.url,
                    monospace = true
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (vm.container.mcp.isConnected(server.id)) {
                        Text("connected", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall)
                    } else {
                        TextButton(onClick = { vm.connectMcp(server.id) }) { Text("Reconnect") }
                    }
                    TextButton(onClick = { vm.removeMcp(server.id) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun ProvidersTab(vm: MainViewModel) {
    val providers by vm.providers.collectAsStateWithLifecycle()
    val testResult by vm.providerTestResult.collectAsStateWithLifecycle()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { vm.saveProvider(vm.newProviderTemplate()) { } }, modifier = Modifier.weight(1f)) {
                    Text("+ Add OpenAI template")
                }
            }
        }
        testResult?.let {
            item {
                StatCard("Provider test", it, accent = MaterialTheme.colorScheme.primary, monospace = true)
                TextButton(onClick = vm::clearProviderTestResult) { Text("Dismiss") }
            }
        }
        items(providers, key = { it.id }) { provider ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatCard(
                    label = "${provider.name} • ${provider.type} • ${if (provider.isDefault) "DEFAULT" else ""}",
                    value = "${provider.baseUrl} • ${provider.model} • enabled=${provider.enabled}",
                    monospace = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!provider.isDefault) {
                        TextButton(onClick = { vm.setDefaultProvider(provider.id) }) { Text("Make default") }
                    }
                    TextButton(onClick = { vm.testProvider(provider.id) }) { Text("Test") }
                    TextButton(onClick = { vm.deleteProvider(provider.id) }) { Text("Delete") }
                }
            }
        }
    }
}