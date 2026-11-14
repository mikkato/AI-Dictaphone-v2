package ru.mytolm.aidictaphone.ui

import android.app.Application
import android.content.Intent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ru.mytolm.aidictaphone.data.*
import ru.mytolm.aidictaphone.voice.TranscriptionWorker
import ru.mytolm.aidictaphone.util.TimeFmt
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class PlayerUi(
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1.0f,
)

class DetailViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = App.instance.dao

    var noteId by mutableLongStateOf(0L)
        private set
    val note = MutableStateFlow<Note?>(null)
    val segments = MutableStateFlow<List<Segment>>(emptyList())
    val highlights = MutableStateFlow<List<Highlight>>(emptyList())
    val textNotes = MutableStateFlow<List<TextNote>>(emptyList())
    val summaries = MutableStateFlow<List<SummaryVersion>>(emptyList())

    val player = MutableStateFlow(PlayerUi())
    var exoPlayer: androidx.media3.exoplayer.ExoPlayer? = null
        private set

    // саммари
    val summaryStreaming = MutableStateFlow(false)
    val summaryStreamText = MutableStateFlow("")
    val summaryStage = MutableStateFlow("")
    val summaryError = MutableStateFlow<String?>(null)
    // null = фаза без измеримого прогресса (загрузка модели, ожидание ответа API)
    val summaryProgress = MutableStateFlow<Float?>(null)
    var selectedTemplate by mutableStateOf("protocol")
    var detailedStyle by mutableStateOf(false)

    // active summarize job — для кнопки «Отмена» (аудит U10)
    private var summarizeJob: kotlinx.coroutines.Job? = null
    @Volatile private var summarizeCancelledByUser = false

    fun bind(id: Long) {
        if (noteId == id) return
        noteId = id
        viewModelScope.launch {
            dao.noteFlow(id).collect { n ->
                note.value = n
                n?.let { attachPlayer(it) }
            }
        }
        viewModelScope.launch { dao.segmentsFlow(id).collect { segments.value = it } }
        viewModelScope.launch { dao.highlightsFlow(id).collect { highlights.value = it } }
        viewModelScope.launch { dao.textNotesFlow(id).collect { textNotes.value = it } }
        viewModelScope.launch { dao.summariesFlow(id).collect { summaries.value = it } }
    }

    private fun attachPlayer(note: Note) {
        val p = exoPlayer ?: run {
            val ep = androidx.media3.exoplayer.ExoPlayer.Builder(getApplication()).build()
            exoPlayer = ep
            ep.addListener(object : androidx.media3.common.Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    player.update { it.copy(playing = isPlaying) }
                }
            })
            ep
        }
        if (p.currentMediaItem == null || player.value.durationMs == 0L) {
            val f = File(note.audioPath)
            if (f.exists()) {
                p.setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(f)))
                p.prepare()
                // duration из метаданных; mmr освобождается во всех ветках (runCatching/finally)
                val dur = runCatching {
                    val mmr = MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(f.absolutePath)
                        mmr.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } finally {
                        mmr.release()
                    }
                }.getOrDefault(0L)
                player.update { it.copy(durationMs = dur.coerceAtLeast(note.durationMs)) }
            }
        }
    }

    fun tick() {
        exoPlayer?.let { p ->
            player.update {
                it.copy(positionMs = p.currentPosition.coerceAtLeast(0),
                    playing = p.isPlaying)
            }
        }
    }

    fun seekTo(ms: Long) {
        exoPlayer?.seekTo(ms)
        player.update { it.copy(positionMs = ms) }
    }

    fun togglePlay() {
        val p = exoPlayer ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun skip(deltaMs: Long) {
        val pos = (player.value.positionMs + deltaMs)
            .coerceIn(0, player.value.durationMs)
        seekTo(pos)
    }

    fun setSpeed(s: Float) {
        exoPlayer?.playbackParameters =
            androidx.media3.common.PlaybackParameters(s) // тональность сохраняется встроенным time-stretch
        player.update { it.copy(speed = s) }
    }

    fun transcriptText(): String = segments.value.joinToString(" ") { it.text }.trim()

    fun summarize() {
        if (summaryStreaming.value) return
        val text = transcriptText()
        if (text.length < LlmClient.MIN_TRANSCRIPT_CHARS) {
            summaryError.value = "Транскрипт короче ${LlmClient.MIN_TRANSCRIPT_CHARS} символов — " +
                "саммаризация недоступна (как в Samsung Transcript Assist)"
            return
        }
        summaryError.value = null
        summaryStage.value = ""
        summaryProgress.value = 0f
        summaryStreaming.value = true
        summaryStreamText.value = ""
        summarizeCancelledByUser = false
        val modelName = if (App.instance.settings.summarizeMode == "local" &&
                App.instance.localLlmClient.modelFile() != null)
            "local:" + (App.instance.settings.localLlmId ?: "gguf")
        else App.instance.settings.llm.value.model
        // финальная генерация: прогресс по числу отстримленных символов (оценка ~2000)
        fun onText(chunk: String) {
            summaryStreamText.update { it + chunk }
            val len = summaryStreamText.value.length
            summaryProgress.update { prev ->
                val streamFrac = 0.70f + (len / 2000f).coerceIn(0f, 1f) * 0.28f
                if (prev != null && prev >= streamFrac) prev else streamFrac
            }
        }
        summarizeJob = viewModelScope.launch {
            try {
                val full = buildTranscriptForLlm()
                val localReady = App.instance.settings.summarizeMode == "local" &&
                        App.instance.localLlmClient.modelFile() != null
                val result = if (localReady)
                    App.instance.localLlmClient.summarize(
                        full, selectedTemplate, detailedStyle, ::onText,
                        object : ru.mytolm.aidictaphone.voice.LocalLlmClient.Progress {
                            override fun onStage(stage: String) { summaryStage.value = stage }
                            override fun onChunks(done: Int, total: Int) {
                                summaryProgress.value = 0.05f + 0.65f * done / total
                            }
                        })
                else
                    App.instance.llmClient.summarize(
                        transcriptText = full, templateId = selectedTemplate,
                        detailed = detailedStyle,
                        onToken = ::onText,
                        onChunkProgress = { done, total ->
                            summaryProgress.value = 0.05f + 0.65f * done / total
                        }
                    )
                summaryProgress.value = 1f
                App.instance.dao.insertSummary(SummaryVersion(
                    noteId = noteId,
                    style = if (detailedStyle) "detailed" else "standard",
                    templateId = selectedTemplate,
                    model = modelName,
                    text = result.ifBlank { summaryStreamText.value }))
                App.instance.dao.trimSummaries(noteId, keep = 5)
                App.instance.dao.setSummaryPreview(noteId,
                    result.take(160).ifBlank { summaryStreamText.value.take(160) })
                note.value?.let {
                    App.instance.dao.updateNote(it.copy(status = NoteStatus.READY))
                }
                Notifier.summaryDone(getApplication(), noteId, note.value?.title ?: "Заметка")
            } catch (e: Exception) {
                android.util.Log.e("SummErr", "summarize failed", e)
                val userCancelled = summarizeCancelledByUser ||
                    e is kotlinx.coroutines.CancellationException
                if (!userCancelled) {
                    summaryError.value = e.message
                        ?: ("Ошибка суммаризации: " + e.javaClass.simpleName)
                }
                if (summaryStreamText.value.isNotBlank()) { // частичный ответ сохраняется (§6)
                    App.instance.dao.insertSummary(SummaryVersion(
                        noteId = noteId, style = "partial",
                        templateId = selectedTemplate,
                        model = modelName,
                        text = summaryStreamText.value))
                }
                if (!userCancelled) {
                    Notifier.summaryFailed(getApplication(), noteId,
                        e.message ?: "Ошибка суммаризации")
                }
            } finally {
                summaryStreaming.value = false
            }
        }
    }

    /** Отмена генерации саммари (аудит U10): job + нативная остановка локального чата. */
    fun cancelSummary() {
        if (!summaryStreaming.value) return
        summarizeCancelledByUser = true
        summarizeJob?.cancel()
        summarizeJob = null
        runCatching { ru.mytolm.aidictaphone.voice.LlamaEngine.cancelGeneration() }
        summaryStage.value = "Отменено"
        summaryStreaming.value = false
    }

    /** Отмена транскрибации заметки (аудит U11). */
    fun cancelTranscribe() {
        TranscriptionWorker.cancel(getApplication(), noteId)
        viewModelScope.launch {
            // если воркер ещё не стартовал — статус сам уйдёт из QUEUED в AUDIO
            val n = App.instance.dao.note(noteId)
            if (n != null && n.status == NoteStatus.QUEUED)
                App.instance.dao.setNoteStatus(noteId, NoteStatus.AUDIO, prog = 0)
        }
    }

    private fun buildTranscriptForLlm(): String {
        val segs = segments.value.map {
            Pair(TimeFmt.clock(it.startMs) + " " + speakerLabel(it), it.text)
        }
        val hls = highlights.value.map { Pair(TimeFmt.clock(it.timeMs), it.label) }
        val notes = textNotes.value.map { Pair(TimeFmt.clock(it.timeMs), it.text) }
        return LlmClient.buildTranscriptText(segs, hls, notes)
    }

    fun speakerLabel(s: Segment): String =
        s.speakerName ?: if (s.speakerId > 0) "Спикер ${s.speakerId}" else ""

    fun editSegment(seg: Segment, newText: String) = viewModelScope.launch {
        dao.updateSegment(seg.copy(text = newText))
    }

    fun renameSpeaker(speakerId: Int, name: String) = viewModelScope.launch {
        dao.renameSpeaker(noteId, speakerId, name)
    }

    fun reTranscribe() {
        viewModelScope.launch {
            dao.clearSegments(noteId)
            TranscriptionWorker.schedule(getApplication(), noteId)
        }
    }

    fun export(fmt: String): Uri? {
        val n = note.value ?: return null
        val dir = File(getApplication<Application>()
            .getExternalFilesDir(null), "export").apply { mkdirs() }
        val base = n.title.replace(Regex("[^\\p{L}\\p{N}_ -]"), "").ifBlank { "note" }
        val file = File(dir, "$base.$fmt")
        val segs = segments.value
        val body = when (fmt) {
            "txt" -> buildString {
                append(segs.joinToString("\n") {
                    "[${TimeFmt.clock(it.startMs)}] ${speakerLabel(it).ifBlank { null }?.plus(": ") ?: ""}${it.text}"
                })
                summaries.value.firstOrNull()?.let {
                    append("\n\n══════════ САММАРИ (").append(it.style).append(") ══════════\n\n")
                    append(ru.mytolm.aidictaphone.util.PlainFmt.toPlainText(it.text))
                }
            }
            "md" -> buildString {
                appendLine("# ${n.title}\n")
                appendLine("_${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(n.createdAt))} · ${TimeFmt.durationHuman(n.durationMs)}_\n")
                segs.forEach {
                    val sp = speakerLabel(it)
                    appendLine("**[${TimeFmt.clock(it.startMs)}]${if (sp.isNotEmpty()) " $sp" else ""}** ${it.text}\n")
                }
                summaries.value.firstOrNull()?.let {
                    appendLine("\n## Саммари (${it.style})\n\n${it.text}")
                }
            }
            "srt" -> segs.mapIndexed { i, s ->
                "${i + 1}\n${TimeFmt.srt(s.startMs)} --> ${TimeFmt.srt(s.endMs.coerceAtLeast(s.startMs + 500))}\n${s.text}\n"
            }.joinToString("\n")
            "json" -> kotlinx.serialization.json.buildJsonObject {
                put("title", kotlinx.serialization.json.JsonPrimitive(n.title))
                put("createdAt", kotlinx.serialization.json.JsonPrimitive(n.createdAt))
                put("durationMs", kotlinx.serialization.json.JsonPrimitive(n.durationMs))
                put("segments", kotlinx.serialization.json.buildJsonArray {
                    segs.forEach { s ->
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("startMs", kotlinx.serialization.json.JsonPrimitive(s.startMs))
                            put("endMs", kotlinx.serialization.json.JsonPrimitive(s.endMs))
                            put("speakerId", kotlinx.serialization.json.JsonPrimitive(s.speakerId))
                            put("speakerName", kotlinx.serialization.json.JsonPrimitive(s.speakerName ?: ""))
                            put("speakerOrigin", kotlinx.serialization.json.JsonPrimitive(s.speakerOrigin))
                            put("text", kotlinx.serialization.json.JsonPrimitive(s.text))
                        })
                    }
                })
                put("highlights", kotlinx.serialization.json.buildJsonArray {
                    highlights.value.forEach { h ->
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("timeMs", kotlinx.serialization.json.JsonPrimitive(h.timeMs))
                            put("label", kotlinx.serialization.json.JsonPrimitive(h.label))
                        })
                    }
                })
                put("summaries", kotlinx.serialization.json.buildJsonArray {
                    summaries.value.forEach { sm ->
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("style", kotlinx.serialization.json.JsonPrimitive(sm.style))
                            put("templateId", kotlinx.serialization.json.JsonPrimitive(sm.templateId))
                            put("model", kotlinx.serialization.json.JsonPrimitive(sm.model))
                            put("text", kotlinx.serialization.json.JsonPrimitive(sm.text))
                            put("textPlain", kotlinx.serialization.json.JsonPrimitive(
                                ru.mytolm.aidictaphone.util.PlainFmt.toPlainText(sm.text)))
                        })
                    }
                })
                // API-ключ намеренно отсутствует (ТЗ §6)
            }.toString()
            else -> return null
        }
        file.writeText(body)
        return FileProvider.getUriForFile(
            getApplication(),
            getApplication<Application>().packageName + ".fileprovider", file)
    }

    fun copyToClipboard(text: String) {
        val cm = getApplication<Application>()
            .getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("transcript", text))
    }

    override fun onCleared() {
        exoPlayer?.release()
        exoPlayer = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(noteId: Long, onBack: () -> Unit) {
    val vm: DetailViewModel = viewModel()
    LaunchedEffect(noteId) { vm.bind(noteId) }

    val note by vm.note.collectAsState()
    val segments by vm.segments.collectAsState()
    val highlights by vm.highlights.collectAsState()
    val summaries by vm.summaries.collectAsState()
    val playerUi by vm.player.collectAsState()
    val streaming by vm.summaryStreaming.collectAsState()
    val summaryStage by vm.summaryStage.collectAsState()
    val streamText by vm.summaryStreamText.collectAsState()
    val summaryError by vm.summaryError.collectAsState()
    val context = LocalContext.current

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editTarget by remember { mutableStateOf<Segment?>(null) }
    var renameTarget by remember { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()

    // тикер позиции + автоскролл к активному сегменту
    LaunchedEffect(playerUi.playing, segments.size) {
        while (true) {
            vm.tick()
            kotlinx.coroutines.delay(200)
        }
    }
    val activeIndex = remember(playerUi.positionMs, segments) {
        segments.indexOfLast { it.startMs <= playerUi.positionMs }.takeIf { it >= 0 }
    }
    LaunchedEffect(activeIndex, playerUi.playing) {
        if (tab == 0 && activeIndex != null && playerUi.playing) {
            listState.animateScrollToItem(activeIndex.coerceAtLeast(0))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    IconButton(onClick = {
                        vm.export("txt")?.let { uri ->
                            val i = Intent(Intent.ACTION_SEND).apply {
                                type = "*/*"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(i, "Экспорт/поделиться"))
                        }
                    }) { Icon(Icons.Filled.Share, "Поделиться") }
                    ExportMenu(vm)
                },
                title = { Text(note?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background),
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            note?.let { n ->
                val date = SimpleDateFormat("d MMMM yyyy · HH:mm", Locale("ru"))
                    .format(Date(n.createdAt))
                Text(
                    text = "$date · ${TimeFmt.durationHuman(n.durationMs)}" +
                        if (n.status == NoteStatus.ERROR) " · ${n.errorMessage ?: "ошибка"}" else "",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp))
            }
            Spacer(Modifier.height(8.dp))

            TabRow(selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background) {
                Tab(selected = tab == 0, onClick = { tab = 0 },
                    text = { Text("Транскрипт") })
                Tab(selected = tab == 1, onClick = { tab = 1 },
                    text = { Text("Саммари") })
                Tab(selected = tab == 2, onClick = { tab = 2 },
                    text = { Text("Хайлайты (${highlights.size})") })
            }

            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> TranscriptTab(vm, segments, activeIndex, listState,
                        onEdit = { editTarget = it },
                        onRename = { renameTarget = it })
                    1 -> SummaryTab(vm, summaries, streaming, streamText, summaryError)
                    2 -> HighlightsTab(vm, highlights)
                }
            }
            PlayerBar(vm, playerUi)
        }
    }

    editTarget?.let { seg ->
        var text by remember { mutableStateOf(seg.text) }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("Редактировать сегмент") },
            text = {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().height(180.dp))
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.editSegment(seg, text); editTarget = null
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text("Отмена") } },
        )
    }

    renameTarget?.let { spId ->
        var name by remember { mutableStateOf("Спикер $spId") }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Имя спикера $spId") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) vm.renameSpeaker(spId, name.trim())
                    renameTarget = null
                }) { Text("Применить ко всем") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Отмена") } },
        )
    }
}

