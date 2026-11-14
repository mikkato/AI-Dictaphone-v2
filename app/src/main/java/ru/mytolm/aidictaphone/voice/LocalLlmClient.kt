package ru.mytolm.aidictaphone.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.mytolm.aidictaphone.data.App
import ru.mytolm.aidictaphone.data.LlmClient
import ru.mytolm.aidictaphone.data.LlmRegistry
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Локальная суммаризация GGUF-моделью через llama.cpp (режим «На устройстве»).
 * ASR выгружается перед загрузкой LLM и наоборот — см. ModelManager (защита от OOM).
 */
class LocalLlmClient(private val context: Context) {

    interface Progress {
        fun onStage(stage: String) {}
        fun onChunks(done: Int, total: Int) {}
    }

    fun modelFile(): File? {
        val id = App.instance.settings.localLlmId ?: return null
        val m = LlmRegistry.find(id) ?: return null
        val f = File(App.instance.downloader.modelsDir(), m.fileName)
        return if (f.exists() && f.length() == m.sizeBytes) f else null
    }

    suspend fun summarize(
        transcriptText: String,
        templateId: String,
        detailed: Boolean,
        onToken: LlmClient.TokenCallback,
        progress: Progress = object : Progress {},
    ): String = withContext(Dispatchers.IO) {
        val f = modelFile()
            ?: throw IllegalStateException("Локальная LLM не скачана — выберите её в настройках")
        val m = LlmRegistry.find(App.instance.settings.localLlmId!!)!!
        val threads = App.instance.settings.inferenceThreads

        ModelManager.llmOperation("${m.quant} ${LlmRegistry.locate(m.id)!!.first.id}") {
            progress.onStage("Выгрузка модели распознавания…")
            progress.onStage("Загрузка ${m.quant}: ${(f.length() / 1_048_576)} МБ…")
            LlamaEngine.ensureLoaded(f.absolutePath, threads, maxCtxLen = LlamaEngine.MAX_CTX)
            progress.onStage("Суммаризация локально…")

            val system = LlmClient.systemPrompt(templateId, detailed)
            // чанк под фактическое контекстное окно модели (n_ctx_train, читается при загрузке)
            val ctx = LlamaEngine.contextLength
            val chunkLimit = LlmClient.contextCharLimit(ctx, 1024).coerceAtLeast(1000)
            val chunks = LlmClient.chunkForMapReduce(transcriptText, chunkLimit)
            val temp = App.instance.settings.llm.value.temperature.toFloat()
            val finalInput: String = if (chunks.size == 1) transcriptText
            else {
                val partials = ArrayList<String>()
                for ((i, chunk) in chunks.withIndex()) {
                    coroutineContext.ensureActive()
                    progress.onStage("Фрагмент ${i + 1}/${chunks.size}…")
                    val out = LlamaEngine.chat(
                        "Сделай подробное промежуточное саммари фрагмента протокола на русском, " +
                        "сохрани все факты, решения, задачи и имена.",
                        chunk, maxTokens = 400, temperature = temp)
                    partials.add("Фрагмент ${i + 1}:\n$out")
                    progress.onChunks(i + 1, chunks.size)
                }
                partials.joinToString("\n\n")
            }
            // финальная генерация стримится через PieceCallback (аудит F18):
            // UI печатает по ходу, а не ждёт всю генерацию
            var streamedAny = false
            val finalText = LlamaEngine.chat(system, finalInput, 1024, temp,
                onPiece = { piece ->
                    streamedAny = true
                    onToken.onToken(piece)
                })
            if (!streamedAny && finalText.isNotEmpty()) onToken.onToken(finalText)
            // Qwen3 «thinking»: отрежем размышления до ответа
            val cleaned = finalText.substringAfterLast("</think>").ifBlank { finalText }
                .trim()
            progress.onStage("Готово")
            cleaned
        }
    }
}
