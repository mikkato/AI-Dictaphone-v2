package ru.mytolm.aidictaphone.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Собственный парсер WAV (без ffmpeg): RIFF/WAVE, PCM 16/24-бит, float32,
 * mono/stereo → mono, линейный ресемплинг к 16 кГц (ТЗ §5.1).
 */
object WavParser {

    class WavFormatException(message: String) : Exception(message)

    data class WavData(val sampleRate: Int, val channels: Int, val samples: FloatArray)

    private const val TARGET_RATE = 16000

    fun parse(bytes: ByteArray): WavData {
        if (bytes.size < 44) throw WavFormatException("Файл слишком мал для WAV")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE")
            throw WavFormatException("Это не WAV (нет RIFF/WAVE заголовка)")

        var pos = 12
        var fmtFound = false
        var audioFormat = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataLen = 0

        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = buf.getInt(pos + 4)
            when (id) {
                "fmt " -> {
                    fmtFound = true
                    audioFormat = buf.getShort(pos + 8).toInt() and 0xFFFF
                    channels = buf.getShort(pos + 10).toInt() and 0xFFFF
                    sampleRate = buf.getInt(pos + 12)
                    bitsPerSample = buf.getShort(pos + 22).toInt() and 0xFFFF
                }
                "data" -> {
                    dataOffset = pos + 8
                    dataLen = minOf(size, bytes.size - dataOffset)
                }
            }
            pos += 8 + size + (size and 1)
        }
        if (!fmtFound) throw WavFormatException("В WAV нет секции fmt")
        if (dataOffset < 0) throw WavFormatException("В WAV нет секции data")
        if (channels < 1 || channels > 8) throw WavFormatException("Каналов: $channels — не поддерживается")
        if (audioFormat != 1 && audioFormat != 3)
            throw WavFormatException(
                "Кодек WAV $audioFormat не поддерживается (нужен PCM 16/24-бит или float32). " +
                "Сконвертируйте файл в WAV PCM.")

        val bytesPerSample = bitsPerSample / 8
        val frameCount = dataLen / (bytesPerSample * channels)
        val mono = FloatArray(frameCount)
        var maxAbs = 0f
        for (i in 0 until frameCount) {
            var acc = 0.0
            for (c in 0 until channels) {
                val off = dataOffset + (i * channels + c) * bytesPerSample
                val v = when {
                    audioFormat == 3 && bitsPerSample == 32 ->
                        buf.order(ByteOrder.LITTLE_ENDIAN).getFloat(off).toDouble()
                    bitsPerSample == 16 ->
                        buf.getShort(off).toInt() / 32768.0
                    bitsPerSample == 24 -> {
                        val b0 = bytes[off].toInt() and 0xFF
                        val b1 = bytes[off + 1].toInt() and 0xFF
                        val b2 = bytes[off + 2].toInt() and 0xFF
                        val raw = b0 or (b1 shl 8) or (b2 shl 16)
                        (if (raw and 0x800000 != 0) raw or 0xFF000000.toInt() else raw) / 8388608.0
                    }
                    bitsPerSample == 8 -> ((bytes[off].toInt() and 0xFF) - 128) / 128.0
                    else -> throw WavFormatException("Разрядность $bitsPerSample не поддерживается")
                }
                acc += v
            }
            val s = (acc / channels).toFloat()
            mono[i] = s
            if (abs(s) > maxAbs) maxAbs = abs(s)
        }

