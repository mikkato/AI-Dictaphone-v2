package ru.mytolm.aidictaphone.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.mytolm.aidictaphone.data.App
import ru.mytolm.aidictaphone.voice.ModelManager

/**
 * Плавающий информер: текущий режим (облако/локальная суммаризация, какая модель
 * резидентна) + занято/свободно RAM. Обновляется раз в секунду, виден на всех экранах.
 */
@Composable
fun MemInformer(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var mem by remember { mutableStateOf(ModelManager.mem(context)) }
    var resident by remember { mutableStateOf(ModelManager.resident) }
    var residentName by remember { mutableStateOf(ModelManager.residentName) }
    var mode by remember { mutableStateOf(App.instance.settings.summarizeMode) }
    // свёрнутый информер — кружок с индикатором; раскрытие по тапу (низ экрана не перекрывает)
    var collapsed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            // ActivityManager.getMemoryInfo — IPC, не вешаем на него UI-поток
            mem = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ModelManager.mem(context)
            }
            resident = ModelManager.resident
            residentName = ModelManager.residentName
            mode = App.instance.settings.summarizeMode
            delay(1000)
        }
    }

    val stateText = when (resident) {
        ModelManager.Resident.ASR -> "ASR · $residentName"
        ModelManager.Resident.LLM -> "LLM · $residentName"
        ModelManager.Resident.NONE -> "Ожидание"
    }
    val modeText = if (mode == "local") "лок." else "облако"
    val dotColor by animateColorAsState(
        when (resident) {
            ModelManager.Resident.ASR -> androidx.compose.ui.graphics.Color(0xFF3E9B6E)
            ModelManager.Resident.LLM -> androidx.compose.ui.graphics.Color(0xFF8E6BBF)
            ModelManager.Resident.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
        }, label = "dot")

    if (collapsed) {
        // Свёрнуто: маленький кружок-индикатор состояния. Тап — развернуть.
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            modifier = modifier
                .size(40.dp)
                .clickable { collapsed = false },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("●", color = dotColor, fontSize = 14.sp)
            }
        }
    } else {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            modifier = modifier,
        ) {
            Row(
                Modifier.padding(start = 14.dp, top = 7.dp, end = 4.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("●", color = dotColor, fontSize = 12.sp)
                Text(stateText, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1)
                Text("|", color = MaterialTheme.colorScheme.outline, fontSize = 11.sp)
                Text(modeText, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text("|", color = MaterialTheme.colorScheme.outline, fontSize = 11.sp)
                Text(
                    "RAM %d/%d · своб %d ГБ".format(
                        mem.usedMb / 1024, mem.totalMb / 1024, mem.availMb / 1024),
                    fontSize = 11.sp,
                    color = if (mem.availMb < 1024) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1)
                // стрелка вправо — свернуть до иконки
                Box(
                    Modifier.size(30.dp).clip(CircleShape)
                        .clickable { collapsed = true },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Свернуть",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
