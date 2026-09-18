package com.bagent.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mediation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bagent.app.ui.screens.AgentScreen
import com.bagent.app.ui.screens.DiagnosticsScreen
import com.bagent.app.ui.screens.ExtensionsScreen
import com.bagent.app.ui.screens.FilesScreen
import com.bagent.app.ui.screens.HomeScreen
import com.bagent.app.ui.screens.ProjectsScreen
import com.bagent.app.ui.screens.SessionsScreen
import com.bagent.app.ui.screens.SettingsScreen
import com.bagent.app.ui.screens.TasksScreen
import com.bagent.app.ui.screens.TerminalScreen
import com.bagent.app.ui.theme.BAgentTheme

/** Top-level navigation destinations. */
enum class Destination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Dashboard),
    AGENT("Agent", Icons.Filled.Build),
    TERMINAL("Terminal", Icons.Filled.Terminal),
    PROJECTS("Projects", Icons.Filled.Folder),
    FILES("Files", Icons.Filled.Description),
    TASKS("Tasks", Icons.Filled.CheckCircle),
    SESSIONS("Sessions", Icons.Filled.Article),
    EXTENSIONS("Extensions", Icons.Filled.Mediation),
    SETTINGS("Settings", Icons.Filled.Settings),
    DIAGNOSTICS("Diagnostics", Icons.Filled.Tune)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: MainViewModel = viewModel()
            var destination by remember { mutableStateOf(Destination.HOME) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            BAgentTheme {
                Scaffold { innerPadding ->
                    NavigationRail(
                        modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                    ) {
                        Destination.entries.forEach { item ->
                            NavigationRailItem(
                                selected = destination == item,
                                onClick = { destination = item },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label, fontSize = 10.sp) }
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier.padding(innerPadding).padding(start = 84.dp),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.background
                    ) {
                        when (destination) {
                            Destination.HOME -> HomeScreen(vm, onNavigate = { destination = it })
                            Destination.AGENT -> AgentScreen(vm)
                            Destination.TERMINAL -> TerminalScreen(vm)
                            Destination.PROJECTS -> ProjectsScreen(vm)
                            Destination.FILES -> FilesScreen(vm)
                            Destination.TASKS -> TasksScreen(vm)
                            Destination.SESSIONS -> SessionsScreen(vm)
                            Destination.EXTENSIONS -> ExtensionsScreen(vm)
                            Destination.SETTINGS -> SettingsScreen(vm)
                            Destination.DIAGNOSTICS -> DiagnosticsScreen(vm)
                        }
                    }
                }
            }
        }
    }
}