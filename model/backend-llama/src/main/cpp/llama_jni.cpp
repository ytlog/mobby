#include <jni.h>
#include <android/log.h>
#include <llama.h>
#include <ggml-backend.h>
#include <chat.h>
#include <algorithm>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <sys/sysinfo.h>
#include <utility>
#include <vector>
#if MOBBY_VULKAN_PACKAGED
#include <vulkan/vulkan.h>
#endif

#ifndef MOBBY_VULKAN_PACKAGED
#define MOBBY_VULKAN_PACKAGED 0
#endif

namespace {
enum class VulkanSupport { unavailable, driver_too_old, supported };
VulkanSupport vulkan_support() {
#if MOBBY_VULKAN_PACKAGED
    VkInstanceCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&info, nullptr, &instance) != VK_SUCCESS) return VulkanSupport::unavailable;
    uint32_t count = 0;
    VulkanSupport result = VulkanSupport::unavailable;
    if (vkEnumeratePhysicalDevices(instance, &count, nullptr) == VK_SUCCESS && count) {
        std::vector<VkPhysicalDevice> devices(std::min(count, 16u));
        count = static_cast<uint32_t>(devices.size());
        if (vkEnumeratePhysicalDevices(instance, &count, devices.data()) == VK_SUCCESS) {
            result = VulkanSupport::driver_too_old;
            for (uint32_t i = 0; i < count; ++i) {
                VkPhysicalDeviceProperties properties{};
                vkGetPhysicalDeviceProperties(devices[i], &properties);
                if (properties.apiVersion >= VK_API_VERSION_1_2) {
                    result = VulkanSupport::supported;
                    break;
                }
            }
        }
    }
    vkDestroyInstance(instance, nullptr);
    return result;
#else
    return VulkanSupport::unavailable;
#endif
}
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
void initialize_backend() {
    static std::once_flag once;
    std::call_once(once, [] { llama_backend_init(); });
}
ggml_backend_dev_t vulkan_device() {
    initialize_backend();
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        auto dev = ggml_backend_dev_get(i);
        const auto type = ggml_backend_dev_type(dev);
        if (type != GGML_BACKEND_DEVICE_TYPE_GPU && type != GGML_BACKEND_DEVICE_TYPE_IGPU) continue;
        const char *name = ggml_backend_dev_name(dev);
        if (name && std::strncmp(name, "Vulkan", 6) == 0) return dev;
    }
    return nullptr;
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

struct ModelSession {
    ModelSession(llama_model *value, std::string active_backend, std::string fallback_reason)
        : model(value), backend(std::move(active_backend)), backend_reason(std::move(fallback_reason)) {}
    ~ModelSession() { context.reset(); llama_model_free(model); }
    llama_model *model;
    std::string backend;
    std::string backend_reason;
    std::unique_ptr<llama_context, decltype(&llama_free)> context{nullptr, llama_free};
    std::vector<llama_token> cached_tokens;
    int32_t context_size = 0;
};

std::unique_ptr<ModelSession> load_session(const std::string &file, llama_model_params params,
                                           const std::string &backend, const std::string &reason = "") {
    llama_model *model = llama_model_load_from_file(file.c_str(), params);
    if (!model) return nullptr;
    auto session = std::make_unique<ModelSession>(model, backend, reason);
    auto cp = llama_context_default_params();
    cp.n_ctx = 4096; cp.n_batch = 512; cp.n_ubatch = 512;
    session->context.reset(llama_init_from_model(model, cp));
    if (!session->context) return nullptr;
    session->context_size = 4096;
    return session;
}

// Reuse only an identical token prefix. Keep the last prompt token for decoding so logits
// are valid even when the new prompt is entirely present in the previous context.
size_t prepare_context(ModelSession &session, const std::vector<llama_token> &prompt, int32_t context_size) {
    if (!session.context || session.context_size < context_size) {
        auto cp = llama_context_default_params();
        cp.n_ctx = context_size; cp.n_batch = 512; cp.n_ubatch = 512;
        session.context.reset(llama_init_from_model(session.model, cp));
        session.context_size = session.context ? context_size : 0;
        session.cached_tokens.clear();
        if (!session.context) throw std::runtime_error("Could not create llama context");
    }
    const auto limit = std::min(prompt.size() - 1, session.cached_tokens.size());
    size_t reused = 0;
    while (reused < limit && prompt[reused] == session.cached_tokens[reused]) ++reused;
    auto mem = llama_get_memory(session.context.get());
    if (reused == 0) llama_memory_clear(mem, false);
    else if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(reused), -1)) {
        llama_memory_clear(mem, false);
        reused = 0;
    }
    session.cached_tokens.resize(reused);
    return reused;
}

