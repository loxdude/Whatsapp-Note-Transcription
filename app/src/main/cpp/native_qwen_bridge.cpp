#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include "crispasr_session.h"

namespace {
std::mutex g_mutex;
crispasr_session* g_session = nullptr;
std::string g_model_path;
std::atomic<bool> g_cancelled{false};

std::string jstring_utf8(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

void close_locked() {
    if (g_session) {
        crispasr_session_close(g_session);
        g_session = nullptr;
        g_model_path.clear();
    }
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_local_voicenotes_inference_NativeQwenBridge_detectBackend(
        JNIEnv* env, jobject, jstring modelPath) {
    const auto path = jstring_utf8(env, modelPath);
    char backend[64] = {};
    const int rc = crispasr_detect_backend_from_gguf(path.c_str(), backend, sizeof(backend));
    if (rc < 0) return env->NewStringUTF("");
    return env->NewStringUTF(backend);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_local_voicenotes_inference_NativeQwenBridge_transcribeNative(
        JNIEnv* env, jobject, jstring modelPath, jfloatArray pcmArray,
        jstring language, jint threads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_cancelled.store(false);
    const auto path = jstring_utf8(env, modelPath);
    const auto lang = jstring_utf8(env, language);

    if (!g_session || g_model_path != path) {
        close_locked();
        g_session = crispasr_session_open_explicit(path.c_str(), "qwen3", std::clamp<int>(threads, 1, 8));
        if (!g_session) {
            jclass ex = env->FindClass("java/lang/IllegalStateException");
            env->ThrowNew(ex, "CrispASR could not load this Qwen3-ASR model");
            return nullptr;
        }
        g_model_path = path;
    }

    const jsize count = env->GetArrayLength(pcmArray);
    jfloat* pcm = env->GetFloatArrayElements(pcmArray, nullptr);
    if (!pcm) return nullptr;
    crispasr_reset_progress();
    crispasr_session_result* result = lang.empty() || lang == "auto"
        ? crispasr_session_transcribe(g_session, pcm, count)
        : crispasr_session_transcribe_lang(g_session, pcm, count, lang.c_str());
    env->ReleaseFloatArrayElements(pcmArray, pcm, JNI_ABORT);

    if (g_cancelled.load()) {
        if (result) crispasr_session_result_free(result);
        return env->NewStringUTF("");
    }
    if (!result) {
        jclass ex = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(ex, "Qwen3-ASR transcription failed");
        return nullptr;
    }

    std::string text;
    const int segments = crispasr_session_result_n_segments(result);
    for (int i = 0; i < segments; ++i) {
        const char* part = crispasr_session_result_segment_text(result, i);
        if (!part || !*part) continue;
        if (!text.empty() && text.back() != ' ') text.push_back(' ');
        text += part;
    }
    crispasr_session_result_free(result);
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_local_voicenotes_inference_NativeQwenBridge_progressNative(JNIEnv*, jobject) {
    return crispasr_get_progress();
}

extern "C" JNIEXPORT void JNICALL
Java_com_local_voicenotes_inference_NativeQwenBridge_cancelNative(JNIEnv*, jobject) {
    // CrispASR does not currently expose an interrupt-safe decoder abort.
    // Mark the request cancelled and discard its result at the JNI boundary.
    g_cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_local_voicenotes_inference_NativeQwenBridge_releaseNative(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    close_locked();
}
