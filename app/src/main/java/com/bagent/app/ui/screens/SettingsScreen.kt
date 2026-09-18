package com.bagent.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bagent.app.core.model.PermAction
import com.bagent.app.core.model.ProviderConfig
import com.bagent.app.core.model.ProviderType
import com.bagent.app.core.util.JsonUtil
import com.bagent.app.ui.MainViewModel
import com.bagent.app.ui.components.ScreenScaffold
import com.bagent.app.ui.components.Section
import com.bagent.app.ui.components.StatCard

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val providers by vm.providers.collectAsStateWithLifecycle()
    val autoApprove by vm.container.settings.autoApprove.collectAsStateWithLifecycle(emptySet())
    val language by vm.container.settings.language.collectAsStateWithLifecycle("en")
    val contextBudget by vm.container.settings.contextBudget.collectAsStateWithLifecycle(64_000)
    val maxIterations by vm.container.settings.maxIterations.collectAsStateWithLifecycle(100)
    val fallback by vm.container.settings.providerFallback.collectAsStateWithLifecycle(true)

    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    ScreenScaffold(title = "Settings") {
        item {
            Section("Providers") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    providers.forEach { provider ->
                        StatCard(
                            label = "${provider.name} • ${provider.type} ${if (provider.isDefault) "• default" else ""}",
                            value = "${provider.baseUrl}\nmodel: ${provider.model}",
                            monospace = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { editing = provider }) { Text("Edit") }
                            if (!provider.isDefault) TextButton(onClick = { vm.setDefaultProvider(provider.id) }) { Text("Default") }
                            TextButton(onClick = { vm.testProvider(provider.id) }) { Text("Test") }
                            TextButton(onClick = { vm.deleteProvider(provider.id) }) { Text("Delete") }
                        }
                    }
                    OutlinedButton(onClick = { editing = vm.newProviderTemplate() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Add provider")
                    }
                    saveMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        editing?.let { config ->
            item {
                ProviderEditor(
                    initial = config,
                    onCancel = { editing = null },
                    onSave = { updated ->
                        vm.saveProvider(updated) { message ->
                            saveMessage = message
                            if (message == "Saved") editing = null
                        }
                    }
                )
            }
        }

        item {
            Section("Permissions (auto-approve)") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PermAction.entries.forEach { action ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(action.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "risk: ${riskLabel(action)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = action in autoApprove,
                                onCheckedChange = { vm.setAutoApprove(action, it) }
                            )
                        }
                    }
                }
            }
        }

        item {
            Section("General") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("en" to "English", "ar" to "العربية").forEach { (code, label) ->
                            FilterChip(
                                selected = language == code,
                                onClick = { vm.setLanguage(code) },
                                label = { Text(label) }
                            )
                        }
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Provider fallback", modifier = Modifier.weight(1f))
                        Switch(checked = fallback, onCheckedChange = { vm.setProviderFallback(it) })
                    }
                    NumberField("Context budget (tokens)", contextBudget) { vm.setContextBudget(it) }
                    NumberField("Max iterations", maxIterations) { vm.setMaxIterations(it) }
                }
            }
        }

        item {
            Section("Environment") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = vm::scanEnvironment, modifier = Modifier.fillMaxWidth()) {
                        Text("Re-scan device capabilities")
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, onCommit: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { text.toIntOrNull()?.let(onCommit) }) { Text("Apply") }
    }
}

@Composable
private fun ProviderEditor(
    initial: ProviderConfig,
    onCancel: () -> Unit,
    onSave: (ProviderConfig) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var type by remember(initial) { mutableStateOf(initial.type) }
    var baseUrl by remember(initial) { mutableStateOf(initial.baseUrl) }
    var model by remember(initial) { mutableStateOf(initial.model) }
    var apiKey by remember(initial) { mutableStateOf(initial.apiKey) }
    var contextLimit by remember(initial) { mutableStateOf(initial.contextLimit.toString()) }
    var timeout by remember(initial) { mutableStateOf(initial.timeoutSec.toString()) }
    var reasoning by remember(initial) { mutableStateOf(initial.reasoning) }
    var enabled by remember(initial) { mutableStateOf(initial.enabled) }
    var isDefault by remember(initial) { mutableStateOf(initial.isDefault) }

    Section("Edit provider") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ProviderType.entries.forEach { candidate ->
                    FilterChip(
                        selected = type == candidate,
                        onClick = { type = candidate },
                        label = { Text(candidate.name, fontSize = 10.sp) }
                    )
                }
            }
            OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = contextLimit, onValueChange = { contextLimit = it }, label = { Text("Context limit") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = timeout, onValueChange = { timeout = it }, label = { Text("Timeout (s)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = reasoning, onValueChange = { reasoning = it }, label = { Text("Reasoning (low/medium/high, optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Enabled", modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Default", modifier = Modifier.weight(1f))
                Switch(checked = isDefault, onCheckedChange = { isDefault = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onSave(
                            initial.copy(
                                name = name,
                                type = type,
                                baseUrl = baseUrl,
                                model = model,
                                apiKey = apiKey,
                                contextLimit = contextLimit.toIntOrNull() ?: initial.contextLimit,
                                timeoutSec = timeout.toIntOrNull() ?: initial.timeoutSec,
                                reasoning = reasoning,
                                enabled = enabled,
                                isDefault = isDefault,
                                headersJson = initial.headersJson.takeIf { initial.id != 0L }
                                    ?: JsonUtil.parseObject("{}")!!
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Save") }
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            }
        }
    }
}

private fun riskLabel(action: PermAction): String = when (action) {
    PermAction.READ_FILES, PermAction.WRITE_FILES, PermAction.NETWORK_ACCESS -> "low"
    PermAction.DELETE_FILES, PermAction.EXECUTE_COMMANDS, PermAction.PROCESS_CONTROL -> "high"
    else -> "critical"
}