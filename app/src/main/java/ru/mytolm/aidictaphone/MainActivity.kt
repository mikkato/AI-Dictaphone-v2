package ru.mytolm.aidictaphone

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.mytolm.aidictaphone.data.App
import ru.mytolm.aidictaphone.ui.DetailScreen
import ru.mytolm.aidictaphone.ui.LibraryScreen
import ru.mytolm.aidictaphone.ui.MemInformer
import ru.mytolm.aidictaphone.ui.ModelScreen
import ru.mytolm.aidictaphone.ui.RecordingScreen
import ru.mytolm.aidictaphone.ui.SettingsScreen
import ru.mytolm.aidictaphone.voice.RecordingService

// Палитра Plaud: светлый off-white фон, белые карточки, чёрный контент-CTA,
// зелёный индикатор статуса, монохром без лишних акцентов.
private val PlaudLight = lightColorScheme(
    primary = Color(0xFF1C1B1F),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF3E6B5A),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F5F4),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFECECEC),
    onSurfaceVariant = Color(0xFF6E6E73),
    outline = Color(0xFFD9D9DC),
    error = Color(0xFFB3261E),
)

private val PlaudDark = darkColorScheme(
    primary = Color(0xFFE4E2E8),
    onPrimary = Color(0xFF1C1B1F),
    secondary = Color(0xFF7FBFA6),
    onSecondary = Color(0xFF0E241B),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1E1E20),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF2A2A2E),
    onSurfaceVariant = Color(0xFFABAAB0),
    outline = Color(0xFF3F3F44),
    error = Color(0xFFF2B8B5),
)

@Composable
fun DictaphoneTheme(content: @Composable () -> Unit) {
    // "system" | "light" | "dark" (Настройки → Оформление)
    val mode by App.instance.settings.theme.collectAsState(initial = "system")
    val dynamic by App.instance.settings.dynamicTheme.collectAsState(initial = true)
    val dark = when (mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    // иконки/текст статус-бара — контрастные к выбранной теме
    val view = LocalView.current
    val context = LocalContext.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as android.app.Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            window.statusBarColor = android.graphics.Color.TRANSPARENT
        }
    }

    // Material You (аудит UX-1): раскраска из обоев, тумблер в настройках;
    // dynamicColorScheme кидает исключение без Material You — откат на бренд-палитру
    val brand = if (dark) PlaudDark else PlaudLight
    val scheme = if (dynamic && !view.isInEditMode) {
        try {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } catch (_: Exception) { brand }
    } else brand

    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // edge-to-edge (аудит UX-1): на minSdk 35 включено системой по умолчанию,
        // вызов делает поведение явным и одинаковым; отступы дают Scaffold/insets
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        notifPermission.launch(perms.toTypedArray())

        // deep-link из уведомления: открыть конкретную заметку
        val openNoteId = intent.getLongExtra("noteId", -1L)

        setContent {
            DictaphoneTheme {
                val nav = rememberNavController()
                // Наличие хотя бы одной скачанной модели определяет стартовый экран.
                // remember: проверка на диске выполняется один раз, а не на каждой рекомпозиции.
                val needsModel = remember {
                    App.instance.downloader.modelsDir().listFiles { f ->
                        f.isFile && f.extension == "bin" && !f.name.endsWith(".part")
                    }.isNullOrEmpty()
                }
                Box(Modifier.fillMaxSize()) {
                    NavHost(
                        navController = nav,
                        startDestination = when {
                            openNoteId > 0 -> "detail/$openNoteId"
                            needsModel -> "models"
                            else -> "library"
                        },
                    ) {
                        composable("library") {
                            LibraryScreen(
                                onOpen = { nav.navigate("detail/$it") },
                                onRecord = { nav.navigate("recording") },
                                onSettings = { nav.navigate("settings") },
                                onModels = { nav.navigate("models") },
                            )
                        }
                        composable(
                            "detail/{noteId}",
                            arguments = listOf(navArgument("noteId") { type = NavType.LongType })
                        ) { entry ->
                            DetailScreen(
                                noteId = entry.arguments?.getLong("noteId") ?: 0,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable("recording") {
                            RecordingScreen(onDone = {
                                RecordingService.stop(this@MainActivity)
                                nav.navigate("library") { popUpTo("library") { inclusive = true } }
                            })
                        }
                        composable("settings") {
                            SettingsScreen(
                                onBack = { nav.popBackStack() },
                                onModels = { nav.navigate("models") }
                            )
                        }
                        composable("models") {
                            ModelScreen(onBack = {
                                if (nav.previousBackStackEntry != null) nav.popBackStack()
                                else nav.navigate("library")
                            })
                        }
                    }
                    MemInformer(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 10.dp)
                    )
                }
            }
        }
    }
}
