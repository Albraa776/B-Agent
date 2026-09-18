package com.bagent.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bagent.app.core.model.AgentState

/** The standard window chrome used by every feature screen. */
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    headerExtra: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
                headerExtra?.invoke()
            }
        }
        content()
    }
}

@Composable
fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    monospace: Boolean = false
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = if (monospace) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                else MaterialTheme.typography.titleMedium,
                color = accent
            )
        }
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        content()
    }
}

@Composable
fun StatusChip(state: AgentState) {
    val (label, color) = when (state) {
        AgentState.IDLE -> "idle" to MaterialTheme.colorScheme.onSurfaceVariant
        AgentState.THINKING, AgentState.PLANNING -> "working" to MaterialTheme.colorScheme.tertiary
        AgentState.EXECUTING_TOOL, AgentState.WAITING_FOR_PROCESS, AgentState.OBSERVING,
        AgentState.RECOVERING -> "working" to MaterialTheme.colorScheme.primary
        AgentState.WAITING_FOR_PERMISSION -> "waiting" to MaterialTheme.colorScheme.tertiary
        AgentState.PAUSED -> "paused" to MaterialTheme.colorScheme.onSurfaceVariant
        AgentState.COMPLETED -> "completed" to MaterialTheme.colorScheme.secondary
        AgentState.FAILED, AgentState.BLOCKED -> "failed" to MaterialTheme.colorScheme.error
        AgentState.CANCELLED -> "cancelled" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.background(
            color.copy(alpha = 0.18f),
            RoundedCornerShape(6.dp)
        ).padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontSize = 11.sp)
    }
}

/** A one-line permission request dialog shown while the agent waits. */
@Composable
fun PermissionSheet(
    title: String,
    reason: String,
    onAllowOnce: () -> Unit,
    onAllowAlways: () -> Unit,
    onAllowSession: () -> Unit,
    onDeny: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onAllowOnce, modifier = Modifier.weight(1f)) { Text("Allow once") }
                Button(onClick = onAllowAlways, modifier = Modifier.weight(1f)) { Text("Always") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onAllowSession, modifier = Modifier.weight(1f)) { Text("For this session") }
                TextButton(onClick = onDeny, modifier = Modifier.weight(1f)) { Text("Deny") }
            }
        }
    }
}