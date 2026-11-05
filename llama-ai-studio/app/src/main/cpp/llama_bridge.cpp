// JNI bridge between com.llamaaistudio.app.engine.LlamaNative (Kotlin) and llama.cpp.
// One Session == one loaded model + its context; handles are the raw Session* cast to jlong.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"
#include "jni_utils.h"
#include <nlohmann/json.hpp>

using json = nlohmann::json;

namespace {

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::atomic<bool> cancel{false};
    int32_t gpuLayersRequested = 0;
    std::mutex decodeMutex; // serialises calls against this context
};

std::once_flag g_backendOnce;

// NULL-terminated, empty device list: passing it as llama_model_params::devices keeps every
// layer *and* every op on the CPU (n_gpu_layers = 0 alone still lets llama.cpp offload big
// prompt batches to the GPU through op_offload).
ggml_backend_dev_t g_noDevices[1] = {nullptr};

bool isGpuDevice(ggml_backend_dev_t dev) {
    auto t = ggml_backend_dev_type(dev);
    return t == GGML_BACKEND_DEVICE_TYPE_GPU || t == GGML_BACKEND_DEVICE_TYPE_IGPU;
}

bool hasGpuDevice() {
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        if (isGpuDevice(ggml_backend_dev_get(i))) return true;
    }
    return false;
}

std::string jsonEscape(const std::string &in) {
    std::string out;
    out.reserve(in.size());
    for (char c : in) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += c;
                }
        }
    }
    return out;
}

std::string tokenToPiece(const llama_vocab *vocab, llama_token token) {
    char buf[256];
    int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
    if (n < 0) {
        std::vector<char> big(-n);
        n = llama_token_to_piece(vocab, token, big.data(), (int32_t) big.size(), 0, true);
        if (n < 0) return {};
        return {big.data(), (size_t) n};
    }
    return {buf, (size_t) n};
}

struct ChatTurn {
    std::string role;
    std::string content;
};

// messagesJson: [{"role":"system|user|assistant","content":".."}, ...], oldest first.
std::vector<ChatTurn> parseMessages(const std::string &messagesJson) {
    std::vector<ChatTurn> turns;
    try {
        json arr = json::parse(messagesJson);
        for (auto &m : arr) {
            turns.push_back({m.value("role", "user"), m.value("content", "")});
        }
    } catch (const std::exception &e) {
        llamaLogError("Failed to parse messages JSON: %s", e.what());
    }
    return turns;
}

std::string formatPrompt(const llama_model *model, const std::vector<ChatTurn> &turns) {
    const char *tmpl = llama_model_chat_template(model, nullptr);
    size_t totalChars = 0;
    for (auto &t : turns) totalChars += t.content.size();

    if (tmpl != nullptr) {
        std::vector<llama_chat_message> msgs;
        msgs.reserve(turns.size());
        for (auto &t : turns) {
            msgs.push_back({t.role.c_str(), t.content.c_str()});
        }
        std::vector<char> buf(4096 + 2 * totalChars);
        int32_t need = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                                  buf.data(), (int32_t) buf.size());
        if (need > (int32_t) buf.size()) {
            buf.resize(need);
            need = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(),
                                              (int32_t) buf.size());
        }
        if (need > 0) {
            return {buf.data(), (size_t) need};
        }
    }

    // No usable chat template in the model metadata: fall back to a generic
    // instruction-style transcript. Works reasonably for base/completion models too.
    std::ostringstream oss;
    for (auto &t : turns) {
        if (t.role == "system") {
            oss << t.content << "\n\n";
        } else if (t.role == "user") {
            oss << "User: " << t.content << "\n";
        } else {
            oss << "Assistant: " << t.content << "\n";
        }
    }
    oss << "Assistant: ";
    return oss.str();
}

jmethodID findOnTokenMethod(JNIEnv *env, jobject callback) {
    jclass cls = env->GetObjectClass(callback);
    jmethodID mid = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");
    env->DeleteLocalRef(cls);
    if (mid == nullptr) {
        // GetMethodID leaves a pending NoSuchMethodError on failure; if we don't clear it,
        // every later JNI call in this function silently fails too, which would look exactly
        // like generation running (stats still come back) but streaming nothing to the UI.
        if (env->ExceptionCheck()) env->ExceptionClear();
        llamaLogError("onToken method lookup failed; token callback disabled for this call");
    }
    return mid;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_llamaaistudio_app_engine_LlamaNative_backendInit(JNIEnv *, jobject) {
    std::call_once(g_backendOnce, []() { llama_backend_init(); });
}

JNIEXPORT void JNICALL
Java_com_llamaaistudio_app_engine_LlamaNative_backendFree(JNIEnv *, jobject) {
    llama_backend_free();
}

JNIEXPORT jlong JNICALL
Java_com_llamaaistudio_app_engine_LlamaNative_loadModel(
        JNIEnv *env, jobject, jstring jModelPath, jint nCtx, jint nBatch, jint nThreads,
        jint nThreadsBatch, jint nGpuLayers, jboolean useMmap, jboolean useMlock) {
    std::string modelPath = jstringToUtf8(env, jModelPath);

    llama_model_params mparams = llama_model_default_params();
    // nGpuLayers: 0 = CPU only, N = offload N layers to the GPU (Vulkan), < 0 = all layers.
    const bool useGpu = nGpuLayers != 0 && hasGpuDevice();
    mparams.n_gpu_layers = useGpu ? (nGpuLayers < 0 ? 999 : nGpuLayers) : 0;
    if (!useGpu) mparams.devices = g_noDevices;
    if (useMlock) {
        mparams.load_mode = useMmap ? LLAMA_LOAD_MODE_MMAP_MLOCK : LLAMA_LOAD_MODE_MLOCK;
    } else {
        mparams.load_mode = useMmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    }

    llama_model *model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (model == nullptr) {
        llamaLogError("Failed to load model from %s", modelPath.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) nCtx;
    cparams.n_batch = (uint32_t) nBatch;
    cparams.n_ubatch = (uint32_t) nBatch;
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreadsBatch;
    cparams.no_perf = true; // the bridge does its own wall-clock timing
    cparams.offload_kqv = useGpu; // KV cache on the GPU only when layers are there too
    cparams.op_offload = useGpu;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llamaLogError("Failed to create context for %s", modelPath.c_str());
        llama_model_free(model);
        return 0;
    }

    auto *session = new Session();
    session->gpuLayersRequested = mparams.n_gpu_layers;
    session->model = model;
    session->ctx = ctx;
    session->vocab = llama_model_get_vocab(model);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_llamaaistudio_app_engine_LlamaNative_freeModel(JNIEnv *, jobject, jlong handle) {
    if (handle == 0) return;
    auto *session = reinterpret_cast<Session *>(handle);
    std::lock_guard<std::mutex> lock(session->decodeMutex);
    if (session->ctx) llama_free(session->ctx);
    if (session->model) llama_model_free(session->model);
    delete session;
}

JNIEXPORT jstring JNICALL
Java_com_llamaaistudio_app_engine_LlamaNative_getModelInfo(JNIEnv *env, jobject, jlong handle) {
    if (handle == 0) return utf8ToJstring(env, "{}");
    auto *session = reinterpret_cast<Session *>(handle);

    char desc[256] = {0};
    llama_model_desc(session->model, desc, sizeof(desc));

    uint64_t nParams = llama_model_n_params(session->model);
    uint64_t sizeBytes = llama_model_size(session->model);
    int32_t nCtxTrain = llama_model_n_ctx_train(session->model);
    int32_t nVocab = llama_vocab_n_tokens(session->vocab);
    bool hasChatTemplate = llama_model_chat_template(session->model, nullptr) != nullptr;
    int32_t nLayer = llama_model_n_layer(session->model);
    int32_t gpuLayers = std::min(session->gpuLayersRequested, nLayer + 1); // +1: output layer

    std::ostringstream oss;
    oss << "{"
        << "\"desc\":\"" << jsonEscape(desc) << "\","
        << "\"nParams\":" << nParams << ","
        << "\"sizeBytes\":" << sizeBytes << ","
        << "\"nCtxTrain\":" << nCtxTrain << ","
        << "\"nVocab\":" << nVocab << ","
        << "\"hasChatTemplate\":" << (hasChatTemplate ? "true" : "false") << ","
        << "\"nLayer\":" << nLayer << ","
        << "\"gpuLayers\":" << gpuLayers
        << "}";
    return utf8ToJstring(env, oss.str());
}

JNIEXPORT jint JNICALL
Java_com_llamaaistudio_app_engine_LlamaNative_tokenCount(JNIEnv *env, jobject, jlong handle,
                                                          jstring jText) {
    if (handle == 0) return 0;
    auto *session = reinterpret_cast<Session *>(handle);
    std::string text = jstringToUtf8(env, jText);
    int32_t n = -llama_tokenize(session->vocab, text.c_str(), (int32_t) text.size(), nullptr, 0,
                                 true, true);
    return n < 0 ? 0 : n;
}

JNIEXPORT jstring JNICALL
Java_com_llamaaistudio_app_engine_LlamaNative_generate(
        JNIEnv *env, jobject, jlong handle, jstring jMessagesJson,
        jint maxTokens, jfloat temperature, jint topK, jfloat topP, jfloat minP,
        jfloat repeatPenalty, jint repeatLastN, jlong seed, jobject callback) {

    auto errorJson = [&](const char *reason) {
        std::ostringstream oss;
        oss << "{\"error\":\"" << jsonEscape(reason) << "\"}";
        return utf8ToJstring(env, oss.str());
    };

    if (handle == 0) return errorJson("invalid_handle");
    auto *session = reinterpret_cast<Session *>(handle);
    std::lock_guard<std::mutex> lock(session->decodeMutex);
    session->cancel.store(false);

    std::string messagesJson = jstringToUtf8(env, jMessagesJson);
    std::vector<ChatTurn> turns = parseMessages(messagesJson);
    if (turns.empty()) return errorJson("no_messages");
    jmethodID onTokenMid = findOnTokenMethod(env, callback);

    // Fresh KV cache per call: each generate() invocation carries the whole conversation
    // as plain text, so there is no cross-call state to preserve. Reusing the cache across
    // turns (to skip re-prefilling shared history) is a follow-up perf optimisation.
    llama_memory_clear(llama_get_memory(session->ctx), true);

    const int32_t nCtx = (int32_t) llama_n_ctx(session->ctx);
    const int32_t nBatch = std::max<int32_t>(1, (int32_t) llama_n_batch(session->ctx));
    // Leave room for the reply: maxTokens where possible, but never less than 32 tokens and
    // never so much that the prompt gets no room at all.
    const int32_t replyReserve = std::max<int32_t>(32, std::min<int32_t>(maxTokens, nCtx / 2));
    const int32_t budget = std::max<int32_t>(1, nCtx - replyReserve);

    auto tokenize = [&](const std::string &text) {
        int32_t n = -llama_tokenize(session->vocab, text.c_str(), (int32_t) text.size(), nullptr, 0,
                                    true, true);
        std::vector<llama_token> toks;
        if (n <= 0) return toks;
        toks.resize(n);
        llama_tokenize(session->vocab, text.c_str(), (int32_t) text.size(), toks.data(), n, true, true);
        return toks;
    };

    // Long sessions outgrow the context window. Drop the oldest whole turns (keeping the
    // system prompt and the latest user message) and re-apply the chat template, rather than
    // chopping raw tokens off the front - that would cut off the BOS token, the system prompt
    // and the template's own header, which derails most chat models.
    std::vector<llama_token> promptTokens = tokenize(formatPrompt(session->model, turns));
    while ((int32_t) promptTokens.size() > budget) {
        auto firstDroppable = std::find_if(turns.begin(), turns.end(),
                                           [](const ChatTurn &t) { return t.role != "system"; });
        if (firstDroppable == turns.end() || firstDroppable + 1 >= turns.end()) break;
        turns.erase(firstDroppable);
        promptTokens = tokenize(formatPrompt(session->model, turns));
    }
    if (promptTokens.empty()) return errorJson("tokenize_failed");
    if ((int32_t) promptTokens.size() > budget) {
        // A single message larger than the whole context: keep its tail.
        int32_t drop = (int32_t) promptTokens.size() - budget;
        promptTokens.erase(promptTokens.begin(), promptTokens.begin() + drop);
        llamaLogInfo("Prompt truncated by %d tokens to fit context", drop);
    }

    llama_sampler_chain_params schainParams = llama_sampler_chain_default_params();
    llama_sampler *chain = llama_sampler_chain_init(schainParams);
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(
        llama_vocab_n_tokens(session->vocab), repeatLastN, repeatPenalty, 0.0f, 0.0f));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        if (topK > 0) llama_sampler_chain_add(chain, llama_sampler_init_top_k(topK));
        if (topP < 1.0f) llama_sampler_chain_add(chain, llama_sampler_init_top_p(topP, 1));
        if (minP > 0.0f) llama_sampler_chain_add(chain, llama_sampler_init_min_p(minP, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist((uint32_t) seed));
    }

    auto t0 = std::chrono::steady_clock::now();

    // llama_decode() rejects batches larger than n_batch, so prefill in n_batch-sized chunks -
    // a single-shot decode made every conversation longer than ~512 tokens fail outright.
    for (size_t off = 0; off < promptTokens.size(); off += (size_t) nBatch) {
        if (session->cancel.load()) {
            llama_sampler_free(chain);
            return errorJson("cancelled");
        }
        const int32_t n = (int32_t) std::min(promptTokens.size() - off, (size_t) nBatch);
        llama_batch batch = llama_batch_get_one(promptTokens.data() + off, n);
        if (llama_decode(session->ctx, batch) != 0) {
            llama_sampler_free(chain);
            return errorJson("prefill_decode_failed");
        }
    }
    int32_t nPast = (int32_t) promptTokens.size();

    auto t1 = std::chrono::steady_clock::now();

    std::string stopReason = "length";
    int32_t nGenerated = 0;
    llama_token newToken;
    std::string pending;

    auto emitPiece = [&](const std::string &text) -> bool {
        if (onTokenMid == nullptr || text.empty()) return true;
        jstring jPiece = utf8ToJstring(env, text);
        jboolean keepGoing = env->CallBooleanMethod(callback, onTokenMid, jPiece);
        env->DeleteLocalRef(jPiece);
        if (env->ExceptionCheck()) {
            // An exception thrown by the Kotlin callback must not stay pending across more
            // JNI calls (that's undefined behaviour); treat it as a stop request.
            env->ExceptionClear();
            return false;
        }
        return keepGoing == JNI_TRUE;
    };

    for (int32_t i = 0; i < maxTokens; i++) {
        if (session->cancel.load()) {
            stopReason = "cancelled";
            break;
        }

        newToken = llama_sampler_sample(chain, session->ctx, -1);
        llama_sampler_accept(chain, newToken);

        if (llama_vocab_is_eog(session->vocab, newToken)) {
            stopReason = "eos";
            break;
        }

        pending += tokenToPiece(session->vocab, newToken);
        nGenerated++;

        // A multi-byte character (emoji, CJK, accented letters in some vocabs) can be split
        // across tokens; hold back the incomplete tail until the next token completes it.
        const size_t ready = utf8CompletePrefixLength(pending);
        if (ready > 0 && !emitPiece(pending.substr(0, ready))) {
            stopReason = "cancelled";
            break;
        }
        pending.erase(0, ready);

        if (nPast >= nCtx) {
            stopReason = "context_full";
            break;
        }
        llama_batch nextBatch = llama_batch_get_one(&newToken, 1);
        if (llama_decode(session->ctx, nextBatch) != 0) {
            stopReason = "decode_error";
            break;
        }
        nPast++;
    }
    if (!pending.empty()) emitPiece(pending);

    auto t2 = std::chrono::steady_clock::now();
    llama_sampler_free(chain);

    double prefillMs = std::chrono::duration<double, std::milli>(t1 - t0).count();
    double genMs = std::chrono::duration<double, std::milli>(t2 - t1).count();
    double promptTps = prefillMs > 0 ? (double) promptTokens.size() / (prefillMs / 1000.0) : 0;
    double genTps = genMs > 0 ? (double) nGenerated / (genMs / 1000.0) : 0;

    std::ostringstream oss;
    oss << "{"
        << "\"promptTokens\":" << promptTokens.size() << ","
        << "\"genTokens\":" << nGenerated << ","
        << "\"prefillMs\":" << prefillMs << ","
        << "\"genMs\":" << genMs << ","
        << "\"promptTps\":" << promptTps << ","
        << "\"genTps\":" << genTps << ","
        << "\"stopReason\":\"" << stopReason << "\""
        << "}";
    return utf8ToJstring(env, oss.str());
}

JNIEXPORT void JNICALL
Java_com_llamaaistudio_app_engine_LlamaNative_cancelGeneration(JNIEnv *, jobject, jlong handle) {
    if (handle == 0) return;
    reinterpret_cast<Session *>(handle)->cancel.store(true);
}

JNIEXPORT jboolean JNICALL
Java_com_llamaaistudio_app_engine_LlamaNative_supportsVulkan(JNIEnv *, jobject) {
    // Runtime check: the Vulkan backend is compiled in, but only counts if this device's
    // driver actually exposes a usable GPU to it.
    std::call_once(g_backendOnce, []() { llama_backend_init(); });
    return hasGpuDevice() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_llamaaistudio_app_engine_LlamaNative_listDevices(JNIEnv *env, jobject) {
    std::call_once(g_backendOnce, []() { llama_backend_init(); });
    std::ostringstream oss;
    oss << "[";
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        size_t freeMem = 0, totalMem = 0;
        ggml_backend_dev_memory(dev, &freeMem, &totalMem);
        const char *type = "accel";
        switch (ggml_backend_dev_type(dev)) {
            case GGML_BACKEND_DEVICE_TYPE_CPU: type = "cpu"; break;
            case GGML_BACKEND_DEVICE_TYPE_GPU: type = "gpu"; break;
            case GGML_BACKEND_DEVICE_TYPE_IGPU: type = "igpu"; break;
            default: break;
        }
        if (i > 0) oss << ",";
        oss << "{\"name\":\"" << jsonEscape(ggml_backend_dev_name(dev)) << "\","
            << "\"description\":\"" << jsonEscape(ggml_backend_dev_description(dev)) << "\","
            << "\"type\":\"" << type << "\","
            << "\"memoryFree\":" << freeMem << ",\"memoryTotal\":" << totalMem << "}";
    }
    oss << "]";
    return utf8ToJstring(env, oss.str());
}

JNIEXPORT void JNICALL
Java_com_llamaaistudio_app_engine_LlamaNative_setThreads(JNIEnv *, jobject, jlong handle, jint nThreads,
                                                         jint nThreadsBatch) {
    if (handle == 0) return;
    auto *session = reinterpret_cast<Session *>(handle);
    std::lock_guard<std::mutex> lock(session->decodeMutex);
    llama_set_n_threads(session->ctx, nThreads, nThreadsBatch);
}

JNIEXPORT jint JNICALL
Java_com_llamaaistudio_app_engine_LlamaNative_nativeThreadCountRecommendation(JNIEnv *, jobject) {
    unsigned n = std::thread::hardware_concurrency();
    if (n == 0) return 4;
    // Leave one core for the UI/binder threads; Tensor G4 is a 1+3+4 big.LITTLE part, using
    // all cores (incl. the efficiency cluster) for prefill still wins on throughput, but we
    // keep one in reserve so the UI thread doesn't stutter mid-generation.
    return (int32_t) std::max(1u, n - 1);
}

} // extern "C"
