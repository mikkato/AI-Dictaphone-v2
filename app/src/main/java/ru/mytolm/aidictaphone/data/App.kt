package ru.mytolm.aidictaphone.data

import android.app.Application
import android.content.Intent
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.mytolm.aidictaphone.voice.TranscriptionEngine

class App : Application() {

    // Ловим и логируем исключения в фоновых корутинах — иначе SupervisorJob
    // глотает их молча и операции (напр. постановка транскрипции в очередь)
    // тихо не выполняются.
    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        android.util.Log.e("AppScope", "Unhandled coroutine exception", e)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    lateinit var settings: SettingsRepository
        private set
    lateinit var downloader: ModelDownloader
        private set
    val dao by lazy { AppDatabase.get(this).dao() }
    val transcriptionEngine by lazy {
        TranscriptionEngine(this, dao, settings, downloader)
    }
    val llmClient by lazy { LlmClient(settings) }
    val localLlmClient by lazy { ru.mytolm.aidictaphone.voice.LocalLlmClient(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsRepository(this)
        downloader = ModelDownloader(this)
        ru.mytolm.aidictaphone.voice.ModelManager.asrReleaser = { transcriptionEngine.releaseAll() }
        // возобновление незавершённых задач после убийства процесса (ТЗ §7):
        // WorkManager сам перезапускает прерванные рабочие задачи.
        WorkManager.initialize(this, androidx.work.Configuration.Builder().build())
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
