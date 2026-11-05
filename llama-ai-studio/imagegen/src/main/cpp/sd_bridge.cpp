// JNI bridge between com.llamaaistudio.imagegen.SdNative (Kotlin) and stable-diffusion.cpp.

#include <jni.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include "stable-diffusion.h"
#include "jni_utils.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

namespace {

std::mutex g_genMutex; // serialises calls against a context; mirrors llama_bridge's pattern.

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

struct ProgressCtx {
    JNIEnv *env = nullptr;
    jobject callback = nullptr;
    jmethodID mid = nullptr;
};

void progressTrampoline(int step, int steps, float time, void *data) {
    auto *ctx = reinterpret_cast<ProgressCtx *>(data);
    if (ctx == nullptr || ctx->env == nullptr || ctx->callback == nullptr || ctx->mid == nullptr) return;
    ctx->env->CallVoidMethod(ctx->callback, ctx->mid, step, steps, time);
    // Never leave a Java exception pending while native code keeps calling into JNI.
    if (ctx->env->ExceptionCheck()) ctx->env->ExceptionClear();
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_llamaaistudio_imagegen_SdNative_getSystemInfo(JNIEnv *env, jobject) {
    return utf8ToJstring(env, sd_get_system_info());
}

JNIEXPORT jlong JNICALL
Java_com_llamaaistudio_imagegen_SdNative_loadModel(
        JNIEnv *env, jobject, jstring jModelPath, jint nThreads, jboolean enableMmap) {
    std::string modelPath = jstringToUtf8(env, jModelPath);

    sd_ctx_params_t params;
    sd_ctx_params_init(&params);
    params.model_path = modelPath.c_str();
    params.n_threads = nThreads;
    params.enable_mmap = enableMmap;

    sd_ctx_t *ctx = new_sd_ctx(&params);
    if (ctx == nullptr) {
        sdLogError("Failed to load diffusion model from %s", modelPath.c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_llamaaistudio_imagegen_SdNative_freeModel(JNIEnv *, jobject, jlong handle) {
    if (handle == 0) return;
    std::lock_guard<std::mutex> lock(g_genMutex);
    free_sd_ctx(reinterpret_cast<sd_ctx_t *>(handle));
}

JNIEXPORT jboolean JNICALL
Java_com_llamaaistudio_imagegen_SdNative_supportsImageGeneration(JNIEnv *, jobject, jlong handle) {
    if (handle == 0) return JNI_FALSE;
    return sd_ctx_supports_image_generation(reinterpret_cast<sd_ctx_t *>(handle)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_llamaaistudio_imagegen_SdNative_generateImage(
        JNIEnv *env, jobject, jlong handle, jstring jPrompt, jstring jNegativePrompt,
        jint width, jint height, jint steps, jfloat cfgScale, jlong seed, jstring jOutputPath,
        jobject callback, jbyteArray jInitRgb, jint initWidth, jint initHeight, jfloat strength) {

    auto errorJson = [&](const std::string &reason) {
        std::ostringstream oss;
        oss << "{\"error\":\"" << jsonEscape(reason) << "\"}";
        return utf8ToJstring(env, oss.str());
    };

    if (handle == 0) return errorJson("invalid_handle");
    auto *ctx = reinterpret_cast<sd_ctx_t *>(handle);
    std::lock_guard<std::mutex> lock(g_genMutex);

    std::string prompt = jstringToUtf8(env, jPrompt);
    std::string negativePrompt = jstringToUtf8(env, jNegativePrompt);
    std::string outputPath = jstringToUtf8(env, jOutputPath);

    ProgressCtx progressCtx;
    progressCtx.env = env;
    progressCtx.callback = callback;
    if (callback != nullptr) {
        jclass cbClass = env->GetObjectClass(callback);
        progressCtx.mid = env->GetMethodID(cbClass, "onProgress", "(IIF)V");
        env->DeleteLocalRef(cbClass);
        if (progressCtx.mid == nullptr && env->ExceptionCheck()) env->ExceptionClear();
    }
    sd_set_progress_callback(progressTrampoline, &progressCtx);

    sd_img_gen_params_t params;
    sd_img_gen_params_init(&params);
    params.prompt = prompt.c_str();
    params.negative_prompt = negativePrompt.c_str();
    params.width = width;
    params.height = height;
    params.seed = seed;
    params.sample_params.sample_steps = steps;
    params.sample_params.guidance.txt_cfg = cfgScale;

    // img2img: start from an existing picture (RGB888, already resized to width x height on
    // the Kotlin side). strength 0..1 = how much the model may change it.
    std::vector<uint8_t> initPixels;
    if (jInitRgb != nullptr) {
        const jsize len = env->GetArrayLength(jInitRgb);
        if (initWidth != width || initHeight != height || len != (jsize) (initWidth * initHeight * 3)) {
            sd_set_progress_callback(nullptr, nullptr);
            return errorJson("init_image_size_mismatch");
        }
        initPixels.resize((size_t) len);
        env->GetByteArrayRegion(jInitRgb, 0, len, reinterpret_cast<jbyte *>(initPixels.data()));
        params.init_image = sd_image_t{(uint32_t) initWidth, (uint32_t) initHeight, 3, initPixels.data()};
        params.strength = std::clamp(strength, 0.05f, 1.0f);
    }

    auto t0 = std::chrono::steady_clock::now();
    sd_image_generation_result_t *result = nullptr;
    bool ok = generate_image(ctx, &params, &result);
    auto t1 = std::chrono::steady_clock::now();

    sd_set_progress_callback(nullptr, nullptr);

    if (!ok || result == nullptr || result->count <= 0) {
        if (result) free_sd_image_generation_result(result);
        return errorJson("generation_failed");
    }

    sd_image_t image = result->items[0].image;
    int writeOk = stbi_write_png(
        outputPath.c_str(), (int) image.width, (int) image.height, (int) image.channel,
        image.data, (int) (image.width * image.channel));

    double genMs = std::chrono::duration<double, std::milli>(t1 - t0).count();

    std::ostringstream oss;
    if (!writeOk) {
        oss << "{\"error\":\"png_write_failed\"}";
    } else {
        oss << "{"
            << "\"widthOut\":" << image.width << ","
            << "\"heightOut\":" << image.height << ","
            << "\"genMs\":" << genMs << ","
            << "\"seedUsed\":" << params.seed
            << "}";
    }

    free_sd_image_generation_result(result);
    return utf8ToJstring(env, oss.str());
}

JNIEXPORT void JNICALL
Java_com_llamaaistudio_imagegen_SdNative_cancelGeneration(JNIEnv *, jobject, jlong handle) {
    if (handle == 0) return;
    sd_cancel_generation(reinterpret_cast<sd_ctx_t *>(handle), SD_CANCEL_ALL);
}

JNIEXPORT jint JNICALL
Java_com_llamaaistudio_imagegen_SdNative_recommendedThreadCount(JNIEnv *, jobject) {
    unsigned n = std::thread::hardware_concurrency();
    if (n == 0) return 4;
    return (jint) std::max(1u, n - 1);
}

} // extern "C"
