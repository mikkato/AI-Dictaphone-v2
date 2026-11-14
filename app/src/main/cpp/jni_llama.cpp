// JNI-мост к llama.cpp (статический ggml-CPU). Экспортирует только Java_* символы.
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <string>
#include <vector>
#include <android/log.h>

#include "llama.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LlamaJNI", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "LlamaJNI", __VA_ARGS__)

struct LlamaHandle { llama_model *m; llama_context *c; };

// Отмена генерации (аудит U10/F6): UI дёргает requestCancel, чат-циклы
// (prefill и генерация) проверяют флаг и выходят с частичным результатом.
static std::atomic<bool> g_cancel{false};

extern "C"
JNIEXPORT void JNICALL
Java_ru_mytolm_aidictaphone_voice_LlamaEngine_requestCancelNative(JNIEnv *, jclass) {
    g_cancel.store(true);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_ru_mytolm_aidictaphone_voice_LlamaEngine_loadNative(JNIEnv *env, jclass, jstring path_,
                                                         jint threads, jint ctx_len) {
    const char *path = env->GetStringUTFChars(path_, nullptr);
    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;                              // чистый CPU
    llama_model *model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(path_, path);
    if (!model) { LOGE("model load failed"); llama_backend_free(); return 0; }

    llama_context_params cp = llama_context_default_params();
    // Контекст = min(тренировочный контекст модели из GGUF-метаданных, запрошенный максимум).
    // Учитываем реальное контекстное окно модели, а не жёсткие 4096.
    uint32_t trained = (uint32_t) llama_model_n_ctx_train(model);
    uint32_t n_ctx = trained;
    if (ctx_len > 0 && n_ctx > (uint32_t) ctx_len) n_ctx = (uint32_t) ctx_len;
    cp.n_ctx = n_ctx;
    cp.n_batch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.n_seq_max = 1;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { LOGE("ctx init failed"); llama_model_free(model); llama_backend_free(); return 0; }

    LOGI("loaded n_ctx=%u (trained=%u) threads=%d", cp.n_ctx, trained, (int) threads);
    return (jlong) (uintptr_t) new LlamaHandle{model, ctx};
}

extern "C"
JNIEXPORT jstring JNICALL
Java_ru_mytolm_aidictaphone_voice_LlamaEngine_chatNative(JNIEnv *env, jclass, jlong handle,
                                                         jstring system_, jstring user_,
                                                         jfloat max_tokens_f, jfloat temperature,
                                                         jobject piece_cb) {
    auto t0 = std::chrono::steady_clock::now();
    jmethodID cb_mid = nullptr;
    if (piece_cb) {
        jclass cb_cls = env->GetObjectClass(piece_cb);
        cb_mid = env->GetMethodID(cb_cls, "onPiece", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cb_cls);
    }
    auto *h = (LlamaHandle *) (uintptr_t) handle;
    const int max_tokens = (int) max_tokens_f;
    const llama_vocab *vocab = llama_model_get_vocab(h->m);
    g_cancel.store(false);   // новый чат — сбросить флаг отмены

    const char *sys = env->GetStringUTFChars(system_, nullptr);
    const char *usr = env->GetStringUTFChars(user_, nullptr);

    // чат-шаблон: берём Jinja из GGUF-метаданных (Qwen3 → ChatML-семейство),
    // фолбэк — встроенное имя "chatml"
    std::vector<llama_chat_message> msgs = {{"system", sys}, {"user", usr}};
    std::string tmpl;
    int32_t tneed = llama_model_meta_val_str(h->m, "tokenizer.chat_template", nullptr, 0);
    if (tneed > 0) {
        std::vector<char> tbuf(tneed);
        int32_t t2 = llama_model_meta_val_str(h->m, "tokenizer.chat_template",
                                              tbuf.data(), tbuf.size());
        if (t2 > 0) tmpl.assign(tbuf.data(), t2);
    }
    if (tmpl.empty()) tmpl = "chatml";
    std::string prompt;
    int32_t need = llama_chat_apply_template(tmpl.c_str(), msgs.data(), msgs.size(), true,
                                             nullptr, 0);
    if (need > 0) {
        std::vector<char> buf(need);
        int32_t n2 = llama_chat_apply_template(tmpl.c_str(), msgs.data(), msgs.size(), true,
                                               buf.data(), (int32_t) buf.size());
        if (n2 > 0) prompt.assign(buf.data(), n2);
    }
    if (prompt.empty()) prompt = std::string("System: ") + sys + "\nUser: " + usr + "\nAssistant:";
    env->ReleaseStringUTFChars(system_, sys);
    env->ReleaseStringUTFChars(user_, usr);

    int n = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                           nullptr, 0, false, true);
    if (n < 0) n = -n;                    // вернули нужный размер со знаком минус
    if (n == 0) return env->NewStringUTF("");
    std::vector<llama_token> toks(n);
    llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                   toks.data(), n, false, true);
    // Резерв под генерацию: промпт не должен занимать весь контекст.
    uint32_t n_ctx = llama_n_ctx(h->c);
    int room = (int) n_ctx - max_tokens - 16;
    if (room < 1) room = 1;
    if (n > room) { toks.resize(room); n = room; LOGI("prompt truncated to %d tokens", room); }
    LOGI("prompt tokens=%d chars=%zu", n, prompt.size());

    llama_memory_seq_rm(llama_get_memory(h->c), 0, -1, -1);

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // prefill окнами по n_batch
    size_t i = 0;
    while (i < toks.size()) {
        if (g_cancel.load()) {
            LOGI("cancelled during prefill");
            llama_sampler_free(smpl);
            return env->NewStringUTF("");
        }
        int take = (int) std::min<size_t>(toks.size() - i, 512);
        llama_batch batch = llama_batch_get_one(&toks[i], take);
        if (llama_decode(h->c, batch) != 0) {
            LOGE("prefill decode failed at %zu", i);
            llama_sampler_free(smpl);
            return env->NewStringUTF("");
        }
        i += take;
    }

    auto tp = std::chrono::steady_clock::now();
    double prefill_ms = std::chrono::duration<double, std::milli>(tp - t0).count();

    std::string out, pending;
    char piece[256];
    int tgen = 0;
    llama_token tok = llama_sampler_sample(smpl, h->c, -1);
    llama_sampler_accept(smpl, tok);
    for (int generated = 0; generated < max_tokens; generated++) {
        if (llama_vocab_is_eog(vocab, tok)) break;
        if (g_cancel.load()) { LOGI("cancelled by request at tok %d", generated); break; }
        int plen = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, true);
        if (plen > 0) {
            out.append(piece, plen);
            pending.append(piece, plen);
        }
        // потоковая отдача кусками ~24 токена — UI печатает по ходу, не ждёт 1024
        if (cb_mid && ++tgen % 24 == 0 && !pending.empty()) {
            jstring js = env->NewStringUTF(pending.c_str());
            env->CallVoidMethod(piece_cb, cb_mid, js);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck()) { env->ExceptionClear(); cb_mid = nullptr; }
            pending.clear();
        }
        llama_batch one = llama_batch_get_one(&tok, 1);
        if (llama_decode(h->c, one) != 0) { LOGE("gen decode failed"); break; }
        tok = llama_sampler_sample(smpl, h->c, -1);
        llama_sampler_accept(smpl, tok);
    }
    if (cb_mid && !pending.empty()) {
        jstring js = env->NewStringUTF(pending.c_str());
        env->CallVoidMethod(piece_cb, cb_mid, js);
        env->DeleteLocalRef(js);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    auto t1 = std::chrono::steady_clock::now();
    double gen_ms = std::chrono::duration<double, std::milli>(t1 - tp).count();
    LOGI("prefill=%0.0fms/%dtok gen=%0.0fms/%dtok (%0.1f tok/s)",
         prefill_ms, n, gen_ms, tgen, gen_ms > 0 ? tgen * 1000.0 / gen_ms : 0.0);

    llama_sampler_free(smpl);
    return env->NewStringUTF(out.c_str());
}

extern "C"
JNIEXPORT jint JNICALL
Java_ru_mytolm_aidictaphone_voice_LlamaEngine_getContextLengthNative(JNIEnv *, jclass, jlong handle) {
    auto *h = (LlamaHandle *) (uintptr_t) handle;
    return h ? (jint) llama_n_ctx(h->c) : 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_ru_mytolm_aidictaphone_voice_LlamaEngine_unloadNative(JNIEnv *, jclass, jlong handle) {
    auto *h = (LlamaHandle *) (uintptr_t) handle;
    LOGI("unload handle=%p", (void *) h);
    if (h) {
        llama_free(h->c);
        llama_model_free(h->m);
        delete h;
    }
    llama_backend_free();
}
