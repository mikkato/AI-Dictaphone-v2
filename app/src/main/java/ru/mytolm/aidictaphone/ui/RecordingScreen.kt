package ru.mytolm.aidictaphone.ui

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
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.mytolm.aidictaphone.voice.RecordingService

/**
 * Экран записи (ТЗ §4.2): live-метр уровня, таймер, пауза/продолжить,
 * кнопка Highlight и поле быстрой заметки с автосохранением по времени.
 */
@Composable
fun RecordingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var elapsed by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    var peak by remember { mutableFloatStateOf(0f) }   // пик-холд для VU-метра
    var paused by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(RecordingService.isRecording) }
    var gain by remember { mutableFloatStateOf(RecordingService.micGain) }
    var noteField by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<List<String>>(emptyList()) }

    // запись запускается только кнопкой — при входе на экран тишина
    LaunchedEffect(Unit) {
        while (true) {
            elapsed = RecordingService.elapsedMs
            val raw = RecordingService.level
            paused = RecordingService.isPaused
            recording = RecordingService.isRecording
            // про-аудио поведение: мгновенная атака, плавное затухание, медленный спад пик-маркера
            level = if (raw > level) raw else (level - 0.035f).coerceAtLeast(raw)
            peak = if (raw >= peak) raw else (peak - 0.004f).coerceAtLeast(level)
            delay(30)
        }
    }

    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {

            Spacer(Modifier.height(24.dp))
            // скрытый переключатель демо-волны: тап по заголовку (на хосте нет микрофона)
            Text("Запись микрофоном", fontSize = 20.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable {
                    RecordingService.simulate = !RecordingService.simulate
                })
            Spacer(Modifier.height(40.dp))

            // таймер + мигающий индикатор записи
            val s = elapsed / 1000
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val pulse = rememberInfiniteTransition(label = "rec")
                val blink by pulse.animateFloat(
                    initialValue = 1f, targetValue = 0.15f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600), repeatMode = RepeatMode.Reverse),
                    label = "blink")
                Box(Modifier.size(16.dp).clip(CircleShape)
                    .background(if (!recording) MaterialTheme.colorScheme.outline
                                else Color(0xFFD24B3E).copy(alpha = if (paused) 0.3f else blink)))
                Text(when {
                        !recording -> "готов к записи"
                        paused -> "REC (пауза)"
                        else -> "REC"
                    }, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = if (recording) Color(0xFFD24B3E)
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("%02d:%02d".format(s / 60, s % 60),
                    fontSize = 64.sp, fontWeight = FontWeight.Light)
            }

            Spacer(Modifier.height(32.dp))

            // VU-метр уровня: сегментная шкала + пик-маркер (как на профессиональных рекордерах)
            VUBar(level = if (paused || !recording) 0f else level,
                  peak = if (paused || !recording) 0f else peak)
            if (paused) Text("на паузе", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(40.dp))

            // чувствительность микрофона (гейн входа, применяется к PCM при записи)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Tune, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Чувствительность микрофона", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("×%.1f".format(gain), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = gain,
                onValueChange = { gain = it; RecordingService.micGain = it },
                valueRange = 0.2f..4f,
                steps = 18,
                modifier = Modifier.semantics {
                    contentDescription = "Чувствительность микрофона, ×%.1f".format(gain)
                },
            )

            Spacer(Modifier.height(8.dp))

            // управление
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                if (!recording) {
                    // ручной запуск
                    Box(
                        Modifier.size(96.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.inverseSurface)
                            .clickable {
                                RecordingService.start(context)
                                recording = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Mic, "Начать запись", tint =
                                MaterialTheme.colorScheme.inverseOnSurface,
                                modifier = Modifier.size(32.dp))
                            Text("Старт", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.inverseOnSurface)
                        }
                    }
                    OutlinedButton(onClick = onDone, modifier = Modifier.height(56.dp)) {
                        Text("Готово")
                    }
                }
                if (recording) OutlinedButton(onClick = {
                    if (paused) RecordingService.resume(context)
                    else RecordingService.pause(context)
                }, modifier = Modifier.height(56.dp)) {
                    Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (paused) "Продолжить" else "Пауза")
                }
                if (recording) Box(
                    Modifier.size(84.dp).clip(CircleShape)
                        .background(if (paused) MaterialTheme.colorScheme.surfaceVariant
                                    else MaterialTheme.colorScheme.error)
                        .clickable {
                            RecordingService.stop(context)
                            recording = false
                        },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Stop, "Стоп",
                        tint = if (paused) MaterialTheme.colorScheme.onSurfaceVariant
                               else Color.White,
                        modifier = Modifier.size(40.dp))
                }
                if (recording) FilledIconButton(onClick = {
                    RecordingService.highlight(context, noteField.trim()
                        .ifBlank { "момент %02d:%02d".format(s / 60, s % 60) })
                    noteField = ""
                }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.BookmarkAdd, "Highlight")
                }
            }

            Spacer(Modifier.height(24.dp))

            // быстрая заметка с автосохранением по времени
            OutlinedTextField(
                value = noteField,
                onValueChange = { noteField = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Быстрая заметка (сохранится с меткой времени)…") },
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (noteField.isNotBlank()) {
                        RecordingService.saveTextNote(context, noteField.trim())
                        pending = pending + noteField.trim()
                        noteField = ""
                    }
                }),
            )
            Spacer(Modifier.height(12.dp))
            if (pending.isNotEmpty()) {
                Text("Сохранённые заметки этой сессии", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(pending.reversed()) { t ->
                        Text("• $t", fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
                Text("Кнопка Highlight отмечает текущий момент; заметки и хайлайты " +
                     "попадут в контекст саммари.", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Сегментный VU-метр в стиле профессиональных рекордеров:
 * сегменты по уровню (зелёный→жёлтый→красный) + отдельный пик-маркер,
 * который медленно спускается, оставляя «след» максимального уровня.
 */
@Composable
private fun VUBar(level: Float, peak: Float) {
    val segments = 40
    val inactive = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val peakColor = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(34.dp)) {
        val gap = 3f
        val w = (size.width - gap * (segments - 1)) / segments
        val lit = (level.coerceIn(0f, 1f) * segments).toInt()
        val peakSeg = (peak.coerceIn(0f, 1f) * (segments - 1)).toInt()
        for (i in 0 until segments) {
            val x = i * (w + gap)
            val frac = (i + 1f) / segments
            val onColor = when {
                frac > 0.88f -> Color(0xFFC25B4E)
                frac > 0.68f -> Color(0xFFD9A03F)
                else -> Color(0xFF2E7D5B)
            }
            drawRoundRect(
                color = if (i < lit) onColor else inactive,
                topLeft = Offset(x, 0f),
                size = Size(w, size.height),
                cornerRadius = CornerRadius(2f, 2f),
            )
        }
        // пик-маркер: тонкая яркая метка, замирающая на максимуме
        if (peak > 0.01f) {
            val px = peakSeg * (w + gap)
            drawRoundRect(
                color = peakColor,
                topLeft = Offset(px, 0f),
                size = Size(w, size.height),
                cornerRadius = CornerRadius(2f, 2f),
            )
        }
    }
}
