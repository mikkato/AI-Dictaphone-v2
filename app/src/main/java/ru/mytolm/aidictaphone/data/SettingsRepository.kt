package ru.mytolm.aidictaphone.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Настройки. API-ключ — только здесь (EncryptedSharedPreferences, Android Keystore),
 * никогда не логируется, в экспорт заметки не попадает (§6).
 *
 * Холодный старт: инициализация Keystore/EncryptedSharedPreferences дорогая
 * (~100-300 мс), поэтому шифрованное хранилище создается лениво при первом
 * обращении к настройкам LLM. Тема — не секрет и хранится в обычных prefs,
 * чтобы DictaphoneTheme не дёргал Keystore на каждом запуске.
 */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext

    private val masterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    }
    private val prefs: android.content.SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            appContext, "settings", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
    /** Немедленные, нешифрованные: читаются до первого Keystore-обращения. */
    private val uiPrefs by lazy {
        appContext.getSharedPreferences("ui", Context.MODE_PRIVATE)
    }

    // Низкоуровневые геттеры/сеттеры: убирают повсеместное prefs.edit()…apply().
    private fun str(key: String, def: String): String = prefs.getString(key, def) ?: def
    private fun strOrNull(key: String): String? = prefs.getString(key, null)
    private fun put(key: String, v: String?) { prefs.edit().putString(key, v).apply() }
    private fun putInt(key: String, v: Int) { prefs.edit().putInt(key, v).apply() }
    private fun putBool(key: String, v: Boolean) { prefs.edit().putBoolean(key, v).apply() }

    data class LlmSettings(
        val baseUrl: String, val apiKey: String, val model: String,
        val temperature: Double, val maxTokens: Int, val chunkCharLimit: Int,
    )

    // ленивые потоки: не читают prefs (и Keystore) до первого подписчика
    private val _llm by lazy { MutableStateFlow(readLlm()) }
    val llm: StateFlow<LlmSettings> get() = _llm.asStateFlow()

    /** Тема интерфейса: "system" | "light" | "dark" (Настройки → Оформление) */
    private val _theme by lazy { MutableStateFlow(uiPrefs.getString("theme_mode", "system") ?: "system") }
    val theme: StateFlow<String> get() = _theme.asStateFlow()
    fun saveTheme(mode: String) {
        uiPrefs.edit().putString("theme_mode", mode).apply()
        _theme.value = mode
    }

    /** Material You: цвета из обоев (аудит UX-1). По умолчанию включена. */
    private val _dynamicTheme by lazy { MutableStateFlow(uiPrefs.getBoolean("dynamic_theme", true)) }
    val dynamicTheme: StateFlow<Boolean> get() = _dynamicTheme.asStateFlow()
    fun saveDynamicTheme(v: Boolean) {
        uiPrefs.edit().putBoolean("dynamic_theme", v).apply()
        _dynamicTheme.value = v
    }

    private fun readLlm() = LlmSettings(
        baseUrl = prefs.getString("llm_base_url", "") ?: "",
        apiKey = prefs.getString("llm_api_key", "") ?: "",
        model = prefs.getString("llm_model", "gpt-4o-mini") ?: "gpt-4o-mini",
        temperature = prefs.getFloat("llm_temperature", 0.3f).toDouble(),
        maxTokens = prefs.getInt("llm_max_tokens", 2048),
        chunkCharLimit = prefs.getInt("llm_chunk_limit", 8000),
    )

    fun saveLlm(baseUrl: String, apiKey: String, model: String,
                temperature: Float, maxTokens: Int, chunkLimit: Int) {
        prefs.edit()
            .putString("llm_base_url", normalizeBaseUrl(baseUrl))
            .putString("llm_api_key", apiKey)
            .putString("llm_model", model)
            .putFloat("llm_temperature", temperature)
            .putInt("llm_max_tokens", maxTokens)
            .putInt("llm_chunk_limit", chunkLimit)
            .apply()
        _llm.value = readLlm()
    }

    /** base URL оканчивается на /v1 автоматически (§4.5). */
    fun normalizeBaseUrl(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (u.isNotEmpty() && !u.endsWith("/v1")) u += "/v1"
        return u
    }

    /** Максимальное контекстное окно облачной LLM в токенах (вход+выход).
     *  По умолчанию 8192; автоопределяется из /v1/models, если провайдер его отдаёт. */
    var contextWindow: Int
        get() = prefs.getInt("llm_context_window", 8192)
        set(v) = putInt("llm_context_window", v)

    // ---- Суммаризация: cloud | local ----
    var summarizeMode: String
        get() = str("summarize_mode", "cloud")
        set(v) = put("summarize_mode", v)

    var localLlmId: String?
        get() = strOrNull("local_llm_id")
        set(v) = put("local_llm_id", v)

    // ---- ASR ----
    var asrKind: String
        get() = str("asr_kind", "whisper")
        set(v) = put("asr_kind", v)

    var activeModelId: String?
        get() = strOrNull("active_model_id")
        set(v) = put("active_model_id", v)

    // ---- Whisper ----
    var whisperModelFile: String?
        get() = strOrNull("whisper_model")
        set(v) = put("whisper_model", v)

    var customModelUrl: String
        get() = str("whisper_custom_url", "")
        set(v) = put("whisper_custom_url", v)

    var whisperLanguage: String
        get() = str("whisper_language", "auto")
        set(v) = put("whisper_language", v)

    var customVocabulary: List<String>
        get() = str("custom_vocab", "")
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = put("custom_vocab", v.joinToString("\n"))

    val inferenceThreads: Int
        get() {
            val saved = prefs.getInt("inference_threads", 0)
            if (saved > 0) return saved
            return (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
        }

    fun setInferenceThreads(n: Int) { putInt("inference_threads", n) }

    // ---- сам-тест CPU (Tensor pitfall, §2) ----
    var selfTestPassed: Boolean
        get() = prefs.getBoolean("selftest_ok", false)
        set(v) = putBool("selftest_ok", v)
}
