#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define TAG "JasSI-whisper"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::string sanitize_utf8(const std::string &s) {
    std::string out;
    size_t i = 0, n = s.size();
    while (i < n) {
        unsigned char c = (unsigned char) s[i];
        size_t len = c < 0x80 ? 1 : (c >> 5) == 6 ? 2 : (c >> 4) == 14 ? 3 : (c >> 3) == 30 ? 4 : 0;
        if (len == 0 || i + len > n) { i++; continue; }
        bool ok = true;
        for (size_t k = 1; k < len; k++) if (((unsigned char) s[i + k] >> 6) != 2) { ok = false; break; }
        if (ok) { out.append(s, i, len); i += len; } else { i++; }
    }
    return out;
}

extern "C" JNIEXPORT jlong JNICALL
Java_pl_jassi_whisper_Whisper_load(JNIEnv *env, jobject, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!ctx) LOGE("nie udalo sie zaladowac whisper");
    return (jlong) ctx;
}

extern "C" JNIEXPORT jstring JNICALL
Java_pl_jassi_whisper_Whisper_transcribe(JNIEnv *env, jobject, jlong handle, jfloatArray jdata, jstring jlang, jint threads, jstring jprompt) {
    auto *ctx = (whisper_context *) handle;
    if (!ctx) return env->NewStringUTF("");
    jsize n = env->GetArrayLength(jdata);
    if (n < 1600) return env->NewStringUTF("");
    const char *lang = env->GetStringUTFChars(jlang, nullptr);
    std::string langs(lang);
    env->ReleaseStringUTFChars(jlang, lang);
    const char *pr = jprompt ? env->GetStringUTFChars(jprompt, nullptr) : nullptr;
    std::string prompts(pr ? pr : "");
    if (pr) env->ReleaseStringUTFChars(jprompt, pr);

    jfloat *data = env->GetFloatArrayElements(jdata, nullptr);
    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.language = langs.c_str();
    wp.initial_prompt = prompts.empty() ? nullptr : prompts.c_str();
    wp.translate = false;
    wp.n_threads = threads;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    wp.no_context = true;
    wp.single_segment = true;
    wp.no_timestamps = true;

    int rc = whisper_full(ctx, wp, data, n);
    env->ReleaseFloatArrayElements(jdata, data, JNI_ABORT);
    if (rc != 0) { LOGE("whisper_full fail %d", rc); return env->NewStringUTF(""); }

    std::string text;
    int segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < segs; i++) text += whisper_full_get_segment_text(ctx, i);
    return env->NewStringUTF(sanitize_utf8(text).c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_pl_jassi_whisper_Whisper_free(JNIEnv *, jobject, jlong handle) {
    if (handle) whisper_free((whisper_context *) handle);
}
