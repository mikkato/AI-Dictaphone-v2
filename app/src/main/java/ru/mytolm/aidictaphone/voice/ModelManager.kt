package ru.mytolm.aidictaphone.voice

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Арбитраж больших моделей в RAM (защита от OOM): одновременно резидентна
 * только одна тяжёлая модель. Вход операции выгружает чужую, informer читает состояние.
 */
object ModelManager {

    enum class Resident { NONE, ASR, LLM }

    @Volatile var resident: Resident = Resident.NONE
        private set
    /** Регистрируется из App: выгрузка whisper-контекста инстанса TranscriptionEngine. */
    @Volatile var asrReleaser: (() -> Unit)? = null
    @Volatile var residentName: String = ""
        private set

    private val opMutex = Mutex()

    /** Вход транскрипции: выгружает LLM, делает ASR резидентом. */
    suspend fun enterAsr(name: String) { opMutex.withLock {
        if (resident == Resident.LLM) LlamaEngine.unload()
        resident = Resident.ASR; residentName = name
    } }

    suspend fun <T> asrOperation(name: String, block: suspend () -> T): T = opMutex.withLock {
        if (resident == Resident.LLM) {
            LlamaEngine.unload()
        }
        resident = Resident.ASR
        residentName = name
        try { block() } finally {
            // после операции ASR держим: транскрипции обычно идут пачками (очередь импорта)
        }
    }

    suspend fun <T> llmOperation(name: String, block: suspend () -> T): T = opMutex.withLock {
        if (resident == Resident.ASR) {
            asrReleaser?.invoke()
            SherpaEngine.release()
        }
        resident = Resident.LLM
        residentName = name
        try { block() } finally { }
    }

    /** Полная разгрузка (по требованию пользователя / нехватке памяти). */
    fun unloadAll() {
        asrReleaser?.invoke()
        SherpaEngine.release()
        LlamaEngine.unload()
        resident = Resident.NONE
        residentName = ""
    }

    data class Mem(val totalMb: Long, val availMb: Long, val appMb: Long) {
        val usedMb get() = (totalMb - availMb).coerceAtLeast(0)
        val usedPct: Int get() = if (totalMb > 0) ((totalMb - availMb) * 100 / totalMb).toInt() else 0
    }

    fun mem(context: Context): Mem {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val rt = Runtime.getRuntime()
        val appMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        return Mem(mi.totalMem / (1024 * 1024), mi.availMem / (1024 * 1024), appMb)
    }
}
