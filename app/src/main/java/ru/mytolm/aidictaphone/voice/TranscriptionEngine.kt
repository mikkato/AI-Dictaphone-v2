package ru.mytolm.aidictaphone.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.mytolm.aidictaphone.data.*
import ru.mytolm.aidictaphone.util.TimeFmt
import java.io.File
import kotlin.math.ceil

/**
 * Пайплайн транскрипции (ТЗ §5): декод → чанки 10 мин (перекрытие 2 с) →
 * whisper_full → сшивка → Room. Прогресс реальный, возобновление с чанка.
 * UI-операции не блокируются: вызывается только из фоновых корутин/WorkManager.
 */
class TranscriptionEngine(
    private val context: Context,
    private val dao: DictaphoneDao,
    private val settings: SettingsRepository,
    private val downloader: ModelDownloader,
) {

    private val mutex = Mutex() // один whisper-контекст за раз (RSS ≤ 2.5 ГБ, ТЗ §7)
    private var ctxHandle = 0L
    private var ctxModel: String? = null
    private var backendReady = false
    @Volatile var cancelled = false

    interface ProgressListener {
        fun onProgress(noteId: Long, percent: Int) {}
        fun onSegment(noteId: Long, text: String) {}
    }

    class TranscriptionError(message: String, val needsSmallerModel: Boolean = false) : Exception(message)

    private fun ensureBackend() {
        if (backendReady) return
        val ok = WhisperNative.initBackend(context.applicationInfo.nativeLibraryDir)
        if (!ok) throw TranscriptionError("Не удалось загрузить CPU-бэкенд ggml")
        backendReady = true
    }

    /** Полная выгрузка whisper-контекста (освобождение RAM под LLM). */
    fun releaseAll() {
        if (ctxHandle != 0L) {
            runCatching { WhisperNative.freeModel(ctxHandle) }
            ctxHandle = 0L; ctxModel = null
        }
    }

    /** Загрузка/переиспользование модели + сам-тест (§2, illegal instruction guard). */
    private fun ensureModel(modelPath: String) {
        ensureBackend()
        if (ctxHandle != 0L && ctxModel == modelPath) return
        if (ctxHandle != 0L) { WhisperNative.freeModel(ctxHandle); ctxHandle = 0L }
        ctxHandle = WhisperNative.loadModel(modelPath)
        if (ctxHandle == 0L)
            throw TranscriptionError("Не удалось загрузить модель: проверьте файл $modelPath")
        // сам-тест 1 с синтетики: при битом наборе инструкций ggml упал бы здесь
        // процессом; если вернул ошибку — бэкенд непригоден.
        if (!WhisperNative.selfTest(ctxHandle)) {
            WhisperNative.freeModel(ctxHandle); ctxHandle = 0L
            throw TranscriptionError("Сам-тест декодирования не пройден на этом CPU")
        }
        settings.selfTestPassed = true
        ctxModel = modelPath
    }

    /** декод mp3/wav → float PCM 16 кГц mono */
    fun decodeToPcm(file: File): Pair<FloatArray, Long> {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".wav") -> {
                val w = WavParser.parseFile(file)   // потоково: без OOM на длинных WAV
                Pair(w.samples, w.samples.size * 1000L / 16000)
            }
            name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".mp4") ||
                name.endsWith(".ogg") || name.endsWith(".3gp") || name.endsWith(".aac") ||
                name.endsWith(".amr") -> {
                // MediaCodec контейнеры: MP3/AAC(MP4)/OGG — аудио-дорожка выбирается по MIME (§5.1)
                val d = Mp3Decoder.decode(file)
                Pair(d.samples, d.durationMs)
            }
            else -> {
                // пробуем как WAV, при неудаче — как MediaCodec-контейнер
                try {
                    val w = WavParser.parseFile(file)
                    Pair(w.samples, w.samples.size * 1000L / 16000)
                } catch (e: Exception) {
                    try {
                        val d = Mp3Decoder.decode(file)
                        Pair(d.samples, d.durationMs)
                    } catch (e2: Exception) {
                        throw TranscriptionError(
                            "Неподдерживаемый аудиоформат «${file.extension}». Поддерживаются mp3 и wav.")
                    }
                }
            }
        }
    }

    /**
     * Транскрибация заметки. Возобновляемая: уже принятые сегменты остаются,
     * продолжение — от конца последнего сегмента (resumption с чанка, ТЗ §5.3).
     */
    suspend fun transcribe(noteId: Long, listener: ProgressListener) = mutex.withLock {
        cancelled = false
        val note = dao.note(noteId) ?: return
        dao.setNoteStatus(noteId, NoteStatus.TRANSCRIBING)
        try {
            val kind = settings.asrKind
            ModelManager.enterAsr("ASR · " + (ModelRegistry.find(settings.activeModelId ?: "")
                ?.let { "${it.quant} ${it.fileName}" } ?: "whisper"))
            val modelPath = if (kind == ModelRegistry.KIND_WHISPER) resolveModelPath()
                            else resolveSherpaDir(kind).absolutePath
            val audio = File(note.audioPath)
            if (!audio.exists())
                throw TranscriptionError("Аудиофайл потерян: ${audio.name}")

            val (pcm, durationMs) = decodeToPcm(audio)
            if (note.durationMs != durationMs)
                dao.updateNote(note.copy(durationMs = durationMs))

            val chunks = if (kind == ModelRegistry.KIND_WHISPER) ChunkPlanner.plan(durationMs)
                         else SherpaPlan.chunkPlan(durationMs)
            val resumeFromMs = dao.lastSegmentEndMs(noteId) ?: -1L
            val accepted = dao.segments(noteId).map {
                ChunkPlanner.AcceptedSegment(it.startMs, it.endMs, it.text)
            }

            val vocab = settings.customVocabulary
            val prompt = if (vocab.isEmpty()) null
                         else "Словарь: " + vocab.joinToString(", ")

            var stitched = accepted
            for (chunk in chunks) {
                if (cancelled) break
                // возобновление с чанка: пропускаем полностью покрытые segments
                if (resumeFromMs > 0 && chunk.endMs <= resumeFromMs) continue

                if (kind == ModelRegistry.KIND_WHISPER) ensureModel(modelPath)
                val fromMs = maxOf(chunk.startMs,
                    if (resumeFromMs > 0) resumeFromMs - ChunkPlanner.OVERLAP_MS else chunk.startMs)
                val startSample = (fromMs * 16).toInt().coerceIn(0, pcm.size)
                val endSample = (chunk.endMs * 16).toInt().coerceAtMost(pcm.size)
                if (endSample <= startSample) continue
                val chunkPcm = pcm.copyOfRange(startSample, endSample)
                val absOffsetMs = startSample * 1000L / 16000

                val raw = ArrayList<Pair<Long, String>>()
                if (kind == ModelRegistry.KIND_WHISPER) {
                    val rc = WhisperNative.transcribeChunk(
                        ctxHandle, chunkPcm, absOffsetMs,
                        settings.whisperLanguage, settings.inferenceThreads, prompt,
                        object : WhisperNative.SegmentCallback {
                            override fun onSegment(startMs: Long, text: String) {
                                raw.add(Pair(startMs, text))
                            }
                        }
                    )
                    if (rc != 0)
                        throw TranscriptionError("Whisper вернул код ошибки $rc при обработке фрагмента " +
                            "${TimeFmt.clock(chunk.startMs)}–${TimeFmt.clock(chunk.endMs)}")
                } else {
                    raw.addAll(SherpaEngine.recognize(
                        context, kind, File(modelPath), chunkPcm,
                        settings.inferenceThreads, absOffsetMs))
                }

                // stitch только дописывает хвост (принятые сегменты не меняются) —
                // в БД уходит новый хвост, а не весь список (раньше: clear+re-insert
                // всех сегментов на каждый чанк = O(n²) записей на длинных записях)
                val prevCount = stitched.size
                stitched = ChunkPlanner.stitch(stitched, absOffsetMs, raw)
                if (stitched.size > prevCount) {
                    dao.insertSegments(stitched.drop(prevCount).map {
                        Segment(noteId = noteId, startMs = it.startMs,
                            endMs = maxOf(it.endMs, it.startMs + 1), text = it.text)
                    })
                }
                val pct = ceil(100.0 * chunk.endMs / durationMs).toInt().coerceIn(0, 100)
                dao.setProgress(noteId, pct)
                listener.onProgress(noteId, pct)
            }

            if (cancelled) {
                dao.setNoteStatus(noteId, NoteStatus.AUDIO, prog = 0)
                return
            }
            val segs = dao.segments(noteId)
            if (segs.isEmpty() && durationMs > 3000)
                throw TranscriptionError("Транскрипт пуст — возможно, в аудио нет речи")
            dao.setNoteStatus(noteId, NoteStatus.TRANSCRIPT, prog = 100)
            maybeAutoTitle(noteId, segs)
        } catch (e: OutOfMemoryError) {
            // §7: OOM → предложить меньшую модель
            dao.setNoteStatus(noteId, NoteStatus.ERROR,
                "Недостаточно памяти для текущей модели. Скачайте версию q8_0 или q5_0 в настройках.")
        } catch (e: TranscriptionError) {
            dao.setNoteStatus(noteId, NoteStatus.ERROR, e.message)
        } catch (e: Exception) {
            Log.e("TranscriptionEngine", "transcribe failed", e)
            dao.setNoteStatus(noteId, NoteStatus.ERROR, e.message ?: "Внутренняя ошибка")
        }
    }

    private suspend fun maybeAutoTitle(noteId: Long, segs: List<Segment>) {
        val note = dao.note(noteId) ?: return
        val auto = android.text.format.DateFormat.format("dd.MM.yyyy HH:mm", note.createdAt).toString()
        if (note.title == auto || note.title.isBlank()) {
            val first = segs.firstOrNull()?.text?.trim()?.split(" ")?.take(6)?.joinToString(" ")
            if (!first.isNullOrBlank()) dao.setTitle(noteId, first)
        }
    }

    /** Каталог активной sherpa-модели (modelsDir/<id>) с проверкой полноты частей. */
    private fun resolveSherpaDir(kind: String): File {
        ModelRegistry.find(settings.activeModelId ?: "")?.let { m ->
            if (downloader.installed(m)) return downloader.dirFor(m)
        }
        for (m in ModelRegistry.MODELS) {
            if (m.kind == kind && downloader.installed(m)) return downloader.dirFor(m)
        }
        throw TranscriptionError("Модель не скачана — загрузите её на экране «Модели»")
    }

    /** Модель: пользовательский путь (SAF-копия уже в приложении) или первый установленный GGML из реестра. */
    private fun resolveModelPath(): String {
        settings.whisperModelFile?.let { p ->
            if (File(p).exists()) return p
        }
        // первая установленная whisper-модель — возвращаем ФАЙЛ .bin, не каталог
        for (m in ModelRegistry.MODELS) {
            if (m.kind != ModelRegistry.KIND_WHISPER) continue
            if (downloader.installed(m)) {
                val part = ModelRegistry.partsOf(m).first()
                return File(downloader.dirFor(m), part.destName).absolutePath
            }
        }
        throw TranscriptionError("Модель Whisper не найдена — скачайте её на экране «Модели»")
    }
}
