package ru.mytolm.aidictaphone.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ru.mytolm.aidictaphone.data.App
import ru.mytolm.aidictaphone.data.Note
import ru.mytolm.aidictaphone.data.NoteStatus
import ru.mytolm.aidictaphone.voice.RecordingService
import ru.mytolm.aidictaphone.voice.TranscriptionWorker
import ru.mytolm.aidictaphone.util.TimeFmt
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class LibraryViewModel(app: Application) : androidx.lifecycle.AndroidViewModel(app) {
    val notes = kotlinx.coroutines.flow.MutableStateFlow<List<Note>>(emptyList())
    var query by mutableStateOf("")
        private set

    private val dao = App.instance.dao
    private var job: kotlinx.coroutines.Job? = null

    init { refresh() }

    fun onQuery(q: String) {
        query = q
        refresh()
    }

    private fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            val flow = if (query.isBlank()) dao.allNotes()
            else dao.searchNotes(query.trim().split(" ").joinToString(" ") { "$it*" })
            flow.collect { notes.value = it }
        }
    }

    fun delete(note: Note) = viewModelScope.launch {
        TranscriptionWorker.cancel(getApplication(), note.id)
        dao.deleteNote(note)
        try { File(note.audioPath).delete() } catch (_: Exception) {}
    }

    fun retryTranscribe(note: Note) = viewModelScope.launch {
        dao.clearSegments(note.id)
        TranscriptionWorker.schedule(getApplication(), note.id)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpen: (Long) -> Unit,
    onRecord: () -> Unit,
    onSettings: () -> Unit,
    onModels: () -> Unit,
) {
    val vm: LibraryViewModel = viewModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notes by vm.notes.collectAsState()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            scope.launch {
                val name = queryName(context, uri)
                val result = importAudio(context, uri, name)
                result.onSuccess { noteId ->
                    TranscriptionWorker.schedule(context, noteId)
                }.onFailure {
                    android.widget.Toast.makeText(context,
                        it.message ?: "Ошибка импорта",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Верх: заголовок + настройки
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Заметки", fontSize = 30.sp, fontWeight = FontWeight.Light)
                Row {
                    IconButton(onClick = onModels) { Icon(Icons.Filled.Download, "Модели") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Настройки") }
                }
            }

            // Поиск (FTS)
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.onQuery(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                placeholder = { Text("Поиск по заметкам и транскриптам") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.onQuery(vm.query) }),
            )

            Spacer(Modifier.height(12.dp))

            // Тёмная CTA-карточка «Запись» (как Capture у PLAUD)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.horizontalGradient(
                        listOf(Color(0xFF0A0A0A), Color(0xFF1D3A44))))
                    .clickable { onRecord() }
                    .padding(20.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    Icon(Icons.Filled.Mic, null, tint = Color.White,
                        modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(24.dp))
                    Text("Записать важный момент", color = Color.White,
                        fontWeight = FontWeight.Medium, fontSize = 17.sp)
                    Text("Запись с микрофона телефона", color = Color(0xFF9AA0A6),
                        fontSize = 12.sp)
                }
                Icon(Icons.Filled.ChevronRight, null, tint = Color.White)
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Недавние файлы", fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = {
                    importLauncher.launch(arrayOf("audio/mpeg", "audio/mp3", "audio/wav",
                        "audio/x-wav", "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac",
                        "audio/amr", "audio/3gpp", "audio/*", "video/mp4"))
                }) {
                    Icon(Icons.Filled.FileOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Импорт аудио")
                }
            }
            Spacer(Modifier.height(8.dp))

            if (notes.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.GraphicEq, null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Пока нет записей.\nИмпортируйте mp3/wav или начните запись.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
            }

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(notes, key = { it.id }) { note ->
                    NoteCard(note,
                        onClick = { onOpen(note.id) },
                        onDelete = { vm.delete(note) },
                        onRetry = { vm.retryTranscribe(note) })
                }
            }
        }
    }
}

@Composable
private fun NoteCard(note: Note, onClick: () -> Unit, onDelete: () -> Unit, onRetry: () -> Unit) {
    val statusColor = when (note.status) {
        NoteStatus.ERROR -> MaterialTheme.colorScheme.error
        NoteStatus.READY -> Color(0xFF2E7D5B)
        NoteStatus.TRANSCRIBING, NoteStatus.QUEUED -> Color(0xFFB58A2E)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when (note.status) {
        NoteStatus.AUDIO -> "аудио"
        NoteStatus.QUEUED -> "в очереди"
        NoteStatus.TRANSCRIBING -> "транскрибируется ${note.transcriptProgress}%"
        NoteStatus.TRANSCRIPT -> "транскрипт"
        NoteStatus.READY -> "готово"
        NoteStatus.ERROR -> "ошибка"
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(note.title, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    val date = SimpleDateFormat("d MMMM · HH:mm", Locale("ru"))
                        .format(Date(note.createdAt))
                    Text(
                        buildString {
                            append(date); append(" · "); append(TimeFmt.duration(note.durationMs))
                        },
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
            }
            if (note.status == NoteStatus.TRANSCRIBING) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { note.transcriptProgress / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
            }
            if (note.status == NoteStatus.ERROR && note.errorMessage != null) {
                Spacer(Modifier.height(6.dp))
                Text(note.errorMessage, fontSize = 12.sp, color = statusColor, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            note.summaryPreview?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (note.status == NoteStatus.ERROR || note.status == NoteStatus.AUDIO) {
                    TextButton(onClick = onRetry) { Text("Транскрибировать") }
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp)); Text("Удалить")
                }
            }
        }
    }
}

private suspend fun queryName(context: android.content.Context, uri: Uri): String {
    var name = "import_${System.currentTimeMillis()}.mp3"
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
    }
    return name
}

/** Импорт: копирование в приватное хранилище + заметка + предупреждение >3 ч (ТЗ §4.3). */
suspend fun importAudio(
    context: android.content.Context, uri: Uri, name: String,
): Result<Long> {
    val dao = App.instance.dao
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val ext = name.substringAfterLast('.', "mp3").lowercase()
            if (ext !in setOf("mp3", "wav", "mp4", "m4a", "aac", "amr", "ogg", "3gp"))
                return@withContext Result.failure(
                    Exception("Формат .$ext не поддерживается. Нужен mp3, wav, m4a или mp4."))
            val dir = File(context.getExternalFilesDir(null), "audio").apply { mkdirs() }
            val target = File(dir, "${System.currentTimeMillis()}_$name")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: return@withContext Result.failure(Exception("Не удалось открыть файл"))

            var durationMs = 0L
            try {
                val mmr = android.media.MediaMetadataRetriever()
                mmr.setDataSource(target.absolutePath)
                durationMs = mmr.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                mmr.release()
            } catch (_: Exception) {}

            val title = android.text.format.DateFormat
                .format("dd.MM.yyyy HH:mm", System.currentTimeMillis()).toString()
            val id = dao.insertNote(Note(title = title, audioPath = target.absolutePath,
                durationMs = durationMs))
            if (durationMs > 3 * 3600_000)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(context,
                        "Запись длиннее 3 часов — транскрипция займёт больше времени",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
