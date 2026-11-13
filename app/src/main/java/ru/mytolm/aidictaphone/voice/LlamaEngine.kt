package ru.mytolm.aidictaphone.voice

/**
 * JNI к libllama_jni.so (llama.cpp, статический ggml-CPU, без DL-бэкендов).
 * Одна модель в памяти; обязательна через ModelManager.
 *
 * Контекстное окно берётся из GGUF-метаданных модели (n_ctx_train) и
 * ограничивается максимумом [MAX_CTX] — см. [contextLength].
 */
object LlamaEngine {

    @Volatile private var handle: Long = 0L

    /** Верхняя граница выделяемого контекста (память KV-кэша на телефоне). */
    const val MAX_CTX = 8192

    init {
        try {
            System.loadLibrary("llama_jni")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("LlamaEngine", "loadLibrary failed", e)
        }
    }

    private external fun loadNative(path: String, threads: Int, maxCtxLen: Int): Long
    /** Потоковая отдача сгенерированных кусков (UI печатает по ходу генерации). */
    interface PieceCallback { fun onPiece(text: String) }

    private external fun chatNative(handle: Long, system: String, user: String,
                                   maxTokens: Float, temperature: Float,
                                   pieceCb: PieceCallback?): String
    private external fun getContextLengthNative(handle: Long): Int
    private external fun unloadNative(handle: Long)

    val isLoaded get() = handle != 0L

    /** Фактически выделенное контекстное окно (min(n_ctx_train, maxCtxLen)). */
    val contextLength: Int get() = if (isLoaded) getContextLengthNative(handle) else 0

    @Synchronized
    fun ensureLoaded(path: String, threads: Int, maxCtxLen: Int = MAX_CTX) {
        if (isLoaded) return
        val h = loadNative(path, threads, maxCtxLen)
        if (h == 0L) throw IllegalStateException("Не удалось загрузить LLM: $path")
        handle = h
    }

    @Synchronized
    fun chat(system: String, user: String, maxTokens: Int = 1024, temperature: Float = 0.4f,
             onPiece: ((String) -> Unit)? = null): String {
        check(isLoaded) { "LLM не загружена" }
        val cb = onPiece?.let { f ->
            object : PieceCallback { override fun onPiece(text: String) = f(text) }
        }
        return chatNative(handle, system, user, maxTokens.toFloat(), temperature, cb)
    }

    @Synchronized
    fun unload() {
        if (isLoaded) {
            unloadNative(handle)
            handle = 0L
        }
    }
}
