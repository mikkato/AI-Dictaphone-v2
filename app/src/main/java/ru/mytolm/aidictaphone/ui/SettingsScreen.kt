package ru.mytolm.aidictaphone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import ru.mytolm.aidictaphone.data.App
import ru.mytolm.aidictaphone.data.LlmClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onModels: () -> Unit) {
    val settings = App.instance.settings
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var baseUrl by remember { mutableStateOf(settings.llm.value.baseUrl) }
    var apiKey by remember { mutableStateOf(settings.llm.value.apiKey) }
    var model by remember { mutableStateOf(settings.llm.value.model) }
    var temperature by remember { mutableStateOf(settings.llm.value.temperature.toFloat()) }
    var maxTokens by remember { mutableIntStateOf(settings.llm.value.maxTokens) }
    var chunkLimit by remember { mutableIntStateOf(settings.llm.value.chunkCharLimit) }
    var ctxWindow by remember { mutableIntStateOf(settings.contextWindow) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var modelPicker by remember { mutableStateOf<List<String>?>(null) }
    var picked by remember { mutableStateOf("") }

    var language by remember { mutableStateOf(settings.whisperLanguage) }
    var vocab by remember { mutableStateOf(settings.customVocabulary.joinToString("\n")) }
    var threads by remember { mutableIntStateOf(settings.inferenceThreads) }
    var diarization by remember { mutableStateOf(settings.diarizationEnabled) }
    var speakers by remember { mutableIntStateOf(settings.expectedSpeakers) }

    var langMenu by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        save(settings, baseUrl, apiKey, model, temperature, maxTokens,
                            chunkLimit, vocab, threads, language)
                        onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                title = { Text("Настройки") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background))
        }
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {

            // ---------- Суммаризация ----------
            SectionTitle("Суммаризация")
            var sumMode by remember { mutableStateOf(settings.summarizeMode) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val sumOpts = listOf("cloud" to "Облако (API)", "local" to "На устройстве (LLM)")
                sumOpts.forEach { (v, label) ->
                    if (sumMode == v)
                        Button(onClick = { sumMode = v; settings.summarizeMode = v }) { Text(label) }
                    else
                        OutlinedButton(onClick = { sumMode = v; settings.summarizeMode = v }) {
                            Text(label)
                        }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (sumMode == "local") {
                LocalLlmSection()
            } else {
            OutlinedTextField(baseUrl, { baseUrl = it },
                label = { Text("Base URL (добавится /v1)") },
                placeholder = { Text("https://api.example.com/v1") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(apiKey, { apiKey = it },
                label = { Text("API-ключ") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Filled.VisibilityOff
                             else Icons.Filled.Visibility, null)
                    }
                },
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it },
                label = { Text("Имя модели") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("temperature = %.1f".format(temperature), fontSize = 13.sp)
                    Slider(temperature, { temperature = it },
                        valueRange = 0f..1f, steps = 19)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    maxTokens.toString(), { maxTokens = it.filter(Char::isDigit).toIntOrNull() ?: 0 },
                    label = { Text("max_tokens") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    chunkLimit.toString(),
                    { chunkLimit = it.filter(Char::isDigit).toIntOrNull() ?: 0 },
                    label = { Text("лимит чанка, симв.") }, singleLine = true,
                    modifier = Modifier.weight(1f))
            }
            OutlinedTextField(
                ctxWindow.toString(),
                { v ->
                    ctxWindow = v.filter(Char::isDigit).toIntOrNull() ?: 0
                    settings.contextWindow = ctxWindow
                },
                label = { Text("контекстное окно, токенов") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Text("Автоопределяется из /v1/models при подключении, если провайдер его отдаёт.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    save(settings, baseUrl, apiKey, model, temperature, maxTokens,
                        chunkLimit, vocab, threads, language)
                    testing = true
                    scope.launch {
                        val r = App.instance.llmClient.testConnection()
                        if (r.isSuccess) {
                            val ms = App.instance.llmClient.fetchModels().getOrNull()
                                ?: emptyList()
                            if (ms.isNotEmpty()) {
                                picked = if (model in ms) model else ms.first()
                                modelPicker = ms
                            } else {
                                snackbar.showSnackbar(
                                    "Подключение успешно — эндпоинт не отдаёт список моделей")
                            }
                        } else {
                            snackbar.showSnackbar(
                                r.exceptionOrNull()?.message ?: "Ошибка сети")
                        }
                        testing = false
                    }
                }, enabled = !testing) {
                    if (testing) CircularProgressIndicator(Modifier.size(16.dp),
                        strokeWidth = 2.dp)
                    else Text("Тест соединения")
                }
            }

            }

            modelPicker?.let { list ->
                AlertDialog(
                    onDismissRequest = { modelPicker = null },
                    title = { Text("Доступные модели эндпоинта") },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            list.forEach { id ->
                                Row(Modifier.fillMaxWidth()
                                    .clickable { picked = id }
                                    .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = picked == id,
                                        onClick = { picked = id })
                                    Text(id, fontSize = 13.sp,
                                        modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (picked.isNotBlank()) {
                                model = picked
                                save(settings, baseUrl, apiKey, model, temperature,
                                    maxTokens, chunkLimit, vocab, threads, language)
                            }
                            modelPicker = null
                        }) { Text("Выбрать") }
                    },
                    dismissButton = {
                        TextButton(onClick = { modelPicker = null }) { Text("Отмена") }
                    },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ---------- Транскрипция ----------
            val activeId = settings.activeModelId
            val located = ru.mytolm.aidictaphone.data.ModelRegistry.locate(activeId ?: "")
            SectionTitle(when {
                located != null -> "Модель: ${located.first.title} · ${located.second.quant}"
                activeId == "custom" && settings.whisperModelFile != null ->
                    "Модель: своя GGML (${settings.whisperModelFile!!.substringAfterLast('/')})"
                settings.whisperModelFile != null ->
                    "Модель: ${settings.whisperModelFile!!.substringAfterLast('/')}"
                else -> "Модель не выбрана"
            })
            Text(when {
                located != null ->
                    "${located.second.label} · ${located.first.engine} · " +
                    "%.0f МБ".format(located.second.sizeBytes / 1_048_576.0) +
                    " · транскрипция локальная, аудио не покидает устройство"
                else -> "Загрузите модель на экране «Модели…» — она хранится в папке приложения"
            }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { langMenu = true }) { Text("Язык: $language") }
                DropdownMenu(langMenu, { langMenu = false }) {
                    listOf("auto", "ru", "en", "uk", "de", "fr", "es").forEach {
                        DropdownMenuItem(text = { Text(if (it == "auto") "auto (приоритет RU)" else it) },
                            onClick = { language = it; langMenu = false })
                    }
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = onModels) { Text("Модели…") }
            }
            OutlinedTextField(vocab, { vocab = it },
                label = { Text("Кастомная лексика (по строке на термин)") },
                placeholder = { Text("АО «ТеплоЭнерго», KTA50-G3, БКНС") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp))
            Text("Термины передаются в initial_prompt whisper.cpp — повышают точность " +
                 "распознавания редких слов.", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Потонов inference: $threads", fontSize = 13.sp)
            Slider(threads.toFloat(), { threads = it.toInt() },
                valueRange = 2f..16f, steps = 13)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ---------- Оформление ----------
            SectionTitle("Оформление")
            val themeMode by settings.theme.collectAsState()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val opts = listOf("system" to "Как в системе", "light" to "Светлая", "dark" to "Тёмная")
                opts.forEach { (v, label) ->
                    if (themeMode == v)
                        Button(onClick = { settings.saveTheme(v) }) { Text(label) }
                    else
                        OutlinedButton(onClick = { settings.saveTheme(v) }) { Text(label) }
                }
            }
            Text("Тема применяется сразу, выбирается на всех экранах.", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ---------- Диаризация ----------
            SectionTitle("Диаризация спикеров")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(diarization, {
                    diarization = it
                    settings.diarizationEnabled = it
                })
                Spacer(Modifier.width(10.dp))
                Text("Определять спикеров (sherpa-onnx)", fontSize = 14.sp)
            }
            if (diarization) {
                Text("Ожидаемое число спикеров: $speakers", fontSize = 13.sp)
                Slider(speakers.toFloat(), { speakers = it.toInt() },
                    valueRange = 2f..6f, steps = 3)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ---------- Приватность ----------
            Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Приватность", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Аудио и транскрипты не покидают устройство. Текст транскрипта " +
                         "отправляется только в указанный вами LLM-API для саммари. " +
                         "API-ключ хранится в зашифрованном хранилище (Android Keystore) " +
                         "и не попадает в логи и экспорт.",
                        fontSize = 12.sp, lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SectionTitle(t: String) =
    Text(t, fontWeight = FontWeight.Medium, fontSize = 15.sp)

private fun save(
    settings: ru.mytolm.aidictaphone.data.SettingsRepository,
    baseUrl: String, apiKey: String, model: String, temperature: Float,
    maxTokens: Int, chunkLimit: Int, vocab: String, threads: Int, language: String,
) {
    settings.saveLlm(baseUrl, apiKey, model, temperature, maxTokens, chunkLimit)
    settings.whisperLanguage = language
    settings.customVocabulary = vocab.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    settings.setInferenceThreads(threads)
}

// ---------- Локальная LLM (суммаризация на устройстве) ----------

@Composable
private fun LocalLlmSection() {
    val settings = App.instance.settings
    val scope = rememberCoroutineScope()
    val progress = remember { mutableStateMapOf<String, Int>() }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val errors = remember { mutableStateMapOf<String, String>() }
    var refresh by remember { mutableIntStateOf(0) }
    val selectedId = settings.localLlmId

    Text("Малые multilingual-модели (GGUF, llama.cpp). Одновременно с моделью распознавания " +
         "в памяти держится только одна: при суммаризации ASR выгружается, при распознавании — " +
         "LLM. Текущий режим и RAM — на плавающей плашке внизу экрана.",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))

    ru.mytolm.aidictaphone.data.LlmRegistry.FAMILIES.forEach { fam ->
        var sel by remember(fam.id) {
            mutableStateOf(fam.models.firstOrNull { it.id == selectedId } ?: fam.models.first())
        }
        Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text(fam.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(fam.subtitle, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fam.models.forEach { m ->
                        FilterChip(selected = sel.id == m.id, onClick = { sel = m },
                            label = { Text(m.label, fontSize = 11.sp) })
                    }
                }
                val m = sel
                val installed = App.instance.downloader.installedLlm(m)
                if (busy[m.id] == true) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { (progress[m.id] ?: 0) / 100f },
                        modifier = Modifier.fillMaxWidth())
                    Text("${progress[m.id] ?: 0}% — возобновляется после обрыва",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                errors[m.id]?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (!installed && busy[m.id] != true) {
                        Button(onClick = {
                            busy[m.id] = true; errors.remove(m.id)
                            scope.launch {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    App.instance.downloader.downloadLlm(m) { ev ->
                                        when (ev) {
                                            is ru.mytolm.aidictaphone.data.DownloadEvent.Progress ->
                                                progress[m.id] = if (ev.total > 0)
                                                    (100.0 * ev.downloaded / ev.total).toInt() else 0
                                            is ru.mytolm.aidictaphone.data.DownloadEvent.Failed ->
                                                errors[m.id] = ev.message
                                            else -> {}
                                        }
                                    }
                                }.onFailure { errors[m.id] = it.message ?: "Ошибка загрузки" }
                                busy[m.id] = false; refresh++
                            }
                        }) { Text("Скачать ${m.label.split("·").last().trim()}") }
                    }
                    if (installed) {
                        if (selectedId == m.id)
                            AssistChip(onClick = {}, label = { Text("выбрана", fontSize = 11.sp) })
                        else
                            Button(onClick = { settings.localLlmId = m.id; refresh++ }) {
                                Text("Использовать")
                            }
                        OutlinedIconButton(onClick = {
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                java.io.File(App.instance.downloader.modelsDir(), m.fileName)
                                    .delete()
                                if (settings.localLlmId == m.id) settings.localLlmId = null
                                refresh++
                            }
                        }) { Icon(Icons.Filled.Delete, "Удалить") }
                    }
                }
            }
        }
    }
}
