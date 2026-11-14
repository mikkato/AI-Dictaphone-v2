package ru.mytolm.aidictaphone.voice

import java.io.File

/**
 * Потоковый источник mono float32 PCM 16 кГц (аудит S1): вместо whole-file
 * FloatArray (1 ч ≈ 230 МБ Java-кучи) окна читаются по требованию под чанк,
 * отработанные хвосты сбрасываются.
 *
 * Инвариант: readWindow вызывается монотонно по startSample (чанки идут по
 * возрастанию; перекрытие назад ограничено [RETAIN_SAMPLES]).
 */
interface PcmSource {
    /** Длительность в мс по метаданным источника. */
    val durationMs: Long
    /** Всего сэмплов 16 кГц. */
    val totalSamples: Long
    /** Сэмплы [startSample, endSample) в 16 кГц mono float32. */
    fun readWindow(startSample: Long, endSample: Long): FloatArray
    fun close()
    companion object {
        /** Буфер назад: перекрывающиеся чанки (whisper OVERLAP 2 с) влезают целиком. */
        const val RETAIN_SAMPLES = 48_000L   // 3 с
    }
}

/** Источник из уже декодированного массива (фолбэк, когда поток недоступен). */
class MemoryPcmSource(private val data: FloatArray) : PcmSource {
    override val durationMs = data.size * 1000L / 16000
    override val totalSamples = data.size.toLong()
    override fun readWindow(startSample: Long, endSample: Long): FloatArray {
        val a = startSample.toInt().coerceIn(0, data.size)
        val b = endSample.toInt().coerceIn(a, data.size)
        return data.copyOfRange(a, b)
    }
    override fun close() {}
}

/**
 * WAV-источник: RIFF-заголовок читается один раз, окна декодируются с seeks.
 * Ресемплинг глобально детерминированный (out[i] ← src[i*sr/16000]), поэтому
 * окно, собранное из произвольного числа чтений, байт-в-байт совпадает с
 * whole-file вариантом WavParser.parseFile.
 */
class WavPcmSource private constructor(
    private val raf: java.io.RandomAccessFile,
    private val info: WavParser.WavFileInfo,
) : PcmSource {

    override val durationMs: Long
    override val totalSamples: Long

    init {
        durationMs = info.frameCount * 1000 / info.sampleRate
        totalSamples = if (info.sampleRate == 16000) info.frameCount
                       else info.frameCount * 16000 / info.sampleRate
    }

    /** Декодирует кадры источника [from, until) в mono float (без ресемплинга). */
    private fun frames(from: Long, until: Long): FloatArray {
        val n = (until - from).coerceAtLeast(0).toInt()
        val bytesPerFrame = info.bytesPerFrame
        val raw = ByteArray(n * bytesPerFrame)
        raf.seek(info.dataOffset + from * bytesPerFrame)
        raf.readFully(raw)
        val out = FloatArray(n)
        val bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (o in 0 until n) {
            var acc = 0.0
            for (c in 0 until info.channels) {
                val off = (o * info.channels + c) * info.bytesPerSample
                val v = when {
                    info.audioFormat == 3 -> bb.getFloat(off).toDouble()
                    info.bits == 16 -> bb.getShort(off).toInt() / 32768.0
                    info.bits == 24 -> {
                        val r0 = (raw[off].toInt() and 0xFF) or
                            ((raw[off + 1].toInt() and 0xFF) shl 8) or
                            ((raw[off + 2].toInt() and 0xFF) shl 16)
                        (if (r0 and 0x800000 != 0) r0 or 0xFF000000.toInt() else r0) / 8388608.0
                    }
                    else -> ((raw[off].toInt() and 0xFF) - 128) / 128.0
                }
                acc += v
            }
            out[o] = (acc / info.channels).toFloat()
        }
        return out
    }

    override fun readWindow(startSample: Long, endSample: Long): FloatArray {
        val a = startSample.coerceIn(0, totalSamples)
        val b = endSample.coerceIn(a, totalSamples)
        val n = (b - a).toInt()
        if (n == 0) return FloatArray(0)
        if (info.sampleRate == 16000) return frames(a, b)
        // окно источника, из которого интерполируются выходы [a, b)
        val ratio = info.sampleRate.toDouble() / 16000
        val srcStart = (a * ratio).toLong().coerceAtMost(info.frameCount - 1)
        val srcEnd = minOf((b * ratio).toLong() + 2, info.frameCount)
        val win = frames(srcStart, srcEnd)
        val out = FloatArray(n)
        for (i in a until b) {
            val srcPos = i * ratio
            val i0 = srcPos.toLong()
            val frac = (srcPos - i0).toFloat()
            fun at(idx: Long): Float = when {
                idx < srcStart -> 0f
                idx - srcStart < win.size -> win[(idx - srcStart).toInt()]
                else -> win[win.size - 1]
            }
            val va = at(i0)
            val vb = if (i0 + 1 < info.frameCount) at(i0 + 1) else va
            out[(i - a).toInt()] = va + (vb - va) * frac
        }
        return out
    }

    override fun close() { try { raf.close() } catch (_: Exception) {} }

    companion object {
        fun open(file: File): WavPcmSource {
            val raf = java.io.RandomAccessFile(file, "r")
            return try {
                WavPcmSource(raf, WavParser.parseFileHeader(file, raf))
            } catch (e: Exception) {
                try { raf.close() } catch (_: Exception) {}
                throw e
            }
        }
    }
}

/**
 * MediaCodec-источник для контейнеров (mp3/aac/m4a/mp4/ogg…): декод идёт строго
 * вперёд малыми порциями, буфер удерживает только хвост [start-3 с .. end].
 * Методика ресемплинга и моно-микса — одна и та же, что делал Mp3Decoder.
 */
