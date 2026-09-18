package com.bagent.app.providers

import com.bagent.app.core.database.ProviderDao
import com.bagent.app.core.database.ProviderEntity
import com.bagent.app.core.logging.BLogger
import com.bagent.app.core.model.ProviderConfig
import com.bagent.app.core.model.ProviderEvent
import com.bagent.app.core.model.ProviderRequest
import com.bagent.app.core.model.ProviderType
import com.bagent.app.core.security.KeystoreCrypto
import com.bagent.app.core.settings.SettingsRepository
import com.bagent.app.core.util.JsonUtil
import com.bagent.app.core.util.Redactor
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns provider configurations, key encryption, provider construction,
 * capability fallback and connection testing.
 */
class ProviderManager(
    private val dao: ProviderDao,
    private val settings: SettingsRepository,
    private val crypto: KeystoreCrypto,
    private val logger: BLogger
) {
    private val cache = ConcurrentHashMap<Long, ProviderConfig>()

    suspend fun refresh() {
        val entities = dao.all()
        cache.clear()
        entities.forEach { entity ->
            val config = entity.toConfig(crypto)
            cache[config.id] = config
            if (config.apiKey.isNotBlank()) Redactor.register(config.apiKey)
            if (config.baseUrl.contains("key=")) Redactor.register(config.baseUrl.substringAfter("key="))
        }
    }

    fun configs(): List<ProviderConfig> = cache.values.sortedBy { it.id }

    fun config(id: Long): ProviderConfig? = cache[id]

    suspend fun defaultConfig(): ProviderConfig? {
        val all = configs()
        return all.firstOrNull { it.isDefault && it.enabled }
            ?: all.firstOrNull { it.enabled }
    }

    fun build(config: ProviderConfig, key: String = config.apiKey): ChatProvider = when (config.type) {
        ProviderType.OPENAI, ProviderType.OPENROUTER, ProviderType.OPENAI_COMPAT, ProviderType.LOCAL ->
            OpenAiProvider(config, key)
        ProviderType.ANTHROPIC -> AnthropicProvider(config, key)
        ProviderType.GEMINI -> GeminiProvider(config, key)
    }

    /**
     * Streams a completion using [providerId] (or the default provider when null).
     * When `settings.providerFallback` is enabled and the primary provider fails,
     * the next enabled provider is tried and a visible note is streamed to the UI.
     * Returns the configuration that produced the successful stream.
     */
    suspend fun stream(
        request: ProviderRequest,
        providerId: Long?,
        onEvent: (ProviderEvent) -> Unit
    ): ProviderConfig {
        val primary = providerId?.let { cache[it] } ?: defaultConfig()
            ?: error("No provider is configured or enabled. Add one under Settings -> Providers.")
        val fallbackEnabled = settings.providerFallback.first()
        val chain = mutableListOf(primary)
        if (fallbackEnabled) {
            chain += configs().filter { c -> c.enabled && c.id != primary.id }
        }
        var lastError = "provider failed"
        chain.forEachIndexed { index, config ->
            var failed = false
            val effective = request.copy(model = request.model.ifBlank { config.model })
            try {
                build(config).stream(effective) { event ->
                    when (event) {
                        is ProviderEvent.Error -> {
                            failed = true
                            lastError = event.message
                        }
                        else -> Unit
                    }
                    onEvent(event)
                }
            } catch (e: Exception) {
                failed = true
                lastError = e.message ?: "provider crashed"
                onEvent(ProviderEvent.Error(lastError))
            }
            if (!failed) return config
            logger.warn("provider_failed: ${config.name} - $lastError")
            val next = chain.getOrNull(index + 1) ?: return@forEach
            onEvent(ProviderEvent.Delta("\n\n_[provider ${config.name} failed: $lastError - falling back to ${next.name}]_\n\n"))
        }
        error(lastError)
    }

    /** Runs a minimal round-trip to verify a provider configuration. */
    suspend fun test(config: ProviderConfig): String {
        val provider = build(config)
        val request = ProviderRequest(
            model = config.model,
            messages = listOf(
                com.bagent.app.core.model.ChatMessage(
                    role = com.bagent.app.core.model.Role.USER,
                    parts = listOf(com.bagent.app.core.model.ContentPart.Text("Reply with the single word: ok"))
                )
            ),
            system = "You are a connectivity probe. Answer with one short word.",
            maxTokens = 16
        )
        val text = StringBuilder()
        var failure: String? = null
        provider.stream(request) { event ->
            when (event) {
                is ProviderEvent.Delta -> text.append(event.text)
                is ProviderEvent.Error -> failure = event.message
                else -> Unit
            }
        }
        failure?.let { error(it) }
        if (text.isBlank()) error("provider returned an empty response")
        return "ok: ${text.toString().trim().take(80)}"
    }

    suspend fun save(config: ProviderConfig): Long {
        val entity = ProviderEntity(
            id = config.id,
            name = config.name.ifBlank { "Provider" },
            type = config.type.name,
            baseUrl = config.baseUrl.trim(),
            model = config.model.trim(),
            apiKeyEnc = if (config.apiKey.isBlank()) "" else crypto.encrypt(config.apiKey),
            headersJson = config.headersJson.toString(),
            contextLimit = config.contextLimit,
            timeoutSec = config.timeoutSec,
            stream = config.stream,
            maxRetries = config.maxRetries,
            enabled = config.enabled,
            isDefault = config.isDefault,
            reasoning = config.reasoning,
            createdAt = if (config.id == 0L) System.currentTimeMillis() else (dao.byId(config.id)?.createdAt ?: System.currentTimeMillis())
        )
        val id = if (entity.isDefault) {
            dao.clearDefaults()
            dao.upsert(entity)
        } else {
            dao.upsert(entity)
        }
        refresh()
        return id
    }

    suspend fun delete(id: Long) {
        cache[id]?.apiKey?.takeIf { it.isNotBlank() }?.let { Redactor.unregister(it) }
        dao.delete(id)
        refresh()
    }

    suspend fun setDefault(id: Long) {
        dao.clearDefaults()
        dao.markDefault(id)
        refresh()
    }

    /** Inserts a disabled local-server template on first run so the UI is not empty. */
    suspend fun ensureSeeded() {
        refresh()
        if (cache.isNotEmpty()) return
        save(
            ProviderConfig(
                id = 0L,
                name = "Local (Ollama / llama.cpp)",
                type = ProviderType.LOCAL,
                baseUrl = "http://127.0.0.1:11434",
                model = "llama3.1",
                apiKey = "",
                headersJson = JsonUtil.parseObject("{}")!!,
                contextLimit = 32_768,
                timeoutSec = 300,
                stream = true,
                maxRetries = 1,
                enabled = false,
                isDefault = false,
                reasoning = ""
            )
        )
    }

    private fun ProviderEntity.toConfig(crypto: KeystoreCrypto): ProviderConfig {
        val key = if (apiKeyEnc.isBlank()) "" else crypto.decrypt(apiKeyEnc) ?: ""
        return ProviderConfig(
            id = id,
            name = name,
            type = runCatching { ProviderType.valueOf(type) }.getOrDefault(ProviderType.OPENAI_COMPAT),
            baseUrl = baseUrl,
            model = model,
            apiKey = key,
            headersJson = JsonUtil.parseObject(headersJson) ?: JsonUtil.parseObject("{}")!!,
            contextLimit = contextLimit,
            timeoutSec = timeoutSec,
            stream = stream,
            maxRetries = maxRetries,
            enabled = enabled,
            isDefault = isDefault,
            reasoning = reasoning
        )
    }
}