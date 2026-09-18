package com.bagent.app.tools.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import com.bagent.app.core.model.PermAction
import com.bagent.app.core.model.RiskLevel
import com.bagent.app.core.util.JsonUtil
import com.bagent.app.tools.ToolBase
import com.bagent.app.tools.ToolEnv
import com.bagent.app.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Android intent and package tools. These operate through public Intents and
 * PackageManager APIs - never bypassing Android's security model.
 */
class AndroidTools(private val context: Context) {

    fun all(): List<ToolBase> = listOf(
        LaunchApp(), OpenUrl(), OpenSettings(), ShareContent(), InstalledApps(),
        PackageInfo(), OpenFile()
    )

    private fun ctx(env: ToolEnv): Context = env.context

    inner class LaunchApp : ToolBase() {
        override val id = "launch_app"
        override val name = "launch_app"
        override val category = "android"
        override val description = "Launch an installed app by package name via its main intent."
        override val requiredPermission = PermAction.SYSTEM_SETTINGS
        override val risk = RiskLevel.MEDIUM
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"packageName":{"type":"string"}},"required":["packageName"]}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val pkg = args["packageName"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Failure("missing packageName")
            val launch = ctx(env).packageManager.getLaunchIntentForPackage(pkg)
            if (launch == null) return ToolResult.Failure("no launch intent for $pkg")
            return runCatching {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx(env).startActivity(launch)
                ToolResult.Success("launched $pkg")
            }.getOrElse { ToolResult.Failure("launch failed: ${it.message}") }
        }
    }

    inner class OpenUrl : ToolBase() {
        override val id = "open_url"
        override val name = "open_url"
        override val category = "android"
        override val description = "Open a URL in the default browser."
        override val requiredPermission = PermAction.NETWORK_ACCESS
        override val risk = RiskLevel.MEDIUM
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Failure("missing url")
            return runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx(env).startActivity(intent)
                ToolResult.Success("opened $url")
            }.getOrElse { ToolResult.Failure("open failed: ${it.message}") }
        }
    }

    inner class OpenSettings : ToolBase() {
        override val id = "open_settings"
        override val name = "open_settings"
        override val category = "android"
        override val description = "Open an Android settings screen (accessibility, battery, security…)."
        override val requiredPermission = PermAction.SYSTEM_SETTINGS
        override val risk = RiskLevel.MEDIUM
        override val parameters = JsonUtil.parseObject(
            """{"type":"object","properties":{"screen":{"type":"string","enum":["accessibility","battery","storage","security","apps","wifi","location","notifications","all_files"]}}}"""
        )
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val screen = args["screen"]?.jsonPrimitive?.contentOrNull ?: "apps"
            val action = when (screen) {
                "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                "security" -> Settings.ACTION_SECURITY_SETTINGS
                "apps" -> Settings.ACTION_APPLICATION_SETTINGS
                "wifi" -> Settings.ACTION_WIFI_SETTINGS
                "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                "notifications" -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
                "all_files" -> Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                else -> Settings.ACTION_SETTINGS
            }
            return runCatching {
                val intent = Intent(action).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (screen == "notifications" || screen == "all_files") putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                ctx(env).startActivity(intent)
                ToolResult.Success("opened $screen settings")
            }.getOrElse { ToolResult.Failure("open failed: ${it.message}") }
        }
    }

    inner class ShareContent : ToolBase() {
        override val id = "share_content"
        override val name = "share_content"
        override val category = "android"
        override val description = "Share text content via the Android share sheet."
        override val risk = RiskLevel.MEDIUM
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Failure("missing text")
            return runCatching {
                val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx(env).startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ToolResult.Success("share sheet opened")
            }.getOrElse { ToolResult.Failure("share failed: ${it.message}") }
        }
    }

    inner class InstalledApps : ToolBase() {
        override val id = "installed_apps"
        override val name = "installed_apps"
        override val category = "android"
        override val description = "List installed packages, optionally filtered by a search term."
        override val risk = RiskLevel.LOW
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer"}}}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val query = args["query"]?.jsonPrimitive?.contentOrNull?.lowercase()
            val limit = (args["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 200).coerceIn(1, 1000)
            val pm = ctx(env).packageManager
            val apps = runCatching { pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0)) }.getOrNull() ?: emptyList()
            val filtered = apps.asSequence()
                .filter { query == null || it.packageName.contains(query) || it.loadLabel(pm).toString().lowercase().contains(query) }
                .sortedBy { it.packageName }
                .take(limit)
                .toList()
            return ToolResult.Success(buildJsonObject {
                put("count", filtered.size)
                put("apps", buildJsonArray {
                    filtered.forEach { app ->
                        add(buildJsonObject {
                            put("packageName", app.packageName)
                            put("label", app.loadLabel(pm).toString())
                            put("system", (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                        })
                    }
                })
            }.toString())
        }
    }

    inner class PackageInfo : ToolBase() {
        override val id = "package_info"
        override val name = "package_info"
        override val category = "android"
        override val description = "Inspect metadata of an installed package."
        override val risk = RiskLevel.LOW
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"packageName":{"type":"string"}},"required":["packageName"]}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val pkg = args["packageName"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Failure("missing packageName")
            return runCatching {
                val pm = ctx(env).packageManager
                val info = pm.getPackageInfo(pkg, 0)
                ToolResult.Success(buildJsonObject {
                    put("packageName", pkg)
                    put("versionName", info.versionName ?: "")
                    put("versionCode", info.longVersionCode)
                    put("firstInstallTime", info.firstInstallTime)
                    put("lastUpdateTime", info.lastUpdateTime)
                    put("uid", info.applicationInfo?.uid)
                }.toString())
            }.getOrElse { ToolResult.Failure("package not found or not accessible: $pkg") }
        }
    }

    inner class OpenFile : ToolBase() {
        override val id = "open_file"
        override val name = "open_file"
        override val category = "android"
        override val description = "Open a workspace file with the system's default viewer."
        override val requiredPermission = PermAction.READ_FILES
        override val risk = RiskLevel.MEDIUM
        override val parameters = JsonUtil.parseObject("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""")
        override suspend fun execute(args: JsonObject, env: ToolEnv): ToolResult {
            val path = args["path"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Failure("missing path")
            val root = env.workspace?.let { com.bagent.app.tools.filesystem.FsService(ctx(env)).workspaceDir(it) }
                ?: File(ctx(env).filesDir, "workspaces/0")
            val file = runCatching {
                com.bagent.app.tools.filesystem.FsService(ctx(env)).resolveInside(root, path)
            }.getOrNull() ?: return ToolResult.Failure("invalid path")
            if (!file.isFile) return ToolResult.Failure("not a file")
            return runCatching {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx(env), ctx(env).packageName + ".fileprovider", file
                )
                val mime = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(file.extension.lowercase())
                    ?: "application/octet-stream"
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx(env).startActivity(intent)
                ToolResult.Success("opened ${file.name}")
            }.getOrElse { ToolResult.Failure("open failed: ${it.message}") }
        }
    }
}

private val MIME_GUESS: Map<String, String> = mapOf(
    ".png" to "image/png", ".jpg" to "image/jpeg", ".jpeg" to "image/jpeg", ".gif" to "image/gif",
    ".webp" to "image/webp", ".pdf" to "application/pdf", ".txt" to "text/plain",
    ".md" to "text/markdown", ".json" to "application/json", ".xml" to "application/xml",
    ".kt" to "text/x-kotlin", ".java" to "text/x-java", ".py" to "text/x-python", ".js" to "text/javascript",
    ".zip" to "application/zip", ".mp4" to "video/mp4", ".mp3" to "audio/mpeg"
)