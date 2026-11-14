package ru.mytolm.aidictaphone.voice

/**
 * JNI-обёртка whisper.cpp. Сегменты приходят через callback [SegmentCallback].
 * Выбор CPU-бэкенда ggml (dotprod/i8mm/SVE) выполняется в рантайме —
 * вариант с неподдерживаемыми инструкциями не загружается (защита от
 * illegal instruction на Tensor).
 */
object WhisperNative {

    interface SegmentCallback {
        /** startMs/endMs — абсолютные (с учётом offset чанка) границы сегмента. */
        fun onSegment(startMs: Long, endMs: Long, text: String)
        /** Опрашивается из whisper progress_callback: true → инференс прерывается. */
        fun shouldCancel(): Boolean = false
    }

    @Volatile
    private var loaded = false

    private fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("whisper_jni")
            loaded = true
        }
    }

    /** Загружает лучший CPU-бэкенд ggml из nativeLibraryDir. Вызывать до loadModel. */
    fun initBackend(nativeLibDir: String): Boolean {
        ensureLoaded()
        return initBackendNative(nativeLibDir)
    }

    fun loadModel(path: String): Long {
        ensureLoaded()
        return loadModelNative(path)
    }

    fun freeModel(handle: Long) = freeModelNative(handle)

    /** Сам-тест декодирования (1 с синтетики): false → бэкенд непригоден. */
    fun selfTest(handle: Long): Boolean {
        ensureLoaded()
        return selfTestNative(handle)
    }

    fun transcribeChunk(
        handle: Long,
        pcm: FloatArray,
        startOffsetMs: Long,
        language: String,
        threads: Int,
        initialPrompt: String?,
        callback: SegmentCallback,
    ): Int {
        ensureLoaded()
        return transcribeChunkNative(
            handle, pcm, startOffsetMs, language, threads, initialPrompt, callback
        )
    }

    private external fun initBackendNative(libDir: String): Boolean
    private external fun loadModelNative(path: String): Long
    private external fun freeModelNative(handle: Long)
    private external fun selfTestNative(handle: Long): Boolean
    private external fun transcribeChunkNative(
        handle: Long, pcm: FloatArray, startOffsetMs: Long,
        language: String, threads: Int, initialPrompt: String?,
        callback: SegmentCallback,
    ): Int
}
