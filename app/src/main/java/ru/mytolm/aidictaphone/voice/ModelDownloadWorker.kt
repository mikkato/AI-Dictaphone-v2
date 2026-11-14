package ru.mytolm.aidictaphone.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.mytolm.aidictaphone.MainActivity
import ru.mytolm.aidictaphone.R
import ru.mytolm.aidictaphone.data.App
import ru.mytolm.aidictaphone.data.DownloadEvent
import ru.mytolm.aidictaphone.data.LlmRegistry
import ru.mytolm.aidictaphone.data.ModelRegistry
import ru.mytolm.aidictaphone.data.Notifier
import ru.mytolm.aidictaphone.data.WhisperModel
import java.util.concurrent.TimeUnit

/**
 * Скачивание моделей в WorkManager (аудит F3/F4/F16): переживает уход с экрана
 * и перезапуск процесса (auto-retry по backoff), показывает прогресс в
 * нотификации, проверяет свободное место, подчищает осиротевшие .part.
 * UI — только подписчик [ModelDownloads.state].
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    companion object {
        const val CHANNEL_ID = "downloads"
        const val TAG = "model_dl"
        private const val BASE_ID = 30_000
        private const val MARGIN_BYTES = 256L * 1024 * 1024   // запас на tmp/SHA

        fun enqueue(context: Context, modelId: String, isLlm: Boolean, urlBase: String?) {
            val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(
                    "modelId" to modelId, "llm" to isLlm, "urlBase" to urlBase))
                .addTag(TAG)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("model_dl_$modelId", ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, modelId: String) =
            WorkManager.getInstance(context).cancelUniqueWork("model_dl_$modelId")

        /** Осиротевшие .part старше N дней (аудит F4) — чистить при старте докачки. */
        fun sweepStaleParts(context: Context, olderThanDays: Int = 3) {
            val cutoff = System.currentTimeMillis() -
                TimeUnit.DAYS.toMillis(olderThanDays.toLong())
            runCatching {
                App.instance.downloader.modelsDir()
                    .walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".part") && it.lastModified() < cutoff }
                    .forEach { runCatching { it.delete() } }
            }
        }
    }

    private fun notifId(modelId: String) = BASE_ID + (modelId.hashCode() and 0x3FFF)

    override suspend fun doWork(): Result {
        val modelId = inputData.getString("modelId") ?: return Result.failure()
        val isLlm = inputData.getBoolean("llm", false)
        val urlBase = inputData.getString("urlBase")
        val dl = App.instance.downloader
        sweepStaleParts(applicationContext)

        val model: WhisperModel? = if (isLlm) {
            LlmRegistry.find(modelId)?.let { dl.asrLike(it) }
        } else ModelRegistry.find(modelId)
        if (model == null)
            return fail(modelId, "Модель $modelId не найдена в реестре")

        if (dl.installed(model)) return Result.success()

        // проверка свободного места (F4): размер модели + запас
        val dir = dl.modelsDir()
        val free = runCatching { StatFs(dir.absolutePath).availableBytes }
            .getOrDefault(Long.MAX_VALUE)
        val need = model.sizeBytes + MARGIN_BYTES
        if (free < need)
            return fail(modelId, "Нужно ${need / 1_048_576} МБ свободного места, " +
                "доступно ${free / 1_048_576} МБ — освободите диск")

        createChannel()
        setForeground(ForegroundInfo(notifId(modelId),
            buildNotification(modelId, model.fileName, 0),
            // targetSdk 36: тип FGS обязателен явно, иначе InvalidForegroundServiceTypeException
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        setProgressAsync(workDataOf("modelId" to modelId, "percent" to 0))

        var lastPct = -1
        var error: String? = null
        val outcome = dl.download(model, urlBase) { ev ->
            when (ev) {
                is DownloadEvent.Progress -> {
                    val pct = if (ev.total > 0)
                        (100.0 * ev.downloaded / ev.total).toInt() else 0
                    if (pct != lastPct) {
                        lastPct = pct
                        setProgressAsync(workDataOf(
                            "modelId" to modelId, "percent" to pct))
                        notificationManager()?.notify(notifId(modelId),
                            buildNotification(modelId, model.fileName, pct))
                    }
                }
                is DownloadEvent.Failed -> error = ev.message
                else -> {}
            }
        }
        notificationManager()?.cancel(notifId(modelId))
        if (outcome.isSuccess) {
            Notifier.downloadDone(applicationContext, model.fileName)
            return Result.success()
        }
        // сеть отвалилась — retry с backoff; WorkManager возобновит сам,
        // .part переживает (HTTP Range). После 6 попыток — FAIL + нотификация.
        if (runAttemptCount < 6) return Result.retry()
        Notifier.notify(applicationContext,
            BASE_ID + 4_096 + (modelId.hashCode() and 0xFFF),
            "Загрузка не удалась",
            "${model.fileName}: ${error ?: outcome.exceptionOrNull()?.message ?: "сеть"}")
        return fail(modelId, error ?: outcome.exceptionOrNull()?.message ?: "Ошибка загрузки")
    }

    private fun fail(modelId: String, msg: String): Result =
        Result.failure(workDataOf("modelId" to modelId, "error" to msg))

    private fun notificationManager(): android.app.NotificationManager? =
        applicationContext.getSystemService(android.app.NotificationManager::class.java)

    private fun buildNotification(modelId: String, fileName: String, percent: Int): Notification {
        val open = PendingIntent.getActivity(
            applicationContext, 1,
            Intent(applicationContext, MainActivity::class.java)
                .putExtra("openModels", true),
            PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getBroadcast(
            applicationContext, 2,
            Intent(applicationContext, CancelDownloadReceiver::class.java)
                .putExtra("modelId", modelId),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Загрузка модели")
            .setContentText("$fileName — $percent%")
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setContentIntent(open)
            .setProgress(100, percent, percent <= 0)
            .addAction(0, "Отмена", cancel)
            .build()
    }

    private fun createChannel() {
        val nm = notificationManager() ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Загрузка моделей",
                android.app.NotificationManager.IMPORTANCE_LOW))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val modelId = inputData.getString("modelId") ?: "model"
        return ForegroundInfo(notifId(modelId), buildNotification(modelId, modelId, 0),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}

/** Кнопка «Отмена» в нотификации загрузки. */
class CancelDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("modelId") ?: return
        ModelDownloadWorker.cancel(context, id)
    }
}

/**
 * Единый наблюдаемый статус загрузок для UI (ModelScreen + SettingsScreen):
 * source of truth — WorkManager (переживает смерть процесса и экрана).
 */
data class DlState(val percent: Int, val error: String?, val running: Boolean)

object ModelDownloads {
    @Volatile private var flow: StateFlow<Map<String, DlState>>? = null

    fun state(context: Context): StateFlow<Map<String, DlState>> {
        flow?.let { return it }
        synchronized(this) {
            flow?.let { return it }
            val wm = WorkManager.getInstance(context.applicationContext)
            val f = wm.getWorkInfosByTagFlow(ModelDownloadWorker.TAG)
                .map { infos ->
                    val out = HashMap<String, DlState>()
                    for (info in infos) {
                        // modelId известен UI после первого setProgress (в начале doWork)
                        val id = info.progress.getString("modelId") ?: continue
                        when (info.state) {
                            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING ->
                                out[id] = DlState(
                                    info.progress.getInt("percent", 0), null, true)
                            WorkInfo.State.FAILED ->
                                out[id] = DlState(0,
                                    info.progress.getString("error")
                                        ?: "Загрузка не удалась", false)
                            else -> {}
                        }
                    }
                    out
                }
                .stateIn(App.instance.scope, SharingStarted.Eagerly, emptyMap())
            flow = f
            return f
        }
    }
}
