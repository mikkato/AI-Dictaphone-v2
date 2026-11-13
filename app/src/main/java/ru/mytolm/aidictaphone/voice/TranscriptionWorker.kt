package ru.mytolm.aidictaphone.voice

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.mytolm.aidictaphone.R
import ru.mytolm.aidictaphone.data.App
import ru.mytolm.aidictaphone.data.NoteStatus
import ru.mytolm.aidictaphone.data.Notifier
import java.util.concurrent.TimeUnit

/**
 * Фоновая транскрипция через WorkManager (ТЗ §5.3): отменяемая, переживает
 * перезапуск приложения, прогресс в нотификации.
 */
class TranscriptionWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    companion object {
        const val CHANNEL_ID = "transcription"

        fun schedule(context: Context, noteId: Long) {
            App.instance.scope.launch {
                val dao = App.instance.dao
                try {
                    // сначала статус в БД, потом enqueue: иначе expedited-воркер успевает
                    // выставить TRANSCRIBING, и асинхронный QUEUED затирает его («вечно в очереди»)
                    if (dao.note(noteId)?.status != NoteStatus.TRANSCRIBING)
                        dao.setNoteStatus(noteId, NoteStatus.QUEUED)
                    val req = OneTimeWorkRequestBuilder<TranscriptionWorker>()
                        .setInputData(workDataOf("noteId" to noteId))
                        // WorkManager 2.10 корректно прокидывает foregroundServiceType=dataSync (manifest) на targetSdk 36
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .setConstraints(Constraints.Builder()
                            .setRequiresBatteryNotLow(false).build())
                        .build()
                    WorkManager.getInstance(context)
                        .enqueueUniqueWork("transcribe_$noteId",
                            ExistingWorkPolicy.KEEP, req)
                } catch (e: Exception) {
                    android.util.Log.e("TranscriptionWorker", "schedule failed for $noteId", e)
                    dao.setNoteStatus(noteId, NoteStatus.ERROR,
                        "Не удалось поставить в очередь: ${e.message}")
                }
            }
        }

        fun cancel(context: Context, noteId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork("transcribe_$noteId")
        }
    }

    override suspend fun doWork(): Result {
        val noteId = inputData.getLong("noteId", -1)
        if (noteId < 0) return Result.failure()
        val app = App.instance
        val dao = app.dao
        createChannel()
        setForeground(createForeground())

        val engine = app.transcriptionEngine
        var lastPct = -1
        try {
            engine.transcribe(noteId, object : TranscriptionEngine.ProgressListener {
                override fun onProgress(noteId: Long, percent: Int) {
                    if (percent != lastPct) {
                        lastPct = percent
                        setProgressAsync(workDataOf("progress" to percent))
                        // живой процент и в foreground-нотификации
                        applicationContext
                            .getSystemService(android.app.NotificationManager::class.java)
                            ?.notify(100 + noteId.toInt(),
                                buildNotification(percent))
                    }
                }
            })
        } catch (e: Exception) {
            dao.setNoteStatus(noteId, NoteStatus.ERROR, e.message)
        }
        val note = dao.note(noteId)
        // уведомление о завершении (успех/ошибка) в системный центр уведомлений
        when (note?.status) {
            NoteStatus.TRANSCRIPT, NoteStatus.READY ->
                Notifier.transcriptionDone(applicationContext, noteId, note.title)
            NoteStatus.ERROR ->
                Notifier.transcriptionFailed(applicationContext, noteId,
                    note.errorMessage ?: "Не удалось распознать")
            else -> {}
        }
        return if (note?.status == NoteStatus.TRANSCRIPT || note?.status == NoteStatus.READY)
            Result.success() else Result.failure()
    }

    private fun createForeground(): ForegroundInfo {
        val noteId = inputData.getLong("noteId", 0)
        return ForegroundInfo(
            100 + noteId.toInt(), buildNotification(0),
            // Android 16 / targetSdk 36: тип обязателен явно, manifest-фолбэка нет
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun buildNotification(percent: Int): Notification {
        val open = android.app.PendingIntent.getActivity(
            applicationContext, 0,
            android.content.Intent(applicationContext, ru.mytolm.aidictaphone.MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Транскрибация")
            .setContentText(if (percent > 0) "Обработка аудио… $percent%" else "Обработка аудио…")
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setContentIntent(open)
            .setProgress(100, percent, percent <= 0)
            .build()
    }

    // для expedited-старта (ACTION_START_FOREGROUND) до doWork()
    override suspend fun getForegroundInfo(): ForegroundInfo = createForeground()

    private fun createChannel() {
        val nm = applicationContext.getSystemService(android.app.NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(android.app.NotificationChannel(
                CHANNEL_ID, "Транскрибация", android.app.NotificationManager.IMPORTANCE_LOW))
        }
    }
}
