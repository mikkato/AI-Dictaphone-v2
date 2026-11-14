# JNI-классы: нативный код находит их по точным именам (FindClass / регистратор
# JNI_OnLoad) и вызывает callback-методы по имени+сигнатуре.
-keep class ru.mytolm.aidictaphone.voice.WhisperNative { *; }
-keep interface ru.mytolm.aidictaphone.voice.WhisperNative$SegmentCallback { *; }
-keep class ru.mytolm.aidictaphone.voice.LlamaEngine { *; }
-keep interface ru.mytolm.aidictaphone.voice.LlamaEngine$PieceCallback { *; }

# sherpa-onnx: нативная библиотека AAR ищет классы com.k2fsa.sherpa.onnx по
# исходным именам (OfflineRecognizer/Config data-классы читаются нативной
# стороной через рефлексию).
-keep class com.k2fsa.sherpa.onnx.** { *; }

# kotlinx.serialization runtime: константы/Companion форматов используются
# через рефлексию из Json({})
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# Точка входа Application/Activity держат manifest-правила AGP; Room-код
# генерируется KSP и ссылается на сущности напрямую — extra keeps не нужны.

# release: вырезать отладочные логи целиком (v/d/i; warn/error остаются)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
