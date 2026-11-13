package ru.mytolm.aidictaphone.voice

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/**
 * MP3 → PCM float32 mono 16 кГц через MediaCodec (без ffmpeg, ТЗ §5.1).
 * Ошибки декода возвращаются понятным исключением, не падением.
 */
object Mp3Decoder {

    class DecodeException(message: String) : Exception(message)

    data class Decoded(val samples: FloatArray, val durationMs: Long)

    fun decode(file: File): Decoded {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null)
                throw DecodeException("В файле нет аудиодорожки — неподдерживаемый контейнер")
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            extractor.selectTrack(trackIndex)

            val dec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw DecodeException("Нет декодера для формата $mime")
            }
            decoder = dec

            val pcmChunks = ArrayList<FloatArray>()
            var totalSamples = 0L
            var lastPtsUs = 0L

            try {
                dec.configure(format, null, null, 0)
                dec.start()
                
            } catch (e: Exception) {
                decoder.release()
                throw DecodeException("Декодер $mime не запустился: ${e.message}")
            }
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var inputRateHz = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var inputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = dec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, sampleSize,
                                extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }
                        if (info.size > 0) {
                            lastPtsUs = maxOf(lastPtsUs, info.presentationTimeUs)
                            val outBuf = dec.getOutputBuffer(outIdx)!!
                            val raw = ByteArray(info.size)
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            outBuf.get(raw)
                            // MediaCodec выдает 16-бит PCM; каналы/частота из формата
                            val shorts = java.nio.ByteBuffer.wrap(raw)
                                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            val nFrames = raw.size / (2 * inputChannels)
                            val mono = FloatArray(nFrames)
                            for (i in 0 until nFrames) {
                                var acc = 0
                                for (c in 0 until inputChannels)
                                    acc += shorts.getShort((i * inputChannels + c) * 2).toInt()
                                mono[i] = acc / (32768.0 * inputChannels).toFloat()
                            }
                            if (inputRateHz != 16000) {
                                val rs = WavParser.resampleLinear(mono, inputRateHz, 16000)
                                totalSamples += rs.size
                                pcmChunks.add(rs)
                            } else {
                                totalSamples += mono.size
                                pcmChunks.add(mono)
                            }
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                            outputDone = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = dec.outputFormat
                        inputRateHz = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        inputChannels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
            try { dec.stop() } catch (_: Exception) {}
            dec.release()
            decoder = null

            val full = FloatArray(totalSamples.toInt())
            var off = 0
            for (c in pcmChunks) {
                System.arraycopy(c, 0, full, off, c.size)
                off += c.size
            }
            if (full.isEmpty())
                throw DecodeException("Декодер не вернул аудио — проверьте файл")
            val durMs = if (lastPtsUs > 0) lastPtsUs / 1000
                        else full.size * 1000L / 16000
            return Decoded(full, durMs)
        } catch (e: DecodeException) {
            throw e
        } catch (e: Exception) {
            throw DecodeException("Ошибка декодирования MP3: ${e.message}")
        } finally {
            // декодер обязан освобождаться и при исключении в середине цикла —
            // иначе MediaCodec утечёт на каждый упавший импорт
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }
}
