// JNI bridge: Kotlin <-> whisper.cpp (vendored, MIT).
// Runtime CPU-feature dispatch is handled by ggml backend variants
// (libggml-cpu-<arch>.so loaded via dlopen after a feature score check),
// which is the mitigation for the Tensor dotprod/i8mm illegal-instruction pitfall.
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstdlib>
#include <cmath>
#include <string>
#include <vector>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM *g_jvm = nullptr;
static jobject g_callback = nullptr;

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

// Enumerate ggml CPU backend variants in the app native lib dir and dlopen
// the best one supported by this SoC (feature-scored). Must be called before
// whisper_load; safe to call repeatedly.
JNIEXPORT jboolean JNICALL
Java_ru_mytolm_aidictaphone_voice_WhisperNative_initBackendNative(JNIEnv *env, jclass, jstring lib_dir) {
    const char *dir = env->GetStringUTFChars(lib_dir, nullptr);
    std::string d(dir);
    env->ReleaseStringUTFChars(lib_dir, dir);
    // ggml's dynamic loader
    void *gg = dlopen("libggml.so", RTLD_NOW | RTLD_GLOBAL);
    if (!gg) {
        LOGE("dlopen libggml failed: %s", dlerror());
        return JNI_FALSE;
    }
    typedef void (*load_all_fn)(const char *);
    auto load_all = (load_all_fn) dlsym(gg, "ggml_backend_load_all_from_path");
    if (load_all) {
        load_all(d.c_str());
        LOGI("ggml backends loaded from %s", d.c_str());
        // диагностика реестра
        auto dev_count = (size_t (*)()) dlsym(gg, "ggml_backend_dev_count");
        auto dev_by_type = (void *(*)(int)) dlsym(gg, "ggml_backend_dev_by_type");
        auto reg_count = (size_t (*)()) dlsym(gg, "ggml_backend_reg_count");
        size_t ndev = dev_count ? dev_count() : 0;
        void *cpu = dev_by_type ? dev_by_type(0 /*CPU*/) : nullptr;
        LOGI("registry: devs=%zu cpu_dev=%p regs=%zu", ndev, cpu,
             reg_count ? reg_count() : 0);
        if (!cpu) {
            // точная причина: raw dlopen + dlerror
            std::string p = d + "/libggml-cpu-android_armv8.0_1.so";
            void *raw = dlopen(p.c_str(), RTLD_NOW | RTLD_LOCAL);
            if (!raw) {
                LOGE("raw dlopen failed: %s", dlerror());
            } else {
                auto score = (int (*)()) dlsym(raw, "ggml_backend_score");
                auto entry = (void (*)(int, void *)) dlsym(raw, "ggml_backend_reg_entry");
                LOGI("raw dlopen ok: score=%d entry=%p", score ? score() : -999, (void*)entry);
            }
            return JNI_FALSE;
        }
        return JNI_TRUE;
    }
    LOGE("ggml_backend_load_all_from_path not found");
    return JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_ru_mytolm_aidictaphone_voice_WhisperNative_loadModelNative(JNIEnv *env, jclass, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    if (!ctx) {
        LOGE("whisper_init_from_file failed");
        return 0;
    }
    return (jlong) (uintptr_t) ctx;
}

JNIEXPORT void JNICALL
Java_ru_mytolm_aidictaphone_voice_WhisperNative_freeModelNative(JNIEnv *, jclass, jlong handle) {
    if (handle) whisper_free((whisper_context *) (uintptr_t) handle);
}

JNIEXPORT jboolean JNICALL
Java_ru_mytolm_aidictaphone_voice_WhisperNative_selfTestNative(JNIEnv *env, jclass, jlong handle) {
    if (!handle) return JNI_FALSE;
    // 1 second of synthetic 220 Hz tone at 16 kHz — exercises the full
    // ggml inference graph (the exact code path that SIGILLs on Tensor
    // with bad -march flags). Output text is irrelevant; only that it
    // does not crash matters.
    const int N = 16000;
    static float pcm[16000];
    for (int i = 0; i < N; i++) {
        pcm[i] = 0.2f * sinf(2.0f * 3.14159265f * 220.0f * i / 16000.0f);
    }
    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_special = false;
    wp.print_timestamps = false;
    wp.n_threads = 2;
    wp.language = "en";
    int rc = whisper_full((whisper_context *) (uintptr_t) handle, wp, pcm, N);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

// Transcribe one PCM chunk (mono float32 16 kHz).
// Emits callback segment(startMs, endMs, text) for every completed segment.
// Returns 0 on success, non-zero error code otherwise.
JNIEXPORT jint JNICALL
Java_ru_mytolm_aidictaphone_voice_WhisperNative_transcribeChunkNative(
        JNIEnv *env, jclass, jlong handle, jfloatArray pcmArr,
        jlong start_offset_ms, jstring language, jint threads,
        jstring initial_prompt, jobject callback) {
    if (!handle) return -1;
    auto *ctx = (whisper_context *) (uintptr_t) handle;

    jsize n = env->GetArrayLength(pcmArr);
    jfloat *pcm = env->GetFloatArrayElements(pcmArr, nullptr);
    if (!pcm) return -2;

    g_callback = callback ? env->NewGlobalRef(callback) : nullptr;

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_special = false;
    wp.print_timestamps = false;
    wp.translate = false;
    wp.no_context = true;
    wp.token_timestamps = false;
    wp.n_threads = threads > 0 ? threads : 4;

    const char *lang = env->GetStringUTFChars(language, nullptr);
    wp.language = (lang && lang[0]) ? lang : "auto";

    std::string prompt;
    if (initial_prompt) {
        const char *ip = env->GetStringUTFChars(initial_prompt, nullptr);
        if (ip && ip[0]) prompt = ip;
        if (ip) env->ReleaseStringUTFChars(initial_prompt, ip);
        wp.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();
    }

    int rc = whisper_full(ctx, wp, pcm, (int) n);

    if (rc == 0 && g_callback) {
        JNIEnv *cenv = nullptr;
        bool attached = false;
        int st = g_jvm->GetEnv((void **) &cenv, JNI_VERSION_1_6);
        if (st == JNI_EDETACHED) {
            g_jvm->AttachCurrentThread(&cenv, nullptr);
            attached = true;
        }
        if (cenv) {
            jclass cls = cenv->GetObjectClass(g_callback);
            jmethodID segMid = cenv->GetMethodID(cls, "onSegment",
                                                 "(JLjava/lang/String;)V");
            int cnt = whisper_full_n_segments(ctx);
            for (int i = 0; i < cnt; i++) {
                int64_t t0 = whisper_full_get_segment_t0(ctx, i); // csec
                int64_t t1 = whisper_full_get_segment_t1(ctx, i);
                const char *txt = whisper_full_get_segment_text(ctx, i);
                if (!txt) continue;
                // clamp to chunk bounds; segments at chunk start (t0==0) belong
                // to the overlap region and are merged/deduped on the Kotlin side
                int64_t ms0 = start_offset_ms + t0 * 10;
                int64_t ms1 = start_offset_ms + t1 * 10;
                jstring jtxt = cenv->NewStringUTF(txt);
                if (segMid) cenv->CallVoidMethod(g_callback, segMid, (jlong) ms0, jtxt);
                if (cenv->ExceptionCheck()) cenv->ExceptionClear();
                cenv->DeleteLocalRef(jtxt);
                (void) ms1;
            }
            cenv->DeleteLocalRef(cls);
        }
        if (attached) g_jvm->DetachCurrentThread();
    }

    if (g_callback) {
        env->DeleteGlobalRef(g_callback);
        g_callback = nullptr;
    }
    env->ReleaseFloatArrayElements(pcmArr, pcm, JNI_ABORT);
    if (lang) env->ReleaseStringUTFChars(language, lang);
    return rc;
}

} // extern "C"
