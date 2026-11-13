package ru.mytolm.aidictaphone.ui

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mytolm.aidictaphone.data.App
import ru.mytolm.aidictaphone.data.ModelRegistry
import ru.mytolm.aidictaphone.data.ModelFamily
import ru.mytolm.aidictaphone.data.WhisperModel
import java.io.File
import java.util.concurrent.Executors

class ModelViewModel(app: Application) : AndroidViewModel(app) {
    val progress = mutableStateMapOf<String, Int>()      // modelId -> %
    val downloading = mutableStateMapOf<String, Boolean>()
    val errors = mutableStateMapOf<String, String>()
    var customUrl by mutableStateOf(App.instance.settings.customModelUrl)

    private val pool = Executors.newSingleThreadExecutor()

    fun installed(model: WhisperModel): Boolean =
        App.instance.downloader.installed(model)

    fun startDownload(model: WhisperModel) {
        if (downloading[model.id] == true) return
        downloading[model.id] = true
        errors.remove(model.id)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    App.instance.downloader.download(
                        model, customUrl.takeIf { it.isNotBlank() }) { ev ->
                        when (ev) {
                            is ru.mytolm.aidictaphone.data.DownloadEvent.Progress ->
                                progress[model.id] =
                                    if (ev.total > 0) (100.0 * ev.downloaded / ev.total).toInt() else 0
                            is ru.mytolm.aidictaphone.data.DownloadEvent.Failed ->
                                errors[model.id] = ev.message
                            else -> {}
                        }
                    }
                }
                result.onFailure { errors[model.id] = it.message ?: "Ошибка загрузки" }
            } finally {
                downloading[model.id] = false
            }
        }
    }

    fun importFrom(localPath: String): String? {
        // SAF-файл модели: читается напрямую без повторного скачивания (ТЗ §3)
        val src = File(localPath)
        if (!src.exists() || src.length() < 1_000_000)
            return "Файл не найден или слишком мал для модели GGML"
        App.instance.settings.whisperModelFile = src.absolutePath
        App.instance.settings.asrKind = ModelRegistry.KIND_WHISPER
        App.instance.settings.activeModelId = "custom"
        return null
    }

    fun useModel(model: WhisperModel) {
        val s = App.instance.settings
        if (model.kind == ModelRegistry.KIND_WHISPER) {
            val part = ModelRegistry.partsOf(model).first()
            s.whisperModelFile = File(App.instance.downloader.dirFor(model), part.destName).absolutePath
        } else {
            s.whisperModelFile = null
        }
        s.asrKind = model.kind
        s.activeModelId = model.id
    }

    fun deleteModel(model: WhisperModel) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = App.instance.downloader.dirFor(model)
            if (model.kind == ModelRegistry.KIND_WHISPER) {
                ModelRegistry.partsOf(model).forEach { File(dir, it.destName).delete() }
            } else {
                dir.deleteRecursively()
            }
            if (App.instance.settings.activeModelId == model.id) {
                App.instance.settings.activeModelId = null
                App.instance.settings.whisperModelFile = null
            }
        }
    }
}

private fun fmtSize(b: Long): String =
    if (b >= 1_073_741_824L) "%.1f ГБ".format(b / 1_073_741_824.0)
    else "%d МБ".format(b / 1_048_576)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(onBack: () -> Unit) {
    val vm: ModelViewModel = viewModel()
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val activeId = App.instance.settings.activeModelId

    val safPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(
            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        importModelFromUri(context, uri, onDone = { refresh++ })
    }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            },
            title = { Text("Модели распознавания") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background))
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
            .padding(20.dp)) {

            Text("Модели скачиваются один раз и хранятся в папке приложения — аудио никуда " +
                 "не отправляется. Выберите семейство и квант (размер/точность).",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            ModelRegistry.FAMILIES.forEach { fam ->
                FamilyCard(vm, fam, activeId, refresh)
            }

            Spacer(Modifier.height(8.dp))
            OutlinedCard(Modifier.fillMaxWidth().clickable {
                safPicker.launch(arrayOf("*/*"))
            }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.FolderOpen, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Выбрать свою модель (SAF)", fontWeight = FontWeight.Medium)
                        Text(".bin GGML уже на телефоне — будет читаться без повторного " +
                             "скачивания", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = vm.customUrl,
                onValueChange = {
                    vm.customUrl = it
                    App.instance.settings.customModelUrl = it
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Свой URL / зеркало Hugging Face (whisper)") },
                placeholder = { Text(ModelRegistry.HF_BASE) },
                singleLine = true,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun FamilyCard(
    vm: ModelViewModel,
    fam: ModelFamily,
    activeId: String?,
    refresh: Int,
) {
    var sel by remember(fam.id) {
        mutableStateOf(fam.quants.firstOrNull { it.id == activeId } ?: fam.quants.firstOrNull())
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(fam.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(fam.subtitle, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SuggestionChip(onClick = {}, label = {
                    Text(if (fam.engine == "whisper.cpp") "whisper.cpp" else "ONNX",
                        fontSize = 10.sp)
                })
            }
            Text(fam.hfUrl.replace("https://", ""), fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (fam.quants.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fam.quants.forEach { q ->
                        FilterChip(
                            selected = sel?.id == q.id,
                            onClick = { sel = q },
                            label = { Text(q.quant + " · " + fmtSize(q.sizeBytes), fontSize = 11.sp) },
                        )
                    }
                }
            } else fam.note?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            sel?.let { m ->
                QuantRow(vm, m, activeId == m.id, refresh)
            }
        }
    }
}

@Composable
private fun QuantRow(vm: ModelViewModel, m: WhisperModel, selected: Boolean, refresh: Int) {
    val present = vm.installed(m)
    val isDownloading = vm.downloading[m.id] == true
    val pct = vm.progress[m.id] ?: 0

    if (isDownloading) {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
        Text("$pct% — возобновляется после обрыва", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    vm.errors[m.id]?.let { err ->
        Spacer(Modifier.height(6.dp))
        Text(err, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!present && !isDownloading) {
            Button(onClick = { vm.startDownload(m) }) { Text("Скачать ${fmtSize(m.sizeBytes)}") }
        }
        if (present) {
            if (selected) {
                AssistChip(onClick = {}, label = { Text("выбрана", fontSize = 11.sp) })
            } else {
                Button(onClick = { vm.useModel(m) }) { Text("Использовать") }
            }
            OutlinedIconButton(onClick = { vm.deleteModel(m) }) {
                Icon(Icons.Filled.Delete, "Удалить")
            }
        }
        Text(m.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun importModelFromUri(
    context: android.content.Context,
    uri: android.net.Uri,
    onDone: () -> Unit,
) {
    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
        try {
            val dir = App.instance.downloader.modelsDir()
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "custom_model.bin"
            val target = File(dir, name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            App.instance.settings.whisperModelFile = target.absolutePath
            App.instance.settings.asrKind = ModelRegistry.KIND_WHISPER
            App.instance.settings.activeModelId = "custom"
            withContext(Dispatchers.Main) { onDone() }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context,
                    "Импорт модели: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