class Mp3PcmSource private constructor(
    private val extractor: android.media.MediaExtractor,
    private val codec: android.media.MediaCodec,
    private var inRateHz: Int,
    private var inChannels: Int,
    override val durationMs: Long,
) : PcmSource {

    override val totalSamples: Long = durationMs * 16

    private val queue = ArrayDeque<FloatArray>()
    private var queueStart = 0L      // абсолютный индекс 16k-сэмпла первой строки queue
    private var produced = 0L        // всего сэмплировано (16k)
    private var inputDone = false
    private var outputDone = false
    private var discardBefore = 0L
    private val info = android.media.MediaCodec.BufferInfo()

    override fun readWindow(startSample: Long, endSample: Long): FloatArray {
        val a = startSample.coerceAtLeast(0)
        val b = endSample.coerceAtLeast(a)
        if (a < discardBefore)
            throw IllegalStateException("PcmSource: окно назад за буфер ($a < $discardBefore)")
        while (produced < b && !outputDone) decodeMore(b)
        val want = (b - a).coerceAtMost((produced - a).coerceAtLeast(0)).toInt()
        val out = FloatArray(want)
        var filled = 0
        var abs = queueStart
        val it = queue.iterator()
        while (it.hasNext() && filled < want) {
            val chunk = it.next()
            val chunkEnd = abs + chunk.size
            if (chunkEnd > a) {
                val from = (a - abs).coerceAtLeast(0).toInt()
                val take = minOf(chunk.size - from, want - filled)
                System.arraycopy(chunk, from, out, filled, take)
                filled += take
            }
            abs = chunkEnd
        }
        // сбрасываем отработанный хвост (держим назад не больше RETAIN)
        discardBefore = maxOf(discardBefore, b - PcmSource.RETAIN_SAMPLES)
        drainDiscard()
        return out
    }

    private fun drainDiscard() {
        var drop = discardBefore - queueStart
        while (drop > 0 && queue.isNotEmpty()) {
            val head = queue.first()
            if (head.size <= drop) { queue.removeFirst(); queueStart += head.size; drop -= head.size }
            else { queue[0] = head.copyOfRange(drop.toInt(), head.size); queueStart += drop; drop = 0 }
        }
        discardBefore = maxOf(discardBefore, queueStart)
    }

    private fun push(chunk: FloatArray) {
        if (chunk.isEmpty()) return
        queue.addLast(chunk)
        produced += chunk.size
    }

    private fun decodeMore(upTo: Long) {
        // ~5 с за шаг — не держим весь файл, но и не дёргаем кодек по 1 кБ
        val target = minOf(upTo, produced + 80_000)
        while (produced < target && !outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)!!
                    val sampleSize = extractor.readSampleData(inBuf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0,
                            android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx >= 0 -> {
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0)
                        info.size = 0
                    if (info.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        val raw = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        outBuf.get(raw)
                        val bb = java.nio.ByteBuffer.wrap(raw)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        val nFrames = raw.size / (2 * inChannels)
                        val mono = FloatArray(nFrames)
                        for (i in 0 until nFrames) {
                            var acc = 0
                            for (c in 0 until inChannels)
                                acc += bb.getShort((i * inChannels + c) * 2).toInt()
                            mono[i] = acc / (32768.0 * inChannels).toFloat()
                        }
                        push(if (inRateHz != 16000)
                            WavParser.resampleLinear(mono, inRateHz, 16000) else mono)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                        outputDone = true
                }
                outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val nf = codec.outputFormat
                    inRateHz = nf.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                    inChannels = nf.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                }
                outIdx == android.media.MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputDone) {
                        // вход вычерпан, выход не спешит: крутимся, пока EOS не придёт
                    }
                }
            }
        }
        if (outputDone && produced < upTo) {
            // аудио кончилось раньше метаданной длительности — закрываем EOS-состояние
        }
    }

    override fun close() {
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        try { extractor.release() } catch (_: Exception) {}
    }

    companion object {
        class Unsupported(message: String) : Exception(message)

        fun open(file: File): Mp3PcmSource {
            val extractor = android.media.MediaExtractor()
            var codec: android.media.MediaCodec? = null
            try {
                extractor.setDataSource(file.absolutePath)
                var trackIndex = -1
                var format: android.media.MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) { trackIndex = i; format = f; break }
                }
                if (trackIndex < 0 || format == null)
                    throw Unsupported("В файле нет аудиодорожки — неподдерживаемый контейнер")
                val mime = format.getString(android.media.MediaFormat.KEY_MIME)!!
                extractor.selectTrack(trackIndex)
                val durationUs = if (format.containsKey(android.media.MediaFormat.KEY_DURATION))
                    format.getLong(android.media.MediaFormat.KEY_DURATION) else 0L
                if (durationUs <= 0)
                    throw Unsupported("Нет метаданных о длительности — нужен whole-file декод")
                val dec = try {
                    android.media.MediaCodec.createDecoderByType(mime)
                } catch (e: Exception) {
                    throw Unsupported("Нет декодера для формата $mime")
                }
                codec = dec
                try {
                    dec.configure(format, null, null, 0)
                    dec.start()
                } catch (e: Exception) {
                    dec.release(); codec = null
                    throw Unsupported("Декодер $mime не запустился: ${e.message}")
                }
                return Mp3PcmSource(
                    extractor, dec,
                    format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE),
                    format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT),
                    durationUs / 1000,
                )
            } catch (e: Exception) {
                try { codec?.release() } catch (_: Exception) {}
                try { extractor.release() } catch (_: Exception) {}
                throw e
            }
        }
    }
}
