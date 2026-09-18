package com.bagent.app.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bagent.app.core.model.PermAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bagent_settings")

/** Typed settings repository backed by Jetpack DataStore. */
class SettingsRepository(private val context: Context) {

    private object K {
        val language = stringPreferencesKey("language")
        val theme = stringPreferencesKey("theme")
        val maxIterations = intPreferencesKey("max_iterations")
        val maxSameRetries = intPreferencesKey("max_same_retries")
        val maxParallel = intPreferencesKey("max_parallel")
        val commandTimeoutSec = longPreferencesKey("command_timeout_sec")
        val taskTimeoutMin = longPreferencesKey("task_timeout_min")
        val retryCount = intPreferencesKey("retry_count")
        val autoApprove = stringPreferencesKey("auto_approve")
        val backgroundExec = booleanPreferencesKey("background_exec")
        val contextBudget = intPreferencesKey("context_budget")
        val logLevel = stringPreferencesKey("log_level")
        val debugMode = booleanPreferencesKey("debug_mode")
        val providerFallback = booleanPreferencesKey("provider_fallback")
        val storageRoot = stringPreferencesKey("storage_root")
        val bootCompleted = booleanPreferencesKey("boot_completed")
        val rootEnabled = booleanPreferencesKey("root_enabled")
        val rootConfigured = booleanPreferencesKey("root_configured")
        val termuxEnabled = booleanPreferencesKey("termux_enabled")
        val accessibilityEnabled = booleanPreferencesKey("accessibility_enabled")
        val defaultWorkspaceId = longPreferencesKey("default_workspace")
        val defaultProviderId = longPreferencesKey("default_provider")
    }

    val language: Flow<String> = context.dataStore.data.map { it[K.language] ?: "en" }
    val theme: Flow<String> = context.dataStore.data.map { it[K.theme] ?: "dark" }
    val maxIterations: Flow<Int> = context.dataStore.data.map { it[K.maxIterations] ?: 100 }
    val maxSameRetries: Flow<Int> = context.dataStore.data.map { it[K.maxSameRetries] ?: 5 }
    val maxParallel: Flow<Int> = context.dataStore.data.map { it[K.maxParallel] ?: 2 }
    val commandTimeoutSec: Flow<Long> = context.dataStore.data.map { it[K.commandTimeoutSec] ?: 120L }
    val taskTimeoutMin: Flow<Long> = context.dataStore.data.map { it[K.taskTimeoutMin] ?: 60L }
    val retryCount: Flow<Int> = context.dataStore.data.map { it[K.retryCount] ?: 2 }
    val backgroundExec: Flow<Boolean> = context.dataStore.data.map { it[K.backgroundExec] ?: true }
    val contextBudget: Flow<Int> = context.dataStore.data.map { it[K.contextBudget] ?: 64000 }
    val logLevel: Flow<String> = context.dataStore.data.map { it[K.logLevel] ?: "info" }
    val debugMode: Flow<Boolean> = context.dataStore.data.map { it[K.debugMode] ?: false }
    val providerFallback: Flow<Boolean> = context.dataStore.data.map { it[K.providerFallback] ?: true }
    val storageRoot: Flow<String?> = context.dataStore.data.map { it[K.storageRoot] }
    val bootCompleted: Flow<Boolean> = context.dataStore.data.map { it[K.bootCompleted] ?: false }
    val rootConfigured: Flow<Boolean> = context.dataStore.data.map { it[K.rootConfigured] ?: false }

    /** Permissions currently set to auto-approve. */
    val autoApprove: Flow<Set<PermAction>> = context.dataStore.data.map { prefs ->
        (prefs[K.autoApprove] ?: "").split(",").mapNotNull { s ->
            PermAction.entries.firstOrNull { it.name == s }
        }.toSet()
    }

    // ---- setters ----
    suspend fun setLanguage(v: String) = context.dataStore.edit { it[K.language] = v }
    suspend fun setTheme(v: String) = context.dataStore.edit { it[K.theme] = v }
    suspend fun setMaxIterations(v: Int) = context.dataStore.edit { it[K.maxIterations] = v }
    suspend fun setMaxSameRetries(v: Int) = context.dataStore.edit { it[K.maxSameRetries] = v }
    suspend fun setMaxParallel(v: Int) = context.dataStore.edit { it[K.maxParallel] = v }
    suspend fun setCommandTimeoutSec(v: Long) = context.dataStore.edit { it[K.commandTimeoutSec] = v }
    suspend fun setTaskTimeoutMin(v: Long) = context.dataStore.edit { it[K.taskTimeoutMin] = v }
    suspend fun setRetryCount(v: Int) = context.dataStore.edit { it[K.retryCount] = v }
    suspend fun setBackgroundExec(v: Boolean) = context.dataStore.edit { it[K.backgroundExec] = v }
    suspend fun setContextBudget(v: Int) = context.dataStore.edit { it[K.contextBudget] = v }
    suspend fun setLogLevel(v: String) = context.dataStore.edit { it[K.logLevel] = v }
    suspend fun setDebugMode(v: Boolean) = context.dataStore.edit { it[K.debugMode] = v }
    suspend fun setProviderFallback(v: Boolean) = context.dataStore.edit { it[K.providerFallback] = v }
    suspend fun setStorageRoot(v: String) = context.dataStore.edit { it[K.storageRoot] = v }
    suspend fun setBootCompleted(v: Boolean) = context.dataStore.edit { it[K.bootCompleted] = v }
    suspend fun setRootConfigured(v: Boolean) = context.dataStore.edit { it[K.rootConfigured] = v }
    suspend fun setRootEnabled(v: Boolean) = context.dataStore.edit { it[K.rootEnabled] = v }
    suspend fun setTermuxEnabled(v: Boolean) = context.dataStore.edit { it[K.termuxEnabled] = v }
    suspend fun setAccessibilityEnabled(v: Boolean) = context.dataStore.edit { it[K.accessibilityEnabled] = v }
    suspend fun setDefaultWorkspace(id: Long) = context.dataStore.edit { it[K.defaultWorkspaceId] = id }
    suspend fun setDefaultProvider(id: Long) = context.dataStore.edit { it[K.defaultProviderId] = id }

    suspend fun setAutoApprove(perms: Set<PermAction>) = context.dataStore.edit {
        it[K.autoApprove] = perms.joinToString(",") { p -> p.name }
    }

    suspend fun setAutoApproveFor(perm: PermAction, enabled: Boolean) {
        val current = autoApprove.firstOrNull() ?: emptySet()
        val next = if (enabled) current + perm else current - perm
        setAutoApprove(next)
    }
}