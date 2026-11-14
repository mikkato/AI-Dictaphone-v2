# Smart Rec

Диктофон с полностью локальной транскрибацией и суммаризацией на устройстве
(Android 15+, arm64-v8a).

- Распознавание: whisper.cpp (JNI) / sherpa-onnx (AAR) — модели GGUF/CTC на устройстве
- Суммаризация: локальная LLM через llama.cpp (JNI) или облачный OpenAI-совместимый API
- Kotlin + Jetpack Compose (Material 3, Material You), Room, WorkManager
- ID приложения: `ru.mytolm.aidictaphone` (не менять: ключ БД, пути, deep-link)

Ранее проект назывался «AI-Диктофон» (AI-Dictaphone v2); с версии 3.0.0 — **Smart Rec**.
Название пакета и git-удалённый репозиторий от переименования не зависят.

## Сборка

Требования: JDK 21, Android SDK (compileSdk 36), NDK, CMake 3.22+.

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew :app:assembleDebug
```

Первая сборка компилирует нативные whisper.cpp и llama.cpp (20–40 минут),
дальше — инкрементально.

## Большие файлы и git-lfs (план)

В репозитории есть крупные бинарные артефакты (модели, AAR, нативные библиотеки).
План — перевести их на git-lfs по маскам из `.gitattributes` (`*.bin`, `*.gguf`,
`*.onnx`, `*.aar`, `*.so`, …). В текущем прогоне это **сознательно отложено**:
переписывание истории (`git lfs migrate`) меняет все коммиты и ломает существующие
клоны/CI, а удаление файлов из working tree нельзя делать, пока их читает код.
Для новых крупных файлов — установить git-lfs до первого `git add`
(`git lfs install`), иначе `filter=lfs` из `.gitattributes` не применится.
