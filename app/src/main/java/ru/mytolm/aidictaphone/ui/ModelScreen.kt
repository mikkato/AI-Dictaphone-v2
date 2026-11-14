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
import ru.mytolm.aidictaphone.voice.DlState
import ru.mytolm.aidictaphone.voice.ModelDownloadWorker
import ru.mytolm.aidictaphone.voice.ModelDownloads
import java.io.File

/**
 * Скачивание — только очередь WorkManager (ModelDownloadWorker): экран может
 * закрыться, загрузка продолжится (аудит F3). VM наблюдает статус из
 * [ModelDownloads], optimistically помечая «нажали Скачать» до старта воркера.
 */
class ModelViewModel(app: Application) : AndroidViewModel(app) {
    val dlState = ModelDownloads.state(app)
    private val tapped = mutableStateMapOf<String, Boolean>()
    val localErrors = mutableStateMapOf<String, String>()
    var customUrl by mutableStateOf(App.instance.settings.customModelUrl)

    fun installed(model: WhisperModel): Boolean =
        App.instance.downloader.installed(model)

    fun busy(id: String, installed: Boolean, st: DlState?): Boolean =
        (st?.running == true) || (tapped[id] == true && !installed && st?.error == null)

    fun startDownload(model: WhisperModel) {
        if (tapped[model.id] == true) return
        tapped[model.id] = true
        localErrors.remove(model.id)
        ModelDownloadWorker.enqueue(getApplication(), model.id, isLlm = false,
            customUrl.takeIf { it.isNotBlank() })
    }

    fun cancelDownload(model: WhisperModel) {
        tapped.remove(model.id)
        ModelDownloadWorker.cancel(getApplication(), model.id)
    }

    fun onDone(id: String) { tapped.remove(id) }

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

    /** Импорт своей модели из SAF: viewModelScope + Dispatchers.IO (аудит F16 — без «ничейной» корутины). */
    fun importFromUri(context: android.content.Context, uri: android.net.Uri, onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
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
    val dl by vm.dlState.collectAsState()

    val safPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(
            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        vm.importFromUri(context, uri, onDone = { refresh++ })
    }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                }
            },
            title = { Text("Модели распознавания") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background))
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
            .padding(20.dp)) {

            Text("Модели скачиваются один раз и хранятся в папке приложения — аудио никуда " +
                 "не отправляется. Выберите семейство и квант (размер/точность). Загрузка " +
                 "продолжается в фоне, можно закрыть экран.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            ModelRegistry.FAMILIES.forEach { fam ->
                FamilyCard(vm, fam, activeId, refresh, dl)
            }

            Spacer(Modifier.height(8.dp))
            OutlinedCard(Modifier.fillMaxWidth().clickable {
                safPicker.launch(arrayOf("*/*"))
            }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.FolderOpen, "Импорт модели из файла")
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
    dl: Map<String, DlState>,
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
                QuantRow(vm, m, activeId == m.id, refresh, dl[m.id])
            }
        }
    }
}

@Composable
private fun QuantRow(
    vm: ModelViewModel,
    m: WhisperModel,
    selected: Boolean,
    refresh: Int,
    st: DlState?,
) {
    val present = vm.installed(m)
    if (present) vm.onDone(m.id)
    val isDownloading = vm.busy(m.id, present, st)
    val pct = st?.percent ?: 0
    val err = vm.localErrors[m.id] ?: st?.error

    if (isDownloading) {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
        Text("$pct% — возобновляется после обрыва", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!isDownloading && err != null) {
        Spacer(Modifier.height(6.dp))
        Text(err, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!present && isDownloading) {
            OutlinedButton(onClick = { vm.cancelDownload(m) }) { Text("Отменить") }
        }
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
                Icon(Icons.Filled.Delete, "Удалить модель")
            }
        }
        Text(m.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
