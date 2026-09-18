package com.bagent.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import com.bagent.app.ui.components.StatCard

@Composable
fun SessionsScreen(vm: MainViewModel) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val selectedId by vm.selectedSessionId.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()

    ScreenScaffold(title = "Sessions") {
        item {
            Button(onClick = { vm.newSession() }, modifier = Modifier.fillMaxWidth()) { Text("New session +") }
        }
        sessions.forEach { session ->
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { vm.selectSession(session.id) }, modifier = Modifier.weight(1f)) {
                            Text(
                                session.title.ifBlank { "Untitled" },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1
                            )
                        }
                        Text(
                            "${sesMsgCount(session.id, session.updatedAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (session.id == selectedId) {
                            Text("active", color = MaterialTheme.colorScheme.secondary)
                        }
                        TextButton(onClick = { vm.deleteSession(session.id) }) { Text("Delete") }
                    }
                    Text(
                        "created ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(session.createdAt))} • " +
                            if (session.model.isNotBlank()) "model ${session.model}" else "default model",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        if (selectedId != null) {
            item {
                StatCard("Selected session", "#$selectedId")
            }
        }
    }
}

private fun sesMsgCount(sessionId: Long, updated: Long): String {
    return "updated ${java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(updated))}"
}