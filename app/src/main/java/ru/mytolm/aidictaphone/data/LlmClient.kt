package ru.mytolm.aidictaphone.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class LlmException(val code: Int, message: String) : Exception(message)

/**
 * OpenAI-совместимый клиент (ТЗ §6). Audio в LLM не отправляется — только текст.
 * Ключ никогда не логируется.
 */
class LlmClient(private val settings: SettingsRepository) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    fun interface TokenCallback { fun onToken(chunk: String) }

    private fun baseUrl() = settings.llm.value.baseUrl.trim().trimEnd('/')

    /** GET /v1/models или минимальный chat-запрос. */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val s = settings.llm.value
            val url = baseUrl() + "/models"
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer ${s.apiKey}").get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Result.success("Подключение успешно")
                } else if (resp.code == 404 || resp.code == 401 || resp.code == 403) {
                    // некоторые эндпоинты не отдают /models — пробуем chat
                    chatOnce("Скажи одно слово: ок", maxTokens = 8)
                } else {
                    Result.failure(LlmException(resp.code, "Сервер ответил ${resp.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** GET /v1/models -> список id моделей (пустой список, если эндпоинт их не отдаёт). */
    suspend fun fetchModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val s = settings.llm.value
            val req = Request.Builder().url(baseUrl() + "/models")
                .header("Authorization", "Bearer ${s.apiKey}").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful)
                    return@use Result.failure<List<String>>(
                        LlmException(resp.code, "Сервер ответил ${resp.code}"))
                val body = resp.body?.string() ?: ""
                val root = try { json.parseToJsonElement(body).jsonObject }
                           catch (_: Exception) { return@use Result.success(emptyList()) }
                val data = root["data"]?.jsonArray ?: JsonArray(emptyList())
                val ids = data.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                // контекстное окно: автоопределение, если провайдер отдаёт его в /v1/models
                detectContextWindow(data)?.let { settings.contextWindow = it }
                Result.success(ids)
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    private val CTX_KEYS = listOf(
        "context_length", "context_window", "max_model_len",
        "max_context_length", "context_size"
    )

    /** Пытается вытащить размер контекстного окна из ответа /v1/models (OpenRouter и др.). */
    private fun detectContextWindow(data: JsonArray): Int? {
        var found: Int? = null
        for (el in data) {
            val obj = el as? JsonObject ?: continue
            for (k in CTX_KEYS) {
                val v = obj[k]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                if (v != null && v > 0) { found = maxOf(found ?: 0, v); break }
            }
        }
        return found
    }

    private suspend fun chatOnce(prompt: String, maxTokens: Int): Result<String> {
        val body = buildChatBody(prompt, stream = false, maxTokensOverride = maxTokens)
        return Result.success(postChat(body, null))
    }

    private fun buildChatBody(
        userContent: String,
        stream: Boolean,
        system: String? = null,
        maxTokensOverride: Int? = null,
    ): String {
        val s = settings.llm.value
        val messages = buildJsonArray {
            if (system != null) add(buildJsonObject {
                put("role", "system"); put("content", system)
            })
            add(buildJsonObject {
                put("role", "user"); put("content", userContent)
            })
        }
        return buildJsonObject {
            put("model", s.model)
            put("messages", messages)
            put("temperature", s.temperature)
            put("max_tokens", maxTokensOverride ?: s.maxTokens)
            put("stream", stream)
        }.toString()
    }

    private fun postChat(body: String, onToken: TokenCallback?): String {
        val s = settings.llm.value
        val req = Request.Builder()
            .url(baseUrl() + "/chat/completions")
            .header("Authorization", "Bearer ${s.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            when {
                resp.code == 401 -> throw LlmException(401, "Ошибка 401: проверьте API-ключ")
                resp.code == 404 -> throw LlmException(404, "Ошибка 404: проверьте имя модели")
                !resp.isSuccessful -> throw LlmException(resp.code, "Сервер ответил ${resp.code}")
            }
            if (onToken == null) {
                val text = resp.body?.string() ?: throw IOException("Пустой ответ")
                return extractContent(text)
            }
            // SSE streaming
            val sb = StringBuilder()
            val reader = resp.body?.byteStream()?.bufferedReader()
            if (reader != null) {
                var partial = ""
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    try {
                        val obj = json.parseToJsonElement(payload).jsonObject
                        val delta = obj["choices"]?.jsonArray?.firstOrNull()
                            ?.jsonObject?.get("delta")?.jsonObject
                            ?.get("content")?.jsonPrimitive?.contentOrNull
                        if (!delta.isNullOrEmpty()) {
                            sb.append(delta)
                            partial += delta
                            if (partial.length >= 48) {
                                onToken.onToken(partial); partial = ""
                            }
                        }
                    } catch (_: Exception) { /* keep-alive/комментарии */ }
                }
                if (partial.isNotEmpty()) onToken.onToken(partial)
                reader.close()
            }
            return sb.toString()
        }
    }

    private fun extractContent(responseBody: String): String = try {
        json.parseToJsonElement(responseBody).jsonObject["choices"]
            ?.jsonArray?.firstOrNull()?.jsonObject?.get("message")
            ?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull ?: ""
    } catch (e: Exception) {
        throw LlmException(0, "Некорректный ответ сервера: ${e.message}")
    }

    /** Retry ×3 с экспоненциальной задержкой для timeout/5xx (ТЗ §6). */
    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: LlmException) {
                if (e.code == 401 || e.code == 404) throw e
                attempt++
                if (attempt > 3) throw e
                kotlinx.coroutines.delay((1000L * 2.0.pow(attempt)).toLong())
            } catch (e: IOException) {
                attempt++
                if (attempt > 3) throw LlmException(0, "Сеть недоступна: ${e.message}")
                kotlinx.coroutines.delay((1000L * 2.0.pow(attempt)).toLong())
            }
        }
    }

    // ---------- промпты ----------

    companion object {
        const val MIN_TRANSCRIPT_CHARS = 200 // порог Samsung (ТЗ §6)

        val BUILTIN_TEMPLATES = mapOf(
            "protocol" to "Протокол",
            "call" to "Звонок",
        )

        fun systemPrompt(templateId: String, detailed: Boolean): String {
            val base = "Ты — протоколист совещаний. Верни структурированное саммари на русском: " +
                "**Краткое резюме**; **Ключевые обсуждения**; **Решения**; " +
                "**Задачи/обязанные (кто/что/срок)**; **Открытые вопросы**."
            val style = if (detailed)
                " Стиль Detailed: раскрой каждый раздел максимально подробно, с опорой на конкретные реплики и таймстампы."
            else " Стиль Standard: сжато, только существенное."
            val byTemplate = when (templateId) {
                "call" -> " Это телефонный звонок: фиксируй договорённости и обещания сторон."
                else -> " Это протокол совещания: зафиксируй повестку, решения и задачи по участникам."
            }
            return base + style + byTemplate
        }

        /**
         * Сборка пользовательского текста: транскрипт + хайлайты + заметки (ТЗ §6).
         * Чистая функция — покрыта unit-тестом.
         */
        fun buildTranscriptText(
            segments: List<Pair<String, String>>, // "мм:сс Спикер" -> text
            highlights: List<Pair<String, String>>,
            notes: List<Pair<String, String>>,
        ): String = buildString {
            if (highlights.isNotEmpty()) {
                appendLine("Ключевые моменты, отмеченные пользователем:")
                highlights.forEach { (t, lbl) -> appendLine("  [$t] $lbl") }
                appendLine()
            }
            if (notes.isNotEmpty()) {
                appendLine("Текстовые заметки пользователя:")
                notes.forEach { (t, txt) -> appendLine("  [$t] $txt") }
                appendLine()
            }
            appendLine("Транскрипт:")
            segments.forEach { (hdr, txt) -> appendLine("[$hdr] $txt") }
        }

        /** Разбивка на чанки ≤ limit по границам строк (map-reduce, ТЗ §6). */
        fun chunkForMapReduce(text: String, limit: Int): List<String> {
            if (text.length <= limit) return listOf(text)
            val chunks = ArrayList<String>()
            var rest = text
            while (rest.length > limit) {
                var cut = rest.lastIndexOf('\n', limit)
                if (cut < limit / 2) cut = limit
                chunks.add(rest.substring(0, cut))
                rest = rest.substring(cut).trimStart('\n')
            }
            if (rest.isNotBlank()) chunks.add(rest)
            return chunks
        }

        /**
         * Максимум символов одного чанка, который гарантированно помещается в контекстное
         * окно модели с резервом на системный промпт и выходные токены. Чистая функция.
         */
        fun contextCharLimit(contextWindow: Int, maxOutputTokens: Int, charsPerToken: Int = 3): Int {
            val reserve = maxOutputTokens + 256 // системный промпт и накладные расходы
            val avail = (contextWindow - reserve).coerceAtLeast(512)
            return avail * charsPerToken
        }
    }

    /**
     * Полная суммаризация с map-reduce для длинных транскриптов, SSE-стримингом
     * финального ответа и retry. onToken — потоковые куски финального саммари.
     */
    suspend fun summarize(
        transcriptText: String,
        templateId: String,
        detailed: Boolean,
        onToken: TokenCallback,
        onChunkProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        if (transcriptText.length < MIN_TRANSCRIPT_CHARS)
            throw LlmException(0,
                "Транскрипт слишком короткий (< $MIN_TRANSCRIPT_CHARS символов) — саммаризация недоступна")
        val s = settings.llm.value
        // чанк не должен превышать контекстное окно (автоопределённое или заданное)
        val ctxLimit = contextCharLimit(settings.contextWindow, s.maxTokens)
        val limit = minOf(s.chunkCharLimit, ctxLimit).coerceAtLeast(256)
        val system = systemPrompt(templateId, detailed)
        val chunks = chunkForMapReduce(transcriptText, limit)

        val partials = ArrayList<String>()
        for ((i, chunk) in chunks.withIndex()) {
            if (chunks.size == 1) break
            val partial = withRetry {
                postChat(
                    buildChatBody(chunk, stream = false,
                        system = "Сделай подробное промежуточное саммари этого фрагмента протокола, сохрани все факты, решения, задачи и имена. Пиши на русском."),
                    null
                )
            }
            partials.add("Фрагмент ${i + 1}/${chunks.size}:\n$partial")
            onChunkProgress(i + 1, chunks.size)
        }
        val finalInput = if (partials.isEmpty()) transcriptText
                         else partials.joinToString("\n\n")
        withRetry {
            postChat(buildChatBody(finalInput, stream = true, system = system), onToken)
        }
    }
}
