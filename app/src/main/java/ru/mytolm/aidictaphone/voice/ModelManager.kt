package ru.mytolm.aidictaphone.voice

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Арбитраж больших моделей в RAM (защита от OOM): одновременно резидентна
 * только одна тяжёлая модель. Вход операции выгружает чужую, informer читает состояние.
 * Простой резидента ограничен таймаутом (аудит S6) и система может потребовать
 * полную выгрузку через onTrimMemory (аудит S5) — см. [unloadIfIdle].
 */
object ModelManager {

    enum class Resident { NONE, ASR, LLM }

    /** Авто-выгрузка резидента после простоя (аудит S6): 3 минуты. */
    private const val IDLE_UNLOAD_MS = 3 * 60_000L

    @Volatile var resident: Resident = Resident.NONE
        private set
    /** Регистрируется из App: выгрузка whisper-контекста инстанса TranscriptionEngine. */
    @Volatile var asrReleaser: (() -> Unit)? = null
    @Volatile var residentName: String = ""
        private set

    private val opMutex = Mutex()
    private val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var unloadTimer: Job? = null

    /** Вход транскрипции: выгружает LLM, делает ASR резидентом. */
    suspend fun enterAsr(name: String) { opMutex.withLock {
        if (resident == Resident.LLM) LlamaEngine.unload()
        resident = Resident.ASR; residentName = name
        cancelIdleTimer()
    } }

    suspend fun <T> asrOperation(name: String, block: suspend () -> T): T = opMutex.withLock {
        if (resident == Resident.LLM) {
            LlamaEngine.unload()
        }
        resident = Resident.ASR
        residentName = name
        cancelIdleTimer()
        try { block() } finally {
            // после операции держим резидент (транскрипции идут пачками), но
            // недолго: по таймауту простоим — выгрузим (аудит S6)
            scheduleIdleUnload()
        }
    }

    suspend fun <T> llmOperation(name: String, block: suspend () -> T): T = opMutex.withLock {
        if (resident == Resident.ASR) {
            asrReleaser?.invoke()
            SherpaEngine.release()
        }
        resident = Resident.LLM
        residentName = name
        cancelIdleTimer()
        try { block() } finally { scheduleIdleUnload() }
    }

    fun cancelIdleTimer() { unloadTimer?.cancel(); unloadTimer = null }

    /** Резидент по-прежнему нужен? Нет — через IDLE_UNLOAD_MS без операций выгрузим. */
    fun scheduleIdleUnload() {
        unloadTimer?.cancel()
        unloadTimer = timerScope.launch {
            delay(IDLE_UNLOAD_MS)
            // если за это время началась операция — не трогаем
            if (opMutex.isLocked) return@launch
            unloadAll()
        }
    }

    /**
     * Выгрузка без ожидания операции (вызывается из onTrimMemory): если тяжёлая
     * операция идёт прямо сейчас — пропускаем (прерывать инференс посреди чанка
     * опаснее, чем получить TRIM: система убьёт процесс только при CRITICAL).
     */
    fun unloadIfIdle() {
        if (resident == Resident.NONE) return
        if (opMutex.isLocked) return
        unloadAll()
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
