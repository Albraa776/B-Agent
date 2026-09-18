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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bagent.app.core.platform.Diagnosis
import com.bagent.app.ui.MainViewModel
import com.bagent.app.ui.components.StatCard

@Composable
fun DiagnosticsScreen(vm: MainViewModel) {
    val results by vm.diagnostics.collectAsStateWithLifecycle()
    val running by vm.diagnosticsRunning.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (results.isEmpty()) vm.runDiagnostics()
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Button(onClick = vm::runDiagnostics, enabled = !running) {
                    Text(if (running) "Running…" else "Run diagnostics")
                }
                Text(
                    "${results.count { it.status == Diagnosis.Status.OK }} pass • " +
                        "${results.count { it.status == Diagnosis.Status.WARN }} warn • " +
                        "${results.count { it.status == Diagnosis.Status.FAIL }} fail",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(results, key = { it.id }) { diagnosis ->
            val color = when (diagnosis.status) {
                Diagnosis.Status.OK -> MaterialTheme.colorScheme.secondary
                Diagnosis.Status.WARN -> MaterialTheme.colorScheme.tertiary
                Diagnosis.Status.FAIL -> MaterialTheme.colorScheme.error
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                StatCard(
                    label = "${diagnosis.status.name} • ${diagnosis.title}",
                    value = diagnosis.detail,
                    accent = color,
                    monospace = true
                )
                if (diagnosis.suggestion.isNotBlank()) {
                    Text(
                        "Fix: ${diagnosis.suggestion}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.SansSerif
                    )
                }
            }
        }
    }
}