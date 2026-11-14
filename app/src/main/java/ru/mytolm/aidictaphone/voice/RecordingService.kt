package ru.mytolm.aidictaphone.voice

import android.app.*
import android.content.*
import android.os.Build
import android.os.IBinder
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaMetadataRetriever
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import ru.mytolm.aidictaphone.MainActivity
import ru.mytolm.aidictaphone.R
import ru.mytolm.aidictaphone.data.App
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Запись микрофоном (ТЗ §4.2): AudioRecord PCM 16-бит mono 48 кГц → WAV,
 * foreground service с нотификацией (пауза/стоп), live-уровень для метра,
 * хайлайты и текстовые заметки с автосохранением по времени.
 */
class RecordingService : Service() {

    companion object {
        const val CHANNEL_ID = "recording"
        const val NOTIF_ID = 42
        const val SR = 48_000
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_STOP = "stop"
        const val ACTION_HIGHLIGHT = "highlight"
        const val ACTION_TEXT_NOTE = "textnote"

        @Volatile var isRecording = false
        @Volatile var isPaused = false
        @Volatile var elapsedMs = 0L
        @Volatile var level = 0f          // 0..1 RMS для VU-метра
        /** Демо-симуляция уровня (на хосте без микрофона): тап по заголовку на экране записи */
        @Volatile var simulate = false
        /** Усиление входа микрофона (0.2..4), применяется к PCM при записи */
        @Volatile var micGain = 1.0f
        @Volatile var lastSavedNoteId: Long = -1

        /** Период переписи WAV-заголовка: каждые ~30 с данных (48 кГц mono 16 бит). */
        private const val HEADER_FLUSH_BYTES = 30L * SR * 2

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, RecordingService::class.java).setAction(ACTION_START))
        }
        fun pause(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_PAUSE))
        }
        fun resume(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_RESUME))
        }
        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }
        fun highlight(context: Context, label: String) {
            val i = Intent(context, RecordingService::class.java).setAction(ACTION_HIGHLIGHT)
            i.putExtra("label", label)
            context.startService(i)
        }
        fun saveTextNote(context: Context, text: String) {
            val i = Intent(context, RecordingService::class.java).setAction(ACTION_TEXT_NOTE)
            i.putExtra("text", text)
            context.startService(i)
        }
    }

    private var record: AudioRecord? = null
    private var job: Job? = null
    private var writerJob: Job? = null
    @Volatile private var pcmChannel: Channel<ByteArray>? = null
    private var outFile: File? = null
    private var raf: RandomAccessFile? = null
    private var pcmBytes = 0L
    private var pausedAccumMs = 0L
    private var pauseStartedAt = 0L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() +
        CoroutineExceptionHandler { _, e ->
            android.util.Log.e("RecordingService", "Unhandled exception", e)
        })

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        when (intent?.action) {
            ACTION_START -> {
                // Android 12+/16: на startForegroundService сервис ОБЯЗАН вызвать
                // startForeground сразу — иначе сбой «did not call startForeground».
                // Тип microphone без выданного RECORD_AUDIO бросает SecurityException —
                // поэтому проверяем разрешение до вызова.
                val micGranted = checkSelfPermission(
                    android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!micGranted) { stopSelf(); return START_NOT_STICKY }
                startForeground(NOTIF_ID, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                if (!beginRecording()) { stopSelf() }
            }
            ACTION_PAUSE -> { isPaused = true; pauseStartedAt = System.currentTimeMillis(); level = 0f }
            ACTION_RESUME -> {
                if (isPaused) pausedAccumMs += System.currentTimeMillis() - pauseStartedAt
                isPaused = false
            }
            ACTION_HIGHLIGHT -> addHighlight(intent.getStringExtra("label") ?: "")
            ACTION_TEXT_NOTE -> {
                val t = intent.getStringExtra("text") ?: ""
                if (t.isNotBlank()) saveText(t)
            }
            ACTION_STOP -> { finishRecording(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun beginRecording(): Boolean {
        if (isRecording) return true
        // без разрешения AudioRecord бросил бы SecurityException — проверяем явно
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        val minBuf = AudioRecord.getMinBufferSize(
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 4, 64 * 1024))
        } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) return false

        val dir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val f = File(dir, "REC_${System.currentTimeMillis()}.wav")
        outFile = f
        raf = RandomAccessFile(f, "rw")
        raf?.setLength(0)
        writeWavHeader(0)
        rec.startRecording()
        record = rec
        isRecording = true
        isPaused = false
        elapsedMs = 0
        pcmBytes = 0
        lastSavedNoteId = -1

        // Непотеря данных (аудит F5): заметка создаётся в НАЧАЛЕ записи — при крахе
        // или убийстве процесса пользователь теряет только хвост после последнего
        // переписанного заголовка, а не запись целиком.
        App.instance.scope.launch { ensureNote() }

        val chan = Channel<ByteArray>(128)   // ~5.5 с буфера — диск не сорвёт захват
        pcmChannel = chan
        writerJob = App.instance.scope.launch {
            var lastHeaderBytes = 0L
            try {
                for (chunk in chan) {
                    try { raf?.write(chunk, 0, chunk.size) } catch (e: Exception) { break }
                    pcmBytes += chunk.size
                    elapsedMs = pcmBytes * 1000 / (SR * 2)
                    // Инкрементальная допись RIFF/data sizes (аудит F5): убийство
                    // процесса оставляет читаемый WAV длительностью до последних 30 с.
                    if (pcmBytes - lastHeaderBytes >= HEADER_FLUSH_BYTES) {
                        lastHeaderBytes = pcmBytes
                        try {
                            raf?.let {
                                it.seek(0); writeWavHeaderTo(it, pcmBytes)
                                it.seek(44 + pcmBytes)
                            }
                        } catch (_: Exception) {}
                        // заодно живой таймер в нотификации (аудит F9)
                        try {
                            getSystemService(android.app.NotificationManager::class.java)
                                ?.notify(NOTIF_ID, buildNotification())
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
            finalizeWav()
        }
        job = scope.launch {
            val buf = ByteArray(4096)   // ~43 мс на чтение — быстрый отклик VU-метра
            while (isActive && isRecording) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    if (!isPaused) {
                        // гейн микрофона: применяем к PCM до записи (софт-клип), затем RMS по итогу.
                        // Один проход с абсолютной индексацией — прежний ByteBuffer.wrap+position
                        // ломал смещение буфера и падал при усилении != 1.0.
                        val g = micGain
                        var sum = 0.0
                        val frames = n / 2
                        val bb = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until frames) {
                            val s = bb.getShort(i * 2).toInt()
                            val boosted = if (g != 1.0f)
                                (s * g).toInt().coerceIn(-32767, 32767).toShort() else s.toShort()
                            if (g != 1.0f) bb.putShort(i * 2, boosted)
                            sum += (boosted / 32768.0) * (boosted / 32768.0)
                        }
                        // буфер переиспользуется циклом — в канал уходит копия
                        chan.trySend(buf.copyOf(n))
                        val rms = if (frames > 0)
                            Math.sqrt(sum / frames).toFloat().coerceIn(0f, 1f) else 0f
                        level = if (simulate) {
                            val t = elapsedMs / 350.0
                            (0.35 + 0.3 * Math.sin(t) + 0.2 * Math.sin(t * 2.7) +
                                Math.random() * 0.15).toFloat().coerceIn(0.05f, 0.98f)
                        } else rms
                    }
                } else if (n < 0) delay(20)
            }
        }
        return true
    }

    private fun addHighlight(label: String) {
        scope.launch {
            val id = ensureNote()
            App.instance.dao.insertHighlight(
                ru.mytolm.aidictaphone.data.Highlight(noteId = id, timeMs = elapsedMs, label = label))
        }
    }

    private fun saveText(text: String) {
        scope.launch {
            val id = ensureNote()
            App.instance.dao.insertTextNote(
                ru.mytolm.aidictaphone.data.TextNote(noteId = id, timeMs = elapsedMs, text = text))
        }
    }

    private suspend fun ensureNote(): Long {
        if (lastSavedNoteId > 0) return lastSavedNoteId
        val title = android.text.format.DateFormat.format("Запись dd.MM HH:mm", System.currentTimeMillis()).toString()
        val id = App.instance.dao.insertNote(
            ru.mytolm.aidictaphone.data.Note(
                title = title, audioPath = outFile?.absolutePath ?: "", durationMs = elapsedMs))
        lastSavedNoteId = id
        return id
    }

    /** однократная дописовка WAV-заголовка; идемпотентна (raf обнуляется) */
    private fun finalizeWav() {
        val r = raf ?: return
        raf = null
        try { r.seek(0); writeWavHeaderTo(r, pcmBytes); r.close() } catch (_: Exception) {}
    }

    private fun finishRecording() {
        if (!isRecording && record == null) return   // повторный stop — не дублировать заметку
        isRecording = false
        job?.cancel()
        try {
            record?.stop()
        } catch (_: Exception) {}
        record?.release()
        record = null
        pcmChannel?.close()      // writer доест остаток и сам закроет файл
        level = 0f

        // Финализация заметки должна пережить onDestroy(): scope.cancel() в onDestroy
        // обрывал этот launch на середине (заметка оставалась AUDIO или без постановки
        // транскрипции в очередь). Используем глобальный scope приложения — он не гасится.
        val writer = writerJob
        // id заметки захватываем синхронно: новая запись может начаться раньше,
        // чем отработает этот launch (companion-флаг к тому моменту уже другой)
        val capturedNoteId = lastSavedNoteId
        lastSavedNoteId = -1
        App.instance.scope.launch {
            // ждём слив буфера с диска (без этого durMs и заголовок могли сниматься
            // по недовычисленному pcmBytes — обрезанная заметка на ровном месте)
            withTimeoutOrNull(5_000) { writer?.join() }
            finalizeWav()
            val durMs = pcmBytes * 1000 / (SR * 2)
            elapsedMs = durMs
            val dao = App.instance.dao
            if (capturedNoteId > 0) {
                dao.note(capturedNoteId)?.let {
                    dao.updateNote(it.copy(audioPath = outFile?.absolutePath ?: it.audioPath,
                        durationMs = durMs, status = ru.mytolm.aidictaphone.data.NoteStatus.QUEUED))
                }
                TranscriptionWorker.schedule(applicationContext, capturedNoteId)
            } else if (durMs > 500) {
                val id = ensureNote()
                dao.note(id)?.let {
                    dao.updateNote(it.copy(durationMs = durMs, status = ru.mytolm.aidictaphone.data.NoteStatus.QUEUED))
                }
                TranscriptionWorker.schedule(applicationContext, id)
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun writeWavHeader(dataLen: Long) { raf?.let { writeWavHeaderTo(it, dataLen) } }

    private fun writeWavHeaderTo(r: RandomAccessFile, dataLen: Long) {
        val totalLen = dataLen + 44 - 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()); header.putInt(totalLen.toInt())
        header.put("WAVE".toByteArray()); header.put("fmt ".toByteArray())
        header.putInt(16); header.putShort(1); header.putShort(1)
        header.putInt(SR); header.putInt(SR * 2)
        header.putShort(2); header.putShort(16)
        header.put("data".toByteArray()); header.putInt(dataLen.toInt())
        r.write(header.array())
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val pauseResume = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java)
                .setAction(if (isPaused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 2, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val secs = elapsedMs / 1000
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Идёт запись")
            .setContentText(ru.mytolm.aidictaphone.util.TimeFmt.duration(secs * 1000))
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, if (isPaused) "Продолжить" else "Пауза", pauseResume)
            .addAction(0, "Стоп", stop)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Запись", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() {
        if (isRecording) finishRecording()
        scope.cancel()
        super.onDestroy()
    }
}
