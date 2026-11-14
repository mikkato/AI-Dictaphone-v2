package ru.mytolm.aidictaphone.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import ru.mytolm.aidictaphone.data.ModelRegistry
import java.io.File

/**
 * Рантайм sherpa-onnx (фаза 2): GigaAM (CTC/RNNT), Parakeet TDT, Qwen3-ASR.
 * Один распознаватель в памяти (переключается при смене модели), CPU, 16 кГц.
 */
object SherpaEngine {

    private var recognizer: OfflineRecognizer? = null
    private var key: String = ""

    private fun f(dir: File, vararg names: String): String {
        for (n in names) {
            val x = File(dir, n)
            if (x.exists()) return x.absolutePath
        }
        throw TranscriptionEngine.TranscriptionError(
            "В каталоге модели нет файла ${names.joinToString(" или ")} — доскачайте модель")
    }

    fun recognizerFor(context: Context, kind: String, dir: File, threads: Int): OfflineRecognizer {
        val k = kind + "@" + dir.absolutePath + "@t$threads"
        recognizer?.let { if (key == k) return it }
        recognizer?.release()
        recognizer = null
        val modelConfig = when (kind) {
            ModelRegistry.KIND_TRANSDUCER -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = f(dir, "encoder.int8.onnx", "encoder.onnx",
                        "gigaam_v3_e2e_rnnt_encoder_int8.onnx", "gigaam_v3_rnnt_encoder_int8.onnx"),
                    decoder = f(dir, "decoder.int8.onnx", "decoder.onnx",
                        "gigaam_v3_e2e_rnnt_decoder.onnx", "gigaam_v3_rnnt_decoder.onnx"),
                    joiner = f(dir, "joiner.int8.onnx", "joiner.onnx",
                        "gigaam_v3_e2e_rnnt_joint.onnx", "gigaam_v3_rnnt_joint.onnx"),
                ),
                tokens = f(dir, "tokens.txt", "gigaam_v3_e2e_rnnt_tokens.txt",
                    "gigaam_v3_rnnt_tokens.txt"),
                numThreads = threads, provider = "cpu", debug = false,
            )
            ModelRegistry.KIND_NEMO_CTC -> OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(
                    model = f(dir, "gigaam_v3_e2e_ctc_int8.onnx", "gigaam_v3_ctc_int8.onnx",
                        "model.onnx")),
                tokens = f(dir, "gigaam_v3_e2e_ctc_tokens.txt", "gigaam_v3_ctc_tokens.txt",
                    "tokens.txt"),
                numThreads = threads, provider = "cpu", debug = false,
            )
            ModelRegistry.KIND_QWEN3 -> {
                val tokDir = File(dir, "tokenizer")
                if (!File(tokDir, "vocab.json").exists() || !File(tokDir, "merges.txt").exists())
                    throw TranscriptionEngine.TranscriptionError(
                        "Модель Qwen3-ASR неполная: нет tokenizer/vocab.json или tokenizer/merges.txt — перекачайте модель")
                OfflineModelConfig(
                    qwen3Asr = OfflineQwen3AsrModelConfig(
                        convFrontend = f(dir, "conv_frontend.onnx"),
                        encoder = f(dir, "encoder.int8.onnx", "encoder.onnx"),
                        decoder = f(dir, "decoder.int8.onnx", "decoder.onnx"),
                        tokenizer = tokDir.absolutePath,
                    ),
                    numThreads = threads, provider = "cpu", debug = false,
                )
            }
            else -> throw TranscriptionEngine.TranscriptionError("Неизвестный тип модели: $kind")
        }
        val cfg = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = modelConfig,
        )
        val rec = try {
            OfflineRecognizer(assetManager = null, config = cfg)
        } catch (e: Exception) {
            throw TranscriptionEngine.TranscriptionError(
                "Не удалось инициализировать модель распознавания ($kind) из ${dir.absolutePath}: ${e.message}")
        }
        recognizer = rec
        key = k
        return rec
    }

    /**
     * Распознавание куска PCM16 16 кГц. Возвращает сегменты с абсолютными
     * startMs/endMs — разбивка по предложениям через таймстампы токенов
     * (если движок их отдаёт), иначе один сегмент на кусок.
     */
    fun recognize(
        context: Context, kind: String, dir: File, pcm: FloatArray,
        threads: Int, offsetMs: Long,
    ): List<ChunkPlanner.SegmentTriple> {
        val rec = recognizerFor(context, kind, dir, threads)
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(pcm, 16000)
            rec.decode(stream)
            val res = rec.getResult(stream)
            val text = res.text.trim()
            if (text.isEmpty()) return emptyList()
            val toks = res.tokens
            val ts = res.timestamps
            if (toks.isEmpty() || ts.isEmpty() || ts.size != toks.size) {
                val durMs = pcm.size * 1000L / 16000
                return listOf(ChunkPlanner.SegmentTriple(
                    offsetMs, offsetMs + durMs.coerceAtLeast(1), text))
            }
            return SherpaPlan.groupSentences(toks, ts, offsetMs)
        } finally {
            stream.release()
        }
    }


    fun release() {
        recognizer?.release(); recognizer = null; key = ""
    }
}

/** Чистая логика без нативных зависимостей — покрыта юнит-тестами. */
object SherpaPlan {

    private val SENTENCE_ENDERS = charArrayOf('.', '!', '?', ';')

    /** Токены -> предложения по концам . ! ? ; с абсолютными startMs/endMs. */
    fun groupSentences(
        toks: Array<String>, ts: FloatArray, offsetMs: Long,
    ): List<ChunkPlanner.SegmentTriple> {
        val out = ArrayList<ChunkPlanner.SegmentTriple>()
        val cur = StringBuilder()
        var startMs = offsetMs
        var lastMs = offsetMs
        var has = false
        for ((i, t) in toks.withIndex()) {
            val ms = offsetMs + (ts[i] * 1000).toLong()
            if (!has) { startMs = ms; has = true }
            lastMs = ms
            cur.append(t)
            val tr = t.trim()
            if (tr.isNotEmpty() && tr.last() in SENTENCE_ENDERS) {
                val str = cur.toString().replace("  ", " ").trim()
                if (str.isNotEmpty())
                    out.add(ChunkPlanner.SegmentTriple(
                        startMs, maxOf(lastMs, startMs + 1), str))
                cur.setLength(0); has = false
            }
        }
        if (cur.isNotBlank())
            out.add(ChunkPlanner.SegmentTriple(
                startMs, maxOf(lastMs, startMs + 1),
                cur.toString().replace("  ", " ").trim()))
        return out
    }

    /** Чанки без перекрытия: 25 с (лимит аудио-токенов LLM-моделей типа Qwen3). */
    fun chunkPlan(durationMs: Long, chunkMs: Long = 25_000L): List<ChunkPlanner.Chunk> {
        val out = ArrayList<ChunkPlanner.Chunk>()
        var s = 0L; var i = 0
        if (durationMs <= 0) return out
        while (s < durationMs) {
            val e = minOf(s + chunkMs, durationMs)
            out.add(ChunkPlanner.Chunk(i, s, e))
            s = e; i++
        }
        return out
    }
}
