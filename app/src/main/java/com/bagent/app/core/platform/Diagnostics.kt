package com.bagent.app.core.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import com.bagent.app.core.security.KeystoreCrypto
import com.bagent.app.providers.ProviderManager
import com.bagent.app.tools.terminal.CommandRequest
import com.bagent.app.tools.terminal.TerminalService
import kotlinx.coroutines.flow.first
import java.io.File

/** Outcome of a single diagnostic check. */
data class Diagnosis(
    val id: String,
    val title: String,
    val status: Status,
    val detail: String,
    val suggestion: String = ""
) {
    enum class Status { OK, WARN, FAIL }
}

/**
 * Runs real checks against the device, the terminal backends, storage,
 * keystore and the configured provider. Nothing is assumed; failures are
 * reported with a concrete remediation suggestion.
 */
class DiagnosticsEngine(
    private val context: Context,
    private val terminal: TerminalService,
    private val environment: EnvironmentManager,
    private val providers: ProviderManager,
    private val crypto: KeystoreCrypto
) {
    suspend fun run(): List<Diagnosis> {
        val out = mutableListOf<Diagnosis>()
        val caps = environment.capabilities.first().associateBy { it.name }

        // Storage access
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val granted = Environment.isExternalStorageManager()
            out += Diagnosis(
                "storage", "All-files storage access",
                if (granted) Diagnosis.Status.OK else Diagnosis.Status.WARN,
                if (granted) "granted" else "not granted",
                if (granted) "" else "Grant 'All files access' in Android settings for full filesystem work."
            )
        } else {
            out += Diagnosis("storage", "Storage permission", Diagnosis.Status.OK, "legacy storage model", "")
        }

        // App-private write
        val probe = File(context.filesDir, ".bagent_probe")
        val wrote = runCatching { probe.writeText("ok"); probe.readText() == "ok" }.getOrDefault(false)
        probe.delete()
        out += Diagnosis(
            "app_storage", "App storage write", if (wrote) Diagnosis.Status.OK else Diagnosis.Status.FAIL,
            if (wrote) "writable" else "cannot write to app storage", ""
        )

        // Keystore round-trip
        val keyOk = runCatching {
            val token = "probe-${System.currentTimeMillis()}"
            crypto.decrypt(crypto.encrypt(token)) == token
        }.getOrDefault(false)
        out += Diagnosis(
            "keystore", "Encrypted secret storage", if (keyOk) Diagnosis.Status.OK else Diagnosis.Status.FAIL,
            if (keyOk) "AES/GCM keystore round-trip ok" else "keystore unavailable",
            if (keyOk) "" else "Device keystore is unusable; API keys cannot be stored securely."
        )

        // Shell
        val shell = runCatching {
            terminal.run(CommandRequest(command = "echo bagent-ok", shellInterpret = true, timeoutMs = 10_000))
        }.getOrNull()
        val shellOk = shell?.combined?.contains("bagent-ok") == true
        out += Diagnosis(
            "shell", "Terminal backend",
            if (shellOk) Diagnosis.Status.OK else Diagnosis.Status.FAIL,
            (terminal.currentBackend().describe()) + " -> " + (shell?.combined?.trim()?.take(120) ?: "no output"),
            if (shellOk) "" else "Native shell unavailable; install Termux and enable its backend for command execution."
        )

        // Common tool binaries
        val binaries = listOf("git", "python3", "node", "ffmpeg", "curl", "wget")
        val missing = binaries.filter { bin ->
            val cap = caps[bin]
            cap != null && !cap.available
        }
        out += Diagnosis(
            "binaries", "Command-line tools",
            if (missing.isEmpty()) Diagnosis.Status.OK else Diagnosis.Status.WARN,
            if (missing.isEmpty()) "git, python3, node, ffmpeg, curl, wget present" else "missing: ${missing.joinToString(", ")}",
            if (missing.isEmpty()) "" else "Install the missing tools inside Termux (pkg install ...) to enable them."
        )

        // Root
        val root = caps["root"]
        out += Diagnosis(
            "root", "Root access",
            if (root?.available == true) Diagnosis.Status.OK else Diagnosis.Status.WARN,
            root?.detail ?: "unknown",
            if (root?.available == true) "" else "Optional: root enables privileged operations but is not required."
        )

        // Termux
        val termux = caps["termux"]
        out += Diagnosis(
            "termux", "Termux runtime",
            if (termux?.available == true) Diagnosis.Status.OK else Diagnosis.Status.WARN,
            termux?.detail ?: "unknown",
            if (termux?.available == true) "" else "Optional: install Termux for a full Linux toolchain."
        )

        // Accessibility
        val a11y = caps["accessibility"]
        out += Diagnosis(
            "accessibility", "Accessibility service",
            if (a11y?.available == true) Diagnosis.Status.OK else Diagnosis.Status.WARN,
            a11y?.detail ?: "unknown",
            if (a11y?.available == true) "" else "Enable B Agent Accessibility Control in Android settings to control other apps."
        )

        // Notifications
        val notifications = caps["notifications"]
        out += Diagnosis(
            "notifications", "Notifications",
            if (notifications?.available == true) Diagnosis.Status.OK else Diagnosis.Status.WARN,
            notifications?.detail ?: "unknown",
            if (notifications?.available == true) "" else "Allow notifications so long tasks can report progress."
        )

        // Network
        val network = networkState()
        out += Diagnosis(
            "network", "Network connectivity",
            if (network.first) Diagnosis.Status.OK else Diagnosis.Status.FAIL,
            network.second,
            if (network.first) "" else "Connect the device to a network or Wi-Fi to reach cloud models."
        )

        // Provider
        val provider = providers.defaultConfig()
        if (provider == null) {
            out += Diagnosis(
                "provider", "AI provider",
                Diagnosis.Status.FAIL,
                "no provider enabled",
                "Open Settings -> Providers and add an API key or a local model endpoint."
            )
        } else {
            val result = runCatching { providers.test(provider) }
            out += Diagnosis(
                "provider", "AI provider (${provider.name})",
                if (result.isSuccess) Diagnosis.Status.OK else Diagnosis.Status.FAIL,
                result.fold({ it }, { it.message ?: "failed" }),
                if (result.isSuccess) "" else "Check the API key, base URL and model name for '${provider.name}'."
            )
        }

        return out
    }

    private fun networkState(): Pair<Boolean, String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false to "no active network"
        val caps = cm.getNetworkCapabilities(network) ?: return false to "network cap unknown"
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return internet to (if (internet) "connected (${if (metered) "metered" else "unmetered"})" else "no internet capability")
    }
}