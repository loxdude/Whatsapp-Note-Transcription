#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <cstdio>
#include <exception>
#include <mutex>
#include <string>

#include "parakeet.h"

namespace {
constexpr const char* kTag = "ParakeetJNI";

std::mutex g_mutex;
parakeet_context* g_context = nullptr;
std::string g_model_path;
std::atomic<bool> g_cancelled{false};
std::atomic<int> g_progress{0};

void android_parakeet_log(enum ggml_log_level level, const char* text, void*) {
    if (!text || !*text) return;
    int priority = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) priority = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) priority = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_DEBUG) priority = ANDROID_LOG_DEBUG;
    __android_log_write(priority, "ParakeetRuntime", text);
}

std::string jstring_utf8(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

void throw_exception(JNIEnv* env, const char* type, const char* message) {
    jclass exception = env->FindClass(type);
    if (exception) env->ThrowNew(exception, message);
}

void close_locked() {
    if (g_context) {
        parakeet_free(g_context);
        g_context = nullptr;
    }
    g_model_path.clear();
}

bool ensure_context_locked(JNIEnv* env, const std::string& path) {
    if (g_context && g_model_path == path) return true;

    close_locked();
    // Qualcomm's driver rejects ggml's specialized Q4_K mat-vec shader. The
    // Vulkan backend patch routes those operations through its general matmul
    // kernels; keep MMVQ disabled while validating that compatibility path.
    setenv("GGML_VK_DISABLE_MMVQ", "1", 1);
    parakeet_log_set(android_parakeet_log, nullptr);
    parakeet_context_params context_params = parakeet_context_default_params();
    context_params.use_gpu = true;
    context_params.gpu_device = 0;
    __android_log_print(ANDROID_LOG_INFO, kTag, "Runtime features: %s", parakeet_print_system_info());
    g_context = parakeet_init_from_file_with_params(path.c_str(), context_params);
    if (!g_context) {
        throw_exception(env, "java/lang/IllegalStateException",
                        "whisper.cpp could not load this Parakeet model. Import an official ggml-org Parakeet .bin file.");
        return false;
    }
    g_model_path = path;
    return true;
}

bool parakeet_model_header(const std::string& path) {
    std::FILE* file = std::fopen(path.c_str(), "rb");
    if (!file) return false;
    char magic[4] = {};
    const size_t read = std::fread(magic, 1, sizeof(magic), file);
    std::fclose(file);
    // The GGML magic is stored as a little-endian 32-bit value, hence the
    // on-disk byte order is "lmgg".
    return read == sizeof(magic) && magic[0] == 'l' && magic[1] == 'm' && magic[2] == 'g' && magic[3] == 'g';
}

void on_progress(parakeet_context*, parakeet_state*, int progress, void*) {
    g_progress.store(std::clamp(progress, 0, 100));
}

bool should_abort(void*) {
    return g_cancelled.load();
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_local_voicenotes_inference_NativeParakeetBridge_isSupportedModel(
        JNIEnv* env, jobject, jstring model_path) {
    return parakeet_model_header(jstring_utf8(env, model_path)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_local_voicenotes_inference_NativeParakeetBridge_preloadNative(
        JNIEnv* env, jobject, jstring model_path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const std::string path = jstring_utf8(env, model_path);
    if (path.empty()) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Model path is empty.");
        return;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "Preloading Parakeet model");
    ensure_context_locked(env, path);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_local_voicenotes_inference_NativeParakeetBridge_transcribeNative(
        JNIEnv* env, jobject, jstring model_path, jfloatArray pcm_array, jint threads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_cancelled.store(false);
    g_progress.store(0);
    const std::string path = jstring_utf8(env, model_path);

    if (!ensure_context_locked(env, path)) return nullptr;

    const jsize count = env->GetArrayLength(pcm_array);
    jfloat* pcm = env->GetFloatArrayElements(pcm_array, nullptr);
    if (!pcm) return nullptr;

    parakeet_full_params params = parakeet_full_default_params(PARAKEET_SAMPLING_GREEDY);
    params.n_threads = std::clamp<int>(threads, 1, 8);
    params.progress_callback = on_progress;
    params.abort_callback = should_abort;

    __android_log_print(ANDROID_LOG_INFO, kTag, "Transcribing %d samples on %d threads", count, params.n_threads);
    int result = -1;
    try {
        result = parakeet_full(g_context, params, pcm, count);
    } catch (const std::exception& error) {
        env->ReleaseFloatArrayElements(pcm_array, pcm, JNI_ABORT);
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Vulkan inference exception: %s", error.what());
        throw_exception(env, "java/lang/IllegalStateException", error.what());
        return nullptr;
    } catch (...) {
        env->ReleaseFloatArrayElements(pcm_array, pcm, JNI_ABORT);
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Unknown Vulkan inference exception");
        throw_exception(env, "java/lang/IllegalStateException", "Unknown Vulkan inference failure.");
        return nullptr;
    }
    env->ReleaseFloatArrayElements(pcm_array, pcm, JNI_ABORT);

    if (g_cancelled.load()) return env->NewStringUTF("");
    if (result != 0) {
        throw_exception(env, "java/lang/IllegalStateException", "Parakeet transcription failed.");
        return nullptr;
    }

    std::string text;
    const int segments = parakeet_full_n_segments(g_context);
    for (int i = 0; i < segments; ++i) {
        const char* segment = parakeet_full_get_segment_text(g_context, i);
        if (!segment || !*segment) continue;
        if (!text.empty() && text.back() != ' ') text.push_back(' ');
        text += segment;
    }
    g_progress.store(100);
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_local_voicenotes_inference_NativeParakeetBridge_progressNative(JNIEnv*, jobject) {
    return g_progress.load();
}

extern "C" JNIEXPORT void JNICALL
Java_com_local_voicenotes_inference_NativeParakeetBridge_cancelNative(JNIEnv*, jobject) {
    g_cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_local_voicenotes_inference_NativeParakeetBridge_releaseNative(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    close_locked();
}
