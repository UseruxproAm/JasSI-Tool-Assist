#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <string>
#include <vector>
#include "llama.h"

#define TAG "JasSI-llama"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model *g_model = nullptr;

// Zostawia tylko poprawne sekwencje UTF-8 (NewStringUTF crashuje na śmieciach).
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

extern "C" JNIEXPORT jboolean JNICALL
Java_pl_jassi_llama_Llama_load(JNIEnv *env, jobject, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!g_model) LOGE("nie udalo sie zaladowac modelu");
    return g_model != nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_pl_jassi_llama_Llama_generate(JNIEnv *env, jobject, jstring jprompt, jint maxTokens, jint threads) {
    if (!g_model) return env->NewStringUTF("");
    const char *p = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(p);
    env->ReleaseStringUTFChars(jprompt, p);

    const llama_vocab *vocab = llama_model_get_vocab(g_model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 2048;
    cp.n_batch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    llama_context *ctx = llama_init_from_model(g_model, cp);
    if (!ctx) { LOGE("brak kontekstu"); return env->NewStringUTF(""); }

    int n = -llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> toks(n > 0 ? n : 0);
    if (n <= 0 || llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), toks.data(), (int) toks.size(), true, true) < 0
        || (int) toks.size() + maxTokens > (int) cp.n_ctx) {
        LOGE("tokenizacja / za dlugi prompt");
        llama_free(ctx);
        return env->NewStringUTF("");
    }

    // prefill
    for (size_t pos = 0; pos < toks.size(); pos += cp.n_batch) {
        int cnt = (int) std::min<size_t>(cp.n_batch, toks.size() - pos);
        llama_batch b = llama_batch_get_one(toks.data() + pos, cnt);
        if (llama_decode(ctx, b) != 0) { LOGE("decode prefill fail"); llama_free(ctx); return env->NewStringUTF(""); }
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string out;
    for (int i = 0; i < maxTokens; i++) {
        llama_token t = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, t)) break;
        char buf[256];
        int len = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, true);
        if (len > 0) out.append(buf, len);
        if (out.find("<end_of_turn>") != std::string::npos) break;
        llama_batch b = llama_batch_get_one(&t, 1);
        if (llama_decode(ctx, b) != 0) break;
    }

    llama_sampler_free(smpl);
    llama_free(ctx);

    size_t cut = out.find("<end_of_turn>");
    if (cut != std::string::npos) out.resize(cut);
    return env->NewStringUTF(sanitize_utf8(out).c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_pl_jassi_llama_Llama_release(JNIEnv *, jobject) {
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    llama_backend_free();
}
