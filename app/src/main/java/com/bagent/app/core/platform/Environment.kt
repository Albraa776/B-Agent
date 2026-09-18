package com.bagent.app.core.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.app.NotificationManagerCompat
import android.provider.Settings
import com.bagent.app.core.database.EnvironmentCapabilityDao
import com.bagent.app.core.database.EnvironmentCapabilityEntity
import com.bagent.app.core.util.now
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File

/** A single detected environment capability with a human detail string. */
data class Capability(val name: String, val available: Boolean, val detail: String)

/**
 * Scans the device and reports what B Agent can truly do. Every capability is
 * detected (never assumed) and persisted so the Agent can reason about its own
 * constraints honestly.
 */
class EnvironmentManager(
    private val context: Context,
    private val dao: EnvironmentCapabilityDao,
    private val scope: CoroutineScope
) {
    val capabilities: Flow<List<EnvironmentCapabilityEntity>> = dao.observeAll()

    fun scanNow() {
        scope.launch {
            val caps = scan()
            dao.clearAll()
            caps.forEach { dao.upsert(it) }
        }
    }

    suspend fun scanOnce(): List<Capability> {
        val caps = scan()
        dao.clearAll()
        caps.forEach { dao.upsert(it) }
        return caps.map { Capability(it.name, it.available, it.detail) }
    }

    private fun scan(): List<EnvironmentCapabilityEntity> {
        val now = now()
        val out = ArrayList<EnvironmentCapabilityEntity>()
        fun add(name: String, available: Boolean, detail: String) =
            out.add(EnvironmentCapabilityEntity(name, available, detail, now))

        add("android_version", true, "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        add("abi", true, Build.SUPPORTED_ABIS.joinToString(","))

        runCatching {
            val stats = StatFs(context.dataDir.absolutePath)
            val freeMb = stats.availableBytes / 1024 / 1024
            val totalMb = stats.totalBytes / 1024 / 1024
            add("storage", freeMb > 256, "free ${freeMb}MB / total ${totalMb}MB")
        }.onFailure { add("storage", false, "unreadable") }

        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mem = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mem)
            add("ram", true, "total ${mem.totalMem / 1024 / 1024}MB")
        }.onFailure { add("ram", false, "unreadable") }

        add("shell", File("/system/bin/sh").exists() || File("/system/bin/toybox").exists(), "native shell")

        val termuxInstalled = isPackageInstalled("com.termux")
        val termuxBin = File("/data/data/com.termux/files/usr/bin")
        val termuxAccessible = termuxInstalled && termuxBin.isDirectory && termuxBin.canExecute()
        add("termux", termuxAccessible, if (termuxInstalled) "installed: ${termuxBin.path}" else "not installed")

        val rootDetected = detectRoot()
        add("root", rootDetected, if (rootDetected) "su binary present" else "not available")

        listOf(
            "git", "python", "python3", "node", "java", "ffmpeg", "clang",
            "cmake", "rustc", "cargo", "go", "tar", "zip", "grep", "wget", "curl"
        ).forEach { bin ->
            val found = findBinary(bin)
            add(bin, found != null, found ?: "missing")
        }

        val a11yEnabled = isAccessibilityServiceEnabled()
        add("accessibility", a11yEnabled, if (a11yEnabled) "service connected" else "service not enabled")
        add("accessibility_available", true, "declared in manifest")

        val allFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE.let {
                runCatching { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
            }
        }
        add("all_files_access", allFiles, if (allFiles) "granted" else "not granted")

        val notif = runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            .getOrDefault(false)
        add("notifications", notif, if (notif) "enabled" else "disabled")

        add("network", true, "declared; connection checked at runtime")
        return out
    }

    private fun isPackageInstalled(pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    private fun detectRoot(): Boolean {
        val paths = listOf(
            "/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su",
            "/system/app/Superuser.apk", "/data/local/bin/su"
        )
        return paths.any { File(it).exists() }
    }

    private fun findBinary(name: String): String? {
        val dirs = listOf(
            "/data/data/com.termux/files/usr/bin", "/system/bin", "/system/xbin",
            "/system/apex/com.android.conscrypt", "/vendor/bin"
        )
        for (dir in dirs) {
            val f = File(dir, name)
            if (f.isFile) return f.absolutePath
        }
        return null
    }

    private fun isAccessibilityServiceEnabled(): Boolean = runCatching {
        val expected = "${context.packageName}/${context.packageName}.accessibility.BAgentAccessibilityService"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        enabled.split(':').any { it.equals(expected, ignoreCase = true) || it.equals(context.packageName, ignoreCase = true) }
    }.getOrDefault(false)
}