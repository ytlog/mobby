#include <jni.h>
#include <llama.h>
#include <algorithm>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void fail(JNIEnv *env, const char *message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(cls, message);
}
std::string get(JNIEnv *env, jstring value) {
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}
size_t complete_utf8(const std::string &s) {
    size_t i = 0, last = 0;
    while (i < s.size()) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        size_t n = c < 0x80 ? 1 : (c & 0xe0) == 0xc0 ? 2 : (c & 0xf0) == 0xe0 ? 3 : (c & 0xf8) == 0xf0 ? 4 : 1;
        if (i + n > s.size()) break;
        i += n;
        last = i;
    }
    return last;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_github_ytlog_mobby_android_localmodel_llama_LlamaNative_load(JNIEnv *env, jclass, jstring path) {
    static bool initialized = false;
    if (!initialized) { llama_backend_init(); initialized = true; }
    const std::string file = get(env, path);
    auto params = llama_model_default_params();
    params.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(file.c_str(), params);
    if (!model) { fail(env, "llama.cpp could not load GGUF"); return 0; }
    return reinterpret_cast<jlong>(model);
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_ytlog_mobby_android_localmodel_llama_LlamaNative_unload(JNIEnv *, jclass, jlong handle) {
    if (handle) llama_model_free(reinterpret_cast<llama_model *>(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_github_ytlog_mobby_android_localmodel_llama_LlamaNative_generate(
    JNIEnv *env, jclass, jlong handle, jobjectArray roles, jobjectArray contents, jint max_tokens, jobject sink) {
    auto *model = reinterpret_cast<llama_model *>(handle);
    if (!model || max_tokens < 1 || max_tokens > 1024 || !roles || !contents ||
        env->GetArrayLength(roles) != env->GetArrayLength(contents)) {
        fail(env, "Invalid generation arguments"); return -1;
    }
    const jsize count = env->GetArrayLength(roles);
    if (count < 1 || count > 64) { fail(env, "Invalid message count"); return -1; }
    std::vector<std::string> role_strings, content_strings;
    role_strings.reserve(count); content_strings.reserve(count);
    for (jsize i = 0; i < count; ++i) {
        auto r = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto c = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        role_strings.push_back(get(env, r)); content_strings.push_back(get(env, c));
        env->DeleteLocalRef(r); env->DeleteLocalRef(c);
    }
    std::vector<llama_chat_message> messages;
    for (jsize i = 0; i < count; ++i) messages.push_back({role_strings[i].c_str(), content_strings[i].c_str()});
    const char *tmpl = llama_model_chat_template(model, nullptr);
    if (!tmpl) { fail(env, "GGUF has no chat template"); return -1; }
    int32_t prompt_len = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, nullptr, 0);
    if (prompt_len <= 0 || prompt_len > 128 * 1024) { fail(env, "Unsupported or oversized chat template"); return -1; }
    std::string prompt(prompt_len + 1, '\0');
    prompt_len = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, prompt.data(), prompt.size());
    if (prompt_len <= 0) { fail(env, "Chat template failed"); return -1; }
    prompt.resize(prompt_len);
    const auto *vocab = llama_model_get_vocab(model);
    const int32_t needed = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
    if (needed <= 0 || needed > 4096 - max_tokens) { fail(env, "Prompt exceeds context budget"); return -1; }
    std::vector<llama_token> tokens(needed);
    if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), tokens.size(), true, true) < 0) {
        fail(env, "Tokenization failed"); return -1;
    }
    jclass sink_class = env->GetObjectClass(sink);
    jmethodID on_start = env->GetMethodID(sink_class, "onStart", "(I)Z");
    jmethodID on_token = env->GetMethodID(sink_class, "onToken", "(Ljava/lang/String;)Z");
    if (!on_start || !on_token) return -1;
    if (!env->CallBooleanMethod(sink, on_start, needed) || env->ExceptionCheck()) return -2;
    auto cp = llama_context_default_params();
    cp.n_ctx = 4096; cp.n_batch = 512; cp.n_ubatch = 512;
    std::unique_ptr<llama_context, decltype(&llama_free)> ctx(llama_init_from_model(model, cp), llama_free);
    if (!ctx) { fail(env, "Could not create llama context"); return -1; }
    auto sp = llama_sampler_chain_default_params();
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(sp), llama_sampler_free);
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
    for (size_t pos = 0; pos < tokens.size(); pos += 512) {
        auto n = static_cast<int32_t>(std::min<size_t>(512, tokens.size() - pos));
        auto batch = llama_batch_get_one(tokens.data() + pos, n);
        if (llama_decode(ctx.get(), batch) != 0) { fail(env, "Prompt decode failed"); return -1; }
    }
    std::string pending;
    int generated = 0;
    for (; generated < max_tokens; ++generated) {
        llama_token token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        char piece[256];
        int n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
        if (n < 0) { fail(env, "Token piece too large"); return -1; }
        pending.append(piece, n);
        size_t ready = complete_utf8(pending);
        if (ready) {
            std::string text = pending.substr(0, ready);
            pending.erase(0, ready);
            jstring chunk = env->NewStringUTF(text.c_str());
            bool keep = env->CallBooleanMethod(sink, on_token, chunk);
            env->DeleteLocalRef(chunk);
            if (env->ExceptionCheck() || !keep) return -2;
        }
        auto batch = llama_batch_get_one(&token, 1);
        if (llama_decode(ctx.get(), batch) != 0) { fail(env, "Generation decode failed"); return -1; }
    }
    return generated;
}
