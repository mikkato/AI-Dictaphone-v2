package ru.mytolm.aidictaphone.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import ru.mytolm.aidictaphone.MainActivity
import ru.mytolm.aidictaphone.R

/**
 * Уведомления в системном центре о завершении фоновых операций
 * (транскрипция, суммаризация, загрузка моделей). Тап открывает приложение;
 * при наличии noteId — сразу экран заметки.
 */
object Notifier {

    const val CHANNEL_COMPLETION = "completion"

    // стабильные диапазоны id, чтобы повторное уведомление обновляло прежнее,
    // а не плодило копии
    private const val ID_TRANSCRIBE = 10_000
    private const val ID_SUMMARY = 20_000
    private const val ID_DOWNLOAD = 30_000

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_COMPLETION) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_COMPLETION, "Завершение операций",
                    NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun contentIntent(context: Context, noteId: Long?): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (noteId != null && noteId > 0) i.putExtra("noteId", noteId)
        return PendingIntent.getActivity(context, 0, i, PendingIntent.FLAG_IMMUTABLE)
    }

    fun notify(context: Context, id: Int, title: String, text: String, noteId: Long? = null) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL_COMPLETION)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, noteId))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    fun transcriptionDone(context: Context, noteId: Long, noteTitle: String) =
        notify(context, ID_TRANSCRIBE + noteId.toInt(),
            "Транскрипция завершена", noteTitle, noteId)

    fun transcriptionFailed(context: Context, noteId: Long, message: String) =
        notify(context, ID_TRANSCRIBE + noteId.toInt(),
            "Ошибка транскрипции", message, noteId)

    fun summaryDone(context: Context, noteId: Long, noteTitle: String) =
        notify(context, ID_SUMMARY + noteId.toInt(),
            "Саммари готово", noteTitle, noteId)

    fun summaryFailed(context: Context, noteId: Long, message: String) =
        notify(context, ID_SUMMARY + noteId.toInt(),
            "Ошибка суммаризации", message, noteId)

    fun downloadDone(context: Context, fileName: String) =
        notify(context, ID_DOWNLOAD + fileName.hashCode(),
            "Загрузка завершена", "$fileName готова к использованию")
}