        // Заголовок может врать о длительности (>1% отклонение — ресемплим по факту).
        // Факт. длительность = frameCount / sampleRate; целевая — TARGET_RATE, поэтому
        // ресемплинг выполняется всегда при sampleRate != TARGET_RATE.
        val out = if (sampleRate == TARGET_RATE) mono
                  else resampleLinear(mono, sampleRate, TARGET_RATE)
        // Нормализация почти тишины (защита от неслышимых записей) не делается —
        // Whisper сам обрабатывает уровень.
        return WavData(TARGET_RATE, 1, out)
    }

    /**
     * Разобранный RIFF-заголовок WAV: всё, что нужно потокому читателю окон.
     */
    data class WavFileInfo(
        val audioFormat: Int, val channels: Int, val sampleRate: Int, val bits: Int,
        val dataOffset: Long, val dataLen: Long,
    ) {
        val bytesPerSample: Int get() = bits / 8
        val bytesPerFrame: Int get() = bytesPerSample * channels
        val frameCount: Long get() = if (bytesPerFrame > 0) dataLen / bytesPerFrame else 0L
    }

    /** Заголовок WAV (RIFF/fmt/data) с валидацией — общий для parseFile и WavPcmSource. */
    fun parseFileHeader(file: File, raf: java.io.RandomAccessFile): WavFileInfo {
        val head = ByteArray(minOf(44, file.length().toInt()))
        if (head.size < 44) throw WavFormatException("Файл слишком мал для WAV")
        raf.seek(0); raf.readFully(head)
        if (String(head, 0, 4) != "RIFF" || String(head, 8, 4) != "WAVE")
            throw WavFormatException("Это не WAV (нет RIFF/WAVE заголовка)")

        var pos = 12L
        var audioFormat = 0; var channels = 0; var sampleRate = 0; var bits = 0
        var dataOffset = -1L; var dataLen = 0L
        while (pos + 8 <= file.length()) {
            val idBuf = ByteArray(8)
            raf.seek(pos); raf.readFully(idBuf)
            val id = String(idBuf, 0, 4)
            val size = (ByteBuffer.wrap(idBuf, 4, 4).order(ByteOrder.LITTLE_ENDIAN)
                .getInt().toLong()) and 0xFFFFFFFFL
            when (id) {
                "fmt " -> {
                    val f = ByteArray(minOf(size, 40L).toInt())
                    raf.seek(pos + 8); raf.readFully(f)
                    val fb = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
                    audioFormat = fb.getShort(0).toInt() and 0xFFFF
                    channels = fb.getShort(2).toInt() and 0xFFFF
                    sampleRate = fb.getInt(4)
                    bits = fb.getShort(14).toInt() and 0xFFFF
                }
                "data" -> { dataOffset = pos + 8; dataLen = minOf(size, file.length() - pos - 8) }
            }
            pos += 8 + size + (size and 1)
        }
        if (audioFormat == 0) throw WavFormatException("В WAV нет секции fmt")
        if (dataOffset < 0) throw WavFormatException("В WAV нет секции data")
        if (channels < 1 || channels > 8) throw WavFormatException("Каналов: $channels — не поддерживается")
        if (audioFormat != 1 && audioFormat != 3)
            throw WavFormatException(
                "Кодек WAV $audioFormat не поддерживается (нужен PCM 16/24-бит или float32). " +
                "Сконвертируйте файл в WAV PCM.")
        val bps = bits / 8
        if (bps !in intArrayOf(1, 2, 3, 4) || (audioFormat == 3 && bits != 32))
            throw WavFormatException("Разрядность $bits не поддерживается")
        if (sampleRate <= 0) throw WavFormatException("Некорректная частота дискретизации")
        return WavFileInfo(audioFormat, channels, sampleRate, bits, dataOffset, dataLen)
    }

    /**
     * Потоковый разбор WAV без загрузки файла целиком (защита от OOM на длинных
     * импортах: час стерео 48 кГц 16 бит ≈ 700 МБ, readBytes() упал бы дважды).
     * Окна ~2 МБ, линейный ресемплинг с сохранением дробной позиции между окнами —
     * результат совпадает с parse(readBytes()) с точностью до float-округлений.
     */
    fun parseFile(file: File): WavData {
        val raf = java.io.RandomAccessFile(file, "r")
        try {
            val info = parseFileHeader(file, raf)
            val channels = info.channels; val sampleRate = info.sampleRate
            val bits = info.bits; val audioFormat = info.audioFormat
            val bps = info.bytesPerSample
            val dataOffset = info.dataOffset
            val frameCount = info.frameCount.toInt()
            val bytesPerFrame = info.bytesPerFrame
            if (frameCount == 0) return WavData(TARGET_RATE, 1, FloatArray(0))

            val windowFrames = maxOf(2 * 1048576 / bytesPerFrame, 4096)
            val outCap = (frameCount.toLong() * TARGET_RATE / sampleRate).toInt() + 1
            val out = FloatArray(outCap)
            var outN = 0
            val ratio = sampleRate.toDouble() / TARGET_RATE
            var srcPos = 0.0                    // глобальная дробная позиция источника
            var framesBase = 0L                 // индекс первого кадра текущего окна
            var carry = 0f                      // последний кадр предыдущего окна
            var haveCarry = false
            var done = 0
            while (done < frameCount) {
                val n = minOf(windowFrames.toLong(), (frameCount - done).toLong()).toInt()
                val raw = ByteArray(n * bytesPerFrame)
                raf.seek(dataOffset + done.toLong() * bytesPerFrame)
                raf.readFully(raw)
                val win = FloatArray(n)
                for (o in 0 until n) {
                    var acc = 0.0
                    for (c in 0 until channels) {
                        val off = (o * channels + c) * bps
                        val v = when {
                            audioFormat == 3 -> (ByteBuffer.wrap(raw, off, 4)
                                .order(ByteOrder.LITTLE_ENDIAN).float).toDouble()
                            bits == 16 -> (ByteBuffer.wrap(raw, off, 2)
                                .order(ByteOrder.LITTLE_ENDIAN).short.toInt()) / 32768.0
                            bits == 24 -> {
                                val r0 = (raw[off].toInt() and 0xFF) or
                                    ((raw[off + 1].toInt() and 0xFF) shl 8) or
                                    ((raw[off + 2].toInt() and 0xFF) shl 16)
                                (if (r0 and 0x800000 != 0) r0 or 0xFF000000.toInt() else r0) / 8388608.0
                            }
                            else -> ((raw[off].toInt() and 0xFF) - 128) / 128.0
                        }
                        acc += v
                    }
                    win[o] = (acc / channels).toFloat()
                }
                // выдаём выходной сэмпл, только когда i0+1 уже прочитан (или источник исчерпан)
                val avail = framesBase + n.toLong()
                fun frameAt(i: Long): Float = when {
                    i >= framesBase -> win[(i - framesBase).toInt()]
                    i == framesBase - 1 && haveCarry -> carry
                    else -> 0f
                }
                while (outN < outCap) {
                    val i0 = srcPos.toLong()
                    if (i0 >= frameCount) break
                    val frac = (srcPos - i0).toFloat()
                    // для интерполяции нужны оба кадра i0 и i0+1 (кроме последнего кадра
                    // источника). Раньше при frac==0 guard проверял только i0 — последний
                    // кадр окна читал win[n] → ArrayIndexOutOfBoundsException на каждом
                    // окне при ratio 48к→16к ровно 3.0 (запись > ~65 с).
                    val need = if (i0 + 1 < frameCount) i0 + 1 else i0
                    if (need >= avail && avail < frameCount) break
                    val a = frameAt(i0)
                    val b = if (i0 + 1 < frameCount) frameAt(i0 + 1) else a
                    out[outN++] = a + (b - a) * frac
                    srcPos += ratio
                }
                carry = win[n - 1]; haveCarry = true
                framesBase += n
                done += n
            }
            return WavData(TARGET_RATE, 1, out.copyOf(outN))
        } finally {
            try { raf.close() } catch (_: Exception) {}
        }
    }

    fun resampleLinear(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return input
        val outLen = (input.size.toLong() * toRate / fromRate).toInt()
        val out = FloatArray(outLen)
        val ratio = fromRate.toFloat() / toRate
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt()
            val frac = srcPos - i0
            val a = input[i0]
            val b = if (i0 + 1 < input.size) input[i0 + 1] else a
            out[i] = a + (b - a) * frac
        }
        return out
    }
}