// маленькая утилита чтобы не конфликтовало имя share с Icons

@Composable
private fun ExportMenu(vm: DetailViewModel) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, "Экспорт")
        }
        DropdownMenu(expanded, { expanded = false }) {
            listOf("txt", "md", "srt", "json").forEach { fmt ->
                DropdownMenuItem(text = { Text("Экспорт .$fmt") }, onClick = {
                    expanded = false
                    vm.export(fmt)?.let { uri ->
                        val i = Intent(Intent.ACTION_SEND).apply {
                            type = "*/*"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(i, "Отправить .$fmt"))
                    }
                })
            }
            DropdownMenuItem(text = { Text("Копировать транскрипт") }, onClick = {
                expanded = false
                vm.copyToClipboard(vm.transcriptText())
            })
            if (vm.summaries.value.isNotEmpty())
                DropdownMenuItem(text = { Text("Копировать саммари") }, onClick = {
                    expanded = false
                    vm.copyToClipboard(ru.mytolm.aidictaphone.util
                        .PlainFmt.toPlainText(vm.summaries.value.first().text))
                })
            DropdownMenuItem(text = { Text("Пере-транскрибировать") }, onClick = {
                expanded = false; vm.reTranscribe()
            })
        }
    }
}

@Composable
private fun TranscriptTab(
    vm: DetailViewModel,
    segments: List<Segment>,
    activeIndex: Int?,
    listState: LazyListState,
    onEdit: (Segment) -> Unit,
    onRename: (Int) -> Unit,
) {
    val note by vm.note.collectAsState()
    if (segments.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when (note?.status) {
                    NoteStatus.TRANSCRIBING -> "Транскрибация… ${note?.transcriptProgress ?: 0}%"
                    NoteStatus.QUEUED -> "Задача в очереди…"
                    NoteStatus.ERROR -> "Ошибка: ${note?.errorMessage ?: "неизвестно"}\n" +
                        "Проверьте модель в «Настройки → Модели» и нажмите «Пере-транскрибировать»."
                    else -> "Транскрипта пока нет. Откройте меню → «Пере-транскрибировать»."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            if (note?.status == NoteStatus.TRANSCRIBING) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { (note?.transcriptProgress ?: 0) / 100f })
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.cancelTranscribe() }) {
                    Text("Отменить транскрибацию")
                }
            }
            if (note?.status == NoteStatus.QUEUED) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.cancelTranscribe() }) {
                    Text("Отменить очередь")
                }
            }
        }
        return
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 170.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(segments, key = { it.id }) { seg ->
            val idx = segments.indexOf(seg)
            val isActive = idx == activeIndex
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isActive)
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    else Color.Transparent)
                    .clickable { vm.seekTo(seg.startMs) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (seg.speakerId > 0 || seg.speakerName != null) {
                        Text(vm.speakerLabel(seg), fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.secondary)
                        Text(" · ", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(TimeFmt.clock(seg.startMs), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    if (seg.speakerId > 0) {
                        IconButton(onClick = { onRename(seg.speakerId) }) {
                            Icon(Icons.Filled.Person, "Изменить имя спикера",
                                Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { onEdit(seg) }) {
                        Icon(Icons.Filled.Edit, "Редактировать сегмент", Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(seg.text, fontSize = 15.sp, lineHeight = 22.sp)
            }
        }
    }
}

@Composable
private fun SummaryTab(
    vm: DetailViewModel,
    summaries: List<SummaryVersion>,
    streaming: Boolean,
    streamText: String,
    error: String?,
) {
    val templates = LlmClient.BUILTIN_TEMPLATES
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(PaddingValues(20.dp, 12.dp, 20.dp, 100.dp))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Шаблон:", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            templates.forEach { (id, label) ->
                FilterChip(
                    selected = vm.selectedTemplate == id,
                    onClick = { vm.selectedTemplate = id },
                    label = { Text(label, fontSize = 12.sp) },
                    modifier = Modifier.padding(end = 4.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(vm.detailedStyle, { vm.detailedStyle = it })
            Spacer(Modifier.width(8.dp))
            Text(if (vm.detailedStyle) "Detailed (подробно)" else "Standard (сжато)",
                fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Button(onClick = { vm.summarize() },
                enabled = !streaming,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary)) {
                Icon(Icons.Filled.AutoAwesome, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (summaries.isEmpty()) "Сгенерировать" else "Заново")
            }
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Surface(color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp)) {
                Text(it, Modifier.padding(12.dp), fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (streaming) {
            val prog by vm.summaryProgress.collectAsState()
            val animated by animateFloatAsState(
                targetValue = prog ?: 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow),
                label = "summaryProgress")
            val stageText = vm.summaryStage.collectAsState().value
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stageText.ifEmpty { "Генерация саммари…" },
                            fontSize = 13.sp, modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface)
                        Text(prog?.let { "${(it * 100).toInt()}%" } ?: "…",
                            fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (prog != null) {
                        LinearProgressIndicator(
                            progress = { animated },
                            modifier = Modifier.fillMaxWidth().height(8.dp)
                                .clip(RoundedCornerShape(4.dp)))
                    } else {
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().height(8.dp)
                                .clip(RoundedCornerShape(4.dp)))
                    }
                    // «Отмена» генерации (аудит U10): локальная генерация может идти минуты
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { vm.cancelSummary() }) {
                            Text("Отменить генерацию")
                        }
                    }
                    if (streamText.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Отпечатано ${streamText.length} символов",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (streamText.isBlank()) {
                Text("ожидание первых токенов…", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(ru.mytolm.aidictaphone.util.PlainFmt.toPlainText(streamText),
                fontSize = 15.sp, lineHeight = 23.sp)
        } else if (summaries.isEmpty()) {
            Text("Саммари ещё нет. Транскрипт отправляется в ваш LLM-API " +
                "(карта-редьюс для длинных записей), аудио не покидает устройство.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val latest = summaries.first()
            Surface(shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("От ${SimpleDateFormat("dd.MM HH:mm", Locale("ru"))
                        .format(Date(latest.createdAt))} · ${latest.style} · ${latest.model}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(ru.mytolm.aidictaphone.util.PlainFmt.toPlainText(latest.text),
                        fontSize = 15.sp, lineHeight = 23.sp)
                }
            }
            if (summaries.size > 1) {
                Spacer(Modifier.height(10.dp))
                Text("История перегенераций (${summaries.size - 1})", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                summaries.drop(1).forEach { s ->
                    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${SimpleDateFormat("dd.MM HH:mm", Locale("ru"))
                                .format(Date(s.createdAt))} · ${s.style}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(ru.mytolm.aidictaphone.util.PlainFmt.toPlainText(s.text),
                                fontSize = 13.sp, maxLines = 6,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HighlightsTab(vm: DetailViewModel, highlights: List<Highlight>) {
    val textNotes by vm.textNotes.collectAsState()
    if (highlights.isEmpty() && textNotes.isEmpty()) {
        Text("Хайлайты и заметки добавляются на экране записи кнопкой «Highlight» " +
            "и текстовым полем. Клик по хайлайту перематывает аудио.",
            Modifier.padding(20.dp), fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 170.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(highlights) { h ->
            Surface(shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().clickable { vm.seekTo(h.timeMs) }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bookmark, "Момент", Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(10.dp))
                    Text(TimeFmt.clock(h.timeMs), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(10.dp))
                    Text(h.label.ifBlank { "момент" }, fontSize = 13.sp)
                }
            }
        }
        items(textNotes) { tn ->
            Surface(shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Заметка ${TimeFmt.clock(tn.timeMs)}", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(tn.text, fontSize = 14.sp)
                }
            }
        }
    }
}

/** Sticky-плеер поверх контента — waveform-шкала, ±10 c, скорость (как Plaud/SoundTouch). */
@Composable
private fun PlayerBar(vm: DetailViewModel, p: PlayerUi) {
    Surface(
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            // Play — в левом верхнем углу карточки: нижний транспорт перекрывался информером.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { vm.togglePlay() },
                    contentAlignment = Alignment.Center) {
                    Icon(if (p.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (p.playing) "Пауза" else "Воспроизвести",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text(TimeFmt.clock(p.positionMs), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(" / ", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(TimeFmt.clock(p.durationMs), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                // ±10 с и скорость — здесь же, внизу их перекрывал информер
                IconButton(onClick = { vm.skip(-10_000) }) {
                    Icon(Icons.Filled.Replay10, "−10 с")
                }
                IconButton(onClick = { vm.skip(10_000) }) {
                    Icon(Icons.Filled.Forward10, "+10 с")
                }
                TextButton(onClick = {
                    val speeds = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                    val cur = speeds.indexOfFirst { kotlin.math.abs(it - p.speed) < 0.01f }
                    vm.setSpeed(speeds[(cur + 1) % speeds.size])
                }) {
                    Text("${p.speed}×", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            // сегментная waveform-шкала (детерминированный паттерн по времени)
            val played = if (p.durationMs > 0) p.positionMs.toFloat() / p.durationMs else 0f
            Row(
                Modifier.fillMaxWidth().height(28.dp)
                    .clickable { /* seek по тапу ниже через Slider-обёртку не нужен в v1 */ },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val bars = 48
                for (i in 0 until bars) {
                    val h = 6 + ((i * 7919 + 13) % 17)
                    Box(Modifier
                        .weight(1f)
                        .height(h.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (i / bars.toFloat() < played)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)))
                }
            }
        }
    }
}
