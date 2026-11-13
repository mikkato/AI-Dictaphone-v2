package ru.mytolm.aidictaphone.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed class DownloadEvent {
    data class Progress(val downloaded: Long, val total: Long) : DownloadEvent()
    data object Done : DownloadEvent()
    data class Failed(val message: String) : DownloadEvent()
}

class ModelDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun modelsDir(): File {
        val dir = File(context.getExternalFilesDir(null), "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Каталог parts для sherpa-моделей (или корень models для whisper). */
    fun dirFor(model: WhisperModel): File =
        if (model.kind == ModelRegistry.KIND_WHISPER) modelsDir()
        else File(modelsDir(), model.id).apply { mkdirs() }

    fun fileFor(model: WhisperModel, part: ModelPart): File = File(dirFor(model), part.destName)

    /** LLM-модель как одночастевая WhisperModel-задача (GGUF в корень modelsDir). */
    fun asrLike(m: LlmModel): WhisperModel = WhisperModel(
        id = m.id, fileName = m.fileName, sizeBytes = m.sizeBytes, label = m.label,
        sha256 = m.sha256, quant = m.quant, repo = m.repo, kind = ModelRegistry.KIND_WHISPER,
        parts = listOf(ModelPart(m.repoPath, m.fileName, m.sizeBytes, m.sha256)))

    fun installedLlm(m: LlmModel): Boolean {
        val f = File(modelsDir(), m.fileName)
        return f.exists() && f.length() == m.sizeBytes
    }

    suspend fun downloadLlm(m: LlmModel, onProgress: (DownloadEvent) -> Unit): Result<File> =
        download(asrLike(m), null, onProgress)

    fun installed(model: WhisperModel): Boolean =
        ModelRegistry.partsOf(model).all { p ->
            val f = File(dirFor(model), p.destName)
            f.exists() && f.length() == p.sizeBytes
        }

    /**
     * Скачивание всех частей с возобновлением по HTTP Range и проверкой SHA-256 (LFS).
     * Прогресс — суммарный по байтам всех частей.
     */
    suspend fun download(
        model: WhisperModel,
        urlBase: String? = null,
        onProgress: (DownloadEvent) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val parts = ModelRegistry.partsOf(model)
        val totalAll = parts.sumOf { it.sizeBytes }
        val dir = dirFor(model)
        try {
            var doneBase = 0L
            for (part in parts) {
                val target = File(dir, part.destName)
                target.parentFile?.mkdirs() // подкаталоги (tokenizer/ у Qwen3-ASR)
                if (target.exists() && target.length() == part.sizeBytes) {
                    doneBase += part.sizeBytes
                    onProgress(DownloadEvent.Progress(doneBase, totalAll))
                    continue
                }
                val url = ModelRegistry.urlFor(model, part, urlBase)
                val partFile = File(dir, part.destName + ".part")
                var existing = if (partFile.exists()) partFile.length() else 0L
                val builder = Request.Builder().url(url)
                if (existing > 0) builder.header("Range", "bytes=$existing-")
                client.newCall(builder.build()).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 206 && resp.code != 416)
                        throw Exception("HTTP ${resp.code}: недоступен $url")
                    if (!(resp.code == 416 && existing > 0)) {
                        val totalHeader = resp.header("Content-Length")?.toLongOrNull()
                        val total = when {
                            resp.code == 206 && totalHeader != null -> existing + totalHeader
                            totalHeader != null -> totalHeader
                            else -> part.sizeBytes
                        }
                        // сервер не поддержал Range и отдал 200 с нуля — дописывать к хвосту нельзя
                        if (resp.code == 200 && existing > 0) existing = 0L
                        val body = resp.body ?: throw Exception("Пустой ответ сервера")
                        FileOutputStream(partFile, existing > 0).use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(128 * 1024)
                                var read: Int
                                var done = existing
                                onProgress(DownloadEvent.Progress(doneBase + done, totalAll))
                                while (input.read(buf).also { read = it } > 0) {
                                    out.write(buf, 0, read)
                                    done += read
                                    if (done - existing >= 2L * 1024 * 1024 || done == total) {
                                        onProgress(DownloadEvent.Progress(doneBase + done, totalAll))
                                    }
                                }
                            }
                        }
                    }
                }
                if (part.sha256 != null) {
                    val hash = sha256(partFile)
                    if (!hash.equals(part.sha256, ignoreCase = true)) {
                        partFile.delete()
                        onProgress(DownloadEvent.Failed(
                            "Контрольная сумма ${part.destName} не совпала — часть удалена, повторите"))
                        return@withContext Result.failure(
                            Exception("Контрольная сумма не совпала (${part.destName})"))
                    }
                } else if (partFile.length() != part.sizeBytes) {
                    partFile.delete()
                    return@withContext Result.failure(
                        Exception("Размер ${part.destName} не совпал"))
                }
                if (!partFile.renameTo(target)) {
                    return@withContext Result.failure(Exception("Не удалось сохранить ${part.destName}"))
                }
                doneBase += part.sizeBytes
            }
            onProgress(DownloadEvent.Done)
            Notifier.downloadDone(context, model.fileName)
            Result.success(dir)
        } catch (e: Exception) {
            onProgress(DownloadEvent.Failed(e.message ?: "Ошибка сети"))
            Result.failure(e)
        }
    }

    suspend fun sha256(f: File): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } > 0) md.update(buf, 0, read)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }
}
