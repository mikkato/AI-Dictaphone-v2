package ru.mytolm.aidictaphone.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.mytolm.aidictaphone.data.*
import ru.mytolm.aidictaphone.util.TimeFmt
import java.io.File
import kotlin.math.ceil

/**
 * Пайплайн транскрипции (ТЗ §5): потоковый декод окнами → чанки 10 мин
 * (перекрытие 2 с) → whisper_full / sherpa → сшивка → Room. Прогресс реальный,
 * возобновление с чанка. Декод whole-file в Java-куче удалён (аудит S1):
 * PCM читается окнами под текущий чанк через [PcmSource].
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
        /** Воркер пробрасывает сюда свою остановку (WorkManager isStopped). */
        fun isCancelled(): Boolean = false
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

    /**
     * Потоковый источник PCM 16 кГц (аудит S1): WAV — по окнам с seeks,
     * контейнеры — MediaCodec вперёд с удержанием 3 с хвоста. Если ни один
     * путь не дал длительности — фолбэк whole-file (короткие файлы без
     * метаданных), как раньше.
     */
    private fun openPcmSource(file: File): PcmSource {
        val name = file.name.lowercase()
        val looksMedia = name.endsWith(".mp3") || name.endsWith(".m4a") ||
            name.endsWith(".mp4") || name.endsWith(".ogg") || name.endsWith(".3gp") ||
            name.endsWith(".aac") || name.endsWith(".amr")
        fun wav(): PcmSource? = try { WavPcmSource.open(file) } catch (_: Exception) { null }
        fun media(): PcmSource? = try { Mp3PcmSource.open(file) } catch (_: Exception) { null }
        val src = if (looksMedia) media() ?: wav() else wav() ?: media()
        if (src != null) return src
        // фолбэк: WAV-парсер по всему файлу (короткие файлы без метаданных)
        val fallback = try {
            val w = WavParser.parseFile(file)
            MemoryPcmSource(w.samples)
        } catch (e: Exception) {
            try {
                val d = Mp3Decoder.decode(file)
                MemoryPcmSource(d.samples)
            } catch (e2: Exception) {
                throw TranscriptionError(
                    "Неподдерживаемый аудиоформат «${file.extension}». Поддерживаются mp3 и wav.")
            }
        }
        if (fallback.totalSamples == 0L)
            throw TranscriptionError("Пустое аудио: ${file.name}")
        return fallback
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

            val source = openPcmSource(audio)
            val durationMs = source.durationMs
            try {
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
                    if (cancelled || listener.isCancelled()) { cancelled = true; break }
                    // возобновление с чанка: пропускаем полностью покрытые segments
                    if (resumeFromMs > 0 && chunk.endMs <= resumeFromMs) continue

                    if (kind == ModelRegistry.KIND_WHISPER) ensureModel(modelPath)
                    val fromMs = maxOf(chunk.startMs,
                        if (resumeFromMs > 0) resumeFromMs - ChunkPlanner.OVERLAP_MS else chunk.startMs)
                    val startSample = (fromMs * 16).coerceIn(0, source.totalSamples)
                    val endSample = (chunk.endMs * 16).coerceAtMost(source.totalSamples)
                    if (endSample <= startSample) continue
                    // окно читается из источника по требованию (аудит S1) —
                    // пик памяти = один чанк, а не весь файл
                    val chunkPcm = source.readWindow(startSample, endSample)
                    val absOffsetMs = startSample * 1000L / 16000

                    val raw = ArrayList<ChunkPlanner.SegmentTriple>()
                    if (kind == ModelRegistry.KIND_WHISPER) {
                        val rc = WhisperNative.transcribeChunk(
                            ctxHandle, chunkPcm, absOffsetMs,
                            settings.whisperLanguage, settings.inferenceThreads, prompt,
                            object : WhisperNative.SegmentCallback {
                                override fun onSegment(startMs: Long, endMs: Long, text: String) {
                                    raw.add(ChunkPlanner.SegmentTriple(startMs, endMs, text))
                                }
                                // опрашивается из whisper progress_callback — реальный
                                // обрыв инференса посреди чанка (аудит F6)
                                override fun shouldCancel(): Boolean =
                                    cancelled || listener.isCancelled()
                            }
                        )
                        if (rc != 0 && !cancelled && !listener.isCancelled())
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
            } finally {
                source.close()
            }

            if (cancelled || listener.isCancelled()) {
                // мягкая отмена: принятые сегменты остаются, статус — AUDIO, prog=0
                // (запись в NonCancellable — воркер уже остановлен, обычная корутина упадёт)
                cancelled = true
                withContext(NonCancellable) {
                    dao.setNoteStatus(noteId, NoteStatus.AUDIO, prog = 0)
                }
                return
            }
            val segs = dao.segments(noteId)
            if (segs.isEmpty() && durationMs > 3000)
                throw TranscriptionError("Транскрипт пуст — возможно, в аудио нет речи")
            dao.setNoteStatus(noteId, NoteStatus.TRANSCRIPT, prog = 100)
            maybeAutoTitle(noteId, segs)
        } catch (e: CancellationException) {
            // воркер отменён во время блокирующего вызова: фиксируем мягкую отмену
            // в NonCancellable-контексте и пробрасываем дальше
            withContext(NonCancellable) {
                dao.setNoteStatus(noteId, NoteStatus.AUDIO, prog = 0)
            }
            throw e
        } catch (e: OutOfMemoryError) {
            // §7: OOM → предложить меньшую модель
            dao.setNoteStatus(noteId, NoteStatus.ERROR,
                "Недостаточно памяти для текущей модели. Скачайте версию q8_0 или q5_0 в настройках.")
        } catch (e: TranscriptionError) {
            dao.setNoteStatus(noteId, NoteStatus.ERROR, e.message)
        } catch (e: Exception) {
            Log.e("TranscriptionEngine", "transcribe failed", e)
            dao.setNoteStatus(noteId, NoteStatus.ERROR, e.message ?: "Внутренняя ошибка")
        } finally {
            // резидентная ASR после завершения транскрипции выгрузится по таймауту (S6)
            ModelManager.scheduleIdleUnload()
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