bool decode_prompt(ModelSession &session, std::vector<llama_token> &tokens, size_t reused) {
    for (size_t pos = reused; pos < tokens.size(); pos += 512) {
        auto n = static_cast<int32_t>(std::min<size_t>(512, tokens.size() - pos));
        auto batch = llama_batch_get_one(tokens.data() + pos, n);
        if (llama_decode(session.context.get(), batch) != 0) {
            llama_memory_clear(llama_get_memory(session.context.get()), false);
            session.cached_tokens.clear();
            return false;
        }
        session.cached_tokens.insert(session.cached_tokens.end(), tokens.begin() + pos, tokens.begin() + pos + n);
    }
    return true;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_github_ytlog_mobby_android_localmodel_llama_LlamaNative_load(JNIEnv *env, jclass, jstring path) {
    const std::string file = get(env, path);
    try {
        const auto support = vulkan_support();
        std::string fallback_reason = !MOBBY_VULKAN_PACKAGED ? "vulkan_not_packaged" :
            support == VulkanSupport::driver_too_old ? "vulkan_driver_too_old" : "vulkan_unavailable";
        struct stat st{};
        struct sysinfo memory{};
        const bool enough_memory = sysinfo(&memory) == 0 &&
            static_cast<uint64_t>(memory.totalram) * memory.mem_unit >= 12'000'000'000ULL;
        const bool fits_full_offload = stat(file.c_str(), &st) == 0 &&
            (st.st_size < 1'500'000'000 || (enough_memory && st.st_size < 3'000'000'000LL));
        if (support == VulkanSupport::supported && !fits_full_offload)
            fallback_reason = "vulkan_full_offload_unavailable";
        if (auto gpu = support == VulkanSupport::supported && fits_full_offload ? vulkan_device() : nullptr) {
            ggml_backend_dev_t devices[] = {gpu, nullptr};
            auto params = llama_model_default_params();
            params.devices = devices;
            params.n_gpu_layers = 99;
            const char *description = ggml_backend_dev_description(gpu);
            const std::string backend = std::string("Vulkan GPU") + (description && *description ? std::string(" · ") + description : "");
            fallback_reason = "vulkan_load_failed";
            try {
                if (auto session = load_session(file, params, backend)) return reinterpret_cast<jlong>(session.release());
                __android_log_print(ANDROID_LOG_WARN, "MobbyLocalModel", "Vulkan model or context could not load; falling back to CPU");
            } catch (const std::exception &) {
                __android_log_print(ANDROID_LOG_WARN, "MobbyLocalModel", "Vulkan model load failed; falling back to CPU");
            }
        }
        auto params = llama_model_default_params();
        params.n_gpu_layers = 0;
        initialize_backend();
        ggml_backend_dev_t cpu_devices[] = {ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU), nullptr};
        params.devices = cpu_devices;
        if (auto session = load_session(file, params, "CPU", fallback_reason)) return reinterpret_cast<jlong>(session.release());
        fail(env, "llama.cpp could not load GGUF");
    } catch (const std::exception &e) { fail(env, e.what()); }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_github_ytlog_mobby_android_localmodel_llama_LlamaNative_unload(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<ModelSession *>(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_github_ytlog_mobby_android_localmodel_llama_LlamaNative_backend(JNIEnv *env, jclass, jlong handle) {
    if (!reinterpret_cast<ModelSession *>(handle)) { fail(env, "Model is not loaded"); return nullptr; }
    return env->NewStringUTF(reinterpret_cast<ModelSession *>(handle)->backend.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_github_ytlog_mobby_android_localmodel_llama_LlamaNative_backendReason(JNIEnv *env, jclass, jlong handle) {
    auto *session = reinterpret_cast<ModelSession *>(handle);
    if (!session) { fail(env, "Model is not loaded"); return nullptr; }
    return session->backend_reason.empty() ? nullptr : env->NewStringUTF(session->backend_reason.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_github_ytlog_mobby_android_localmodel_llama_LlamaNative_generate(
    JNIEnv *env, jclass, jlong handle, jobjectArray roles, jobjectArray contents, jint max_tokens, jobject sink) {
    auto *session = reinterpret_cast<ModelSession *>(handle);
    if (!session || max_tokens < 1 || max_tokens > 1024 || !roles || !contents ||
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
    std::string prompt;
    try {
        common_chat_templates_inputs input;
        input.enable_thinking = false;
        for (jsize i = 0; i < count; ++i) {
            common_chat_msg message;
            message.role = role_strings[i];
            message.content = content_strings[i];
            input.messages.push_back(std::move(message));
        }
        auto templates = common_chat_templates_init(session->model, "");
        prompt = common_chat_templates_apply(templates.get(), input).prompt;
    } catch (const std::exception &e) { fail(env, e.what()); return -1; }
    if (prompt.empty() || prompt.size() > 128 * 1024) { fail(env, "Invalid or oversized chat prompt"); return -1; }
    const auto *vocab = llama_model_get_vocab(session->model);
    const int32_t needed = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
    if (needed <= 0 || needed > 4096 - max_tokens) { fail(env, "Prompt exceeds context budget"); return -1; }
    std::vector<llama_token> tokens(needed);
    if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), tokens.size(), true, true) < 0) {
        fail(env, "Tokenization failed"); return -1;
    }
    jclass sink_class = env->GetObjectClass(sink);
    jmethodID on_start = env->GetMethodID(sink_class, "onStart", "(II)Z");
    jmethodID on_prefill = env->GetMethodID(sink_class, "onPrefillComplete", "()Z");
    jmethodID on_generated = env->GetMethodID(sink_class, "onGeneratedToken", "(I)Z");
    jmethodID on_token = env->GetMethodID(sink_class, "onToken", "(Ljava/lang/String;)Z");
    if (!on_start || !on_prefill || !on_generated || !on_token) return -1;
    size_t reused;
    try { reused = prepare_context(*session, tokens, 4096); }
    catch (const std::exception &e) { fail(env, e.what()); return -1; }
    if (!env->CallBooleanMethod(sink, on_start, needed, static_cast<jint>(reused)) || env->ExceptionCheck()) return -2;
    auto sp = llama_sampler_chain_default_params();
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(sp), llama_sampler_free);
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
    if (!decode_prompt(*session, tokens, reused)) { fail(env, "Prompt decode failed"); return -1; }
    if (!env->CallBooleanMethod(sink, on_prefill) || env->ExceptionCheck()) return -2;
    std::string pending;
    int generated = 0;
    for (; generated < max_tokens; ++generated) {
        llama_token token = llama_sampler_sample(sampler.get(), session->context.get(), -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        if (!env->CallBooleanMethod(sink, on_generated, generated + 1) || env->ExceptionCheck()) return -2;
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
        if (llama_decode(session->context.get(), batch) != 0) {
            llama_memory_clear(llama_get_memory(session->context.get()), false);
            session->cached_tokens.clear();
            fail(env, "Generation decode failed"); return -1;
        }
        session->cached_tokens.push_back(token);
    }
    return generated;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_github_ytlog_mobby_android_localmodel_llama_LlamaNative_generateTools(
    JNIEnv *env, jclass, jlong handle, jstring messages_json, jstring tools_json, jstring choice,
    jboolean parallel, jboolean enable_thinking, jfloat temperature, jfloat top_p, jint max_tokens, jobject sink) {
    try {
        auto *session = reinterpret_cast<ModelSession *>(handle);
        if (!session || max_tokens < 1 || max_tokens > 1024 || temperature < 0 || temperature > 2 || top_p <= 0 || top_p > 1)
            throw std::runtime_error("Invalid generation arguments");
        const auto messages = common_json::parse(get(env, messages_json));
        const auto tools = common_json::parse(get(env, tools_json));
        common_chat_templates_inputs input;
        input.messages = common_chat_msgs_parse_oaicompat(messages);
        input.tools = common_chat_tools_parse_oaicompat(tools);
        input.parallel_tool_calls = parallel;
        input.enable_thinking = enable_thinking;
        const std::string selected = get(env, choice);
        if (selected == "none") input.tool_choice = COMMON_CHAT_TOOL_CHOICE_NONE;
        else if (selected == "required" || selected.rfind("required:", 0) == 0) {
            input.tool_choice = COMMON_CHAT_TOOL_CHOICE_REQUIRED;
            if (selected.rfind("required:", 0) == 0) {
                const auto name = selected.substr(9);
                auto found = std::find_if(input.tools.begin(), input.tools.end(), [&](const common_chat_tool & tool) { return tool.name == name; });
                if (found == input.tools.end()) throw std::runtime_error("Unknown required tool");
                input.tools = {*found};
            }
        } else if (selected != "auto") throw std::runtime_error("Unsupported tool choice");
        auto templates = common_chat_templates_init(session->model, "");
        auto params = common_chat_templates_apply(templates.get(), input);
        if (params.format == COMMON_CHAT_FORMAT_CONTENT_ONLY && !input.tools.empty() && input.tool_choice != COMMON_CHAT_TOOL_CHOICE_NONE)
            throw std::runtime_error("Model chat template does not support tool calls");
        if (params.prompt.empty() || params.prompt.size() > 256 * 1024) throw std::runtime_error("Invalid or oversized tool prompt");
        const auto *vocab = llama_model_get_vocab(session->model);
        const int32_t context_limit = 32768;
        int32_t needed = -llama_tokenize(vocab, params.prompt.data(), params.prompt.size(), nullptr, 0, true, true);
        if (needed <= 0 || needed > context_limit - max_tokens) throw std::runtime_error("Prompt exceeds context budget");
        const int32_t context_size = std::max<int32_t>(4096, ((needed + max_tokens + 1023) / 1024) * 1024);
        std::vector<llama_token> tokens(needed);
        if (llama_tokenize(vocab, params.prompt.data(), params.prompt.size(), tokens.data(), tokens.size(), true, true) < 0)
            throw std::runtime_error("Tokenization failed");
        jclass sink_class = env->GetObjectClass(sink);
        jmethodID on_start = env->GetMethodID(sink_class, "onStart", "(II)Z");
        jmethodID on_prefill = env->GetMethodID(sink_class, "onPrefillComplete", "()Z");
        jmethodID on_generated = env->GetMethodID(sink_class, "onGeneratedToken", "(I)Z");
        jmethodID on_token = env->GetMethodID(sink_class, "onToken", "()Z");
        jmethodID on_text = env->GetMethodID(sink_class, "onText", "(Ljava/lang/String;)Z");
        if (!on_start || !on_prefill || !on_generated || !on_token || !on_text) return nullptr;
        const size_t reused = prepare_context(*session, tokens, context_size);
        if (!env->CallBooleanMethod(sink, on_start, needed, static_cast<jint>(reused)) || env->ExceptionCheck()) {
            if (!env->ExceptionCheck()) throw std::runtime_error("Generation cancelled");
            return nullptr;
        }
        auto sp = llama_sampler_chain_default_params();
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(sp), llama_sampler_free);
        if (!params.grammar.empty()) {
            llama_sampler *grammar = nullptr;
            if (params.grammar_lazy) {
                std::vector<std::string> words;
                std::vector<llama_token> trigger_tokens;
                for (const auto & trigger : params.grammar_triggers) {
                    if (trigger.type == COMMON_GRAMMAR_TRIGGER_TYPE_WORD) words.push_back(trigger.value);
                    else if (trigger.type == COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN) trigger_tokens.push_back(trigger.token);
                    else throw std::runtime_error("Model uses an unsupported tool grammar trigger");
                }
                if (words.empty() && trigger_tokens.empty()) throw std::runtime_error("Model tool grammar has no trigger");
                std::vector<const char *> word_ptrs;
                for (const auto & word : words) word_ptrs.push_back(word.c_str());
                grammar = llama_sampler_init_grammar_lazy(vocab, params.grammar.c_str(), "root",
                    word_ptrs.data(), word_ptrs.size(), trigger_tokens.data(), trigger_tokens.size());
            } else grammar = llama_sampler_init_grammar(vocab, params.grammar.c_str(), "root");
            if (!grammar) throw std::runtime_error("Invalid tool grammar");
            llama_sampler_chain_add(sampler.get(), grammar);
        }
        if (temperature == 0) llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        else {
            if (top_p < 1) llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(top_p, 1));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        }
        for (size_t pos = reused; pos < tokens.size(); pos += 512) {
            if (!env->CallBooleanMethod(sink, on_token) || env->ExceptionCheck()) {
                if (!env->ExceptionCheck()) throw std::runtime_error("Generation cancelled");
                return nullptr;
            }
            auto n = static_cast<int32_t>(std::min<size_t>(512, tokens.size() - pos));
            auto batch = llama_batch_get_one(tokens.data() + pos, n);
            if (llama_decode(session->context.get(), batch) != 0) {
                llama_memory_clear(llama_get_memory(session->context.get()), false);
                session->cached_tokens.clear();
                throw std::runtime_error("Prompt decode failed");
            }
            session->cached_tokens.insert(session->cached_tokens.end(), tokens.begin() + pos, tokens.begin() + pos + n);
        }
        if (!env->CallBooleanMethod(sink, on_prefill) || env->ExceptionCheck()) {
            if (!env->ExceptionCheck()) throw std::runtime_error("Generation cancelled");
            return nullptr;
        }
        common_chat_parser_params parser(params);
        if (!params.parser.empty()) parser.parser.load(params.parser);
        std::string output;
        std::string streamed_content;
        int generated = 0;
        for (; generated < max_tokens; ++generated) {
            if (!env->CallBooleanMethod(sink, on_token) || env->ExceptionCheck()) {
                if (!env->ExceptionCheck()) throw std::runtime_error("Generation cancelled");
                return nullptr;
            }
            llama_token token = llama_sampler_sample(sampler.get(), session->context.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) break;
            if (!env->CallBooleanMethod(sink, on_generated, generated + 1) || env->ExceptionCheck()) {
                if (!env->ExceptionCheck()) throw std::runtime_error("Generation cancelled");
                return nullptr;
            }
            std::vector<char> piece(1024);
            int n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true);
            if (n < 0) throw std::runtime_error("Token piece too large");
            output.append(piece.data(), n);
            const size_t valid = complete_utf8(output);
            if (valid > 0) {
                std::string partial_content;
                try {
                    partial_content = common_chat_parse(output.substr(0, valid), true, parser).content;
                } catch (const std::exception &) {
                    // A partial grammar may be undecidable until more tokens arrive; the final parse below is strict.
                }
                if (partial_content.size() > streamed_content.size() &&
                    partial_content.compare(0, streamed_content.size(), streamed_content) == 0) {
                    const std::string delta = partial_content.substr(streamed_content.size());
                    jstring chunk = env->NewStringUTF(delta.c_str());
                    const bool keep = env->CallBooleanMethod(sink, on_text, chunk);
                    env->DeleteLocalRef(chunk);
                    if (env->ExceptionCheck()) return nullptr;
                    if (!keep) throw std::runtime_error("Generation cancelled");
                    streamed_content = partial_content;
                }
            }
            auto batch = llama_batch_get_one(&token, 1);
            if (llama_decode(session->context.get(), batch) != 0) {
                llama_memory_clear(llama_get_memory(session->context.get()), false);
                session->cached_tokens.clear();
                throw std::runtime_error("Generation decode failed");
            }
            session->cached_tokens.push_back(token);
        }
        auto answer = common_chat_parse(output, false, parser);
        common_json result = {
            {"content", answer.content}, {"input_tokens", needed}, {"output_tokens", generated},
            {"length", generated == max_tokens}, {"tool_calls", common_json::array()}
        };
        for (const auto & tool : answer.tool_calls) {
            result["tool_calls"].push_back({{"id", tool.id}, {"name", tool.name}, {"arguments", tool.arguments}});
        }
        return env->NewStringUTF(result.dump().c_str());
    } catch (const std::exception & e) {
        fail(env, e.what());
        return nullptr;
    }
}
