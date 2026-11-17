// JNI shim around CrispASR's Cohere Transcribe backend (cohere.h). One handle owns one loaded
// model; calls on a handle are serialized by the Kotlin side (single inference thread).
#include <jni.h>

#include <android/log.h>

#include <cstdint>
#include <cstdlib>
#include <exception>
#include <new>
#include <string>
#include <vector>

#include "cohere.h"
#include "core/audio_chunking.h"
#include "core/ngram_loop_fix.h"

namespace {

constexpr const char* kTag = "VoiceNotesCohere";
constexpr int kSampleRate = 16000;
// Same windowing as cohere_transcribe_ex's own long-audio path: <= 30 s windows cut at the
// quietest 100 ms of their last 5 s. Doing it here lets the app report progress and stop
// between windows.
constexpr size_t kChunkSamples = 30 * kSampleRate;
constexpr size_t kSearchSamples = 5 * kSampleRate;
constexpr size_t kEnergyWindow = kSampleRate / 10;

struct Handle {
    cohere_context* ctx;
};

void throwIllegalState(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass error = env->FindClass("java/lang/IllegalStateException");
    if (error != nullptr) env->ThrowNew(error, message);
}

void throwOutOfMemory(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass error = env->FindClass("java/lang/OutOfMemoryError");
    if (error != nullptr) env->ThrowNew(error, message);
}

// Calls back into Kotlin; false means stop (cancelled or a pending Java exception).
bool keepGoing(JNIEnv* env, jobject control, jmethodID onChunk, int done, int total) {
    const bool go = env->CallBooleanMethod(control, onChunk, done, total);
    return go && !env->ExceptionCheck();
}

}  // namespace

// Returns a handle, or 0 if the model could not be loaded.
extern "C" JNIEXPORT jlong JNICALL
Java_com_local_voicenotes_inference_NativeCohere_open(JNIEnv* env, jobject, jstring modelPath, jint threads) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) return 0;
    const std::string file(path);
    env->ReleaseStringUTFChars(modelPath, path);
    try {
        cohere_context_params params = cohere_context_default_params();
        params.n_threads = threads > 0 ? threads : 4;
        params.use_gpu = false;
        params.use_flash = false;
        params.verbosity = 0;
        cohere_context* ctx = cohere_init_from_file(file.c_str(), params);
        if (ctx == nullptr) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "cohere_init_from_file failed");
            return 0;
        }
        auto* handle = new (std::nothrow) Handle{ctx};
        if (handle == nullptr) {
            cohere_free(ctx);
            throwOutOfMemory(env, "Not enough memory for the Arabic model.");
            return 0;
        }
        __android_log_print(ANDROID_LOG_INFO, kTag, "model loaded threads=%d", params.n_threads);
        return reinterpret_cast<jlong>(handle);
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env, "Not enough memory for the Arabic model.");
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "load failed: %s", error.what());
        throwIllegalState(env, "The Arabic model could not be loaded.");
    }
    return 0;
}

// Returns the transcript as UTF-8, or null when control.onChunk returned false (cancelled).
// Throws IllegalStateException when a window fails.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_local_voicenotes_inference_NativeCohere_transcribe(JNIEnv* env, jobject, jlong handlePtr, jfloatArray pcm,
                                                            jstring language, jobject control) {
    auto* handle = reinterpret_cast<Handle*>(handlePtr);
    if (handle == nullptr || handle->ctx == nullptr) {
        throwIllegalState(env, "The Arabic model is not loaded.");
        return nullptr;
    }
    jclass controlClass = env->GetObjectClass(control);
    jmethodID onChunk = env->GetMethodID(controlClass, "onChunk", "(II)Z");
    if (onChunk == nullptr) return nullptr;  // NoSuchMethodError is pending

    const char* lang = env->GetStringUTFChars(language, nullptr);
    if (lang == nullptr) return nullptr;
    const std::string code(lang);
    env->ReleaseStringUTFChars(language, lang);

    std::string text;
    try {
        const jsize n = env->GetArrayLength(pcm);
        std::vector<float> samples(static_cast<size_t>(n));
        env->GetFloatArrayRegion(pcm, 0, n, samples.data());

        const auto ranges = audio_chunking::split_at_energy_minima(samples.data(), samples.size(), kChunkSamples,
                                                                   kSearchSamples, kEnergyWindow);
        const int total = static_cast<int>(ranges.size());
        if (!keepGoing(env, control, onChunk, 0, total)) return nullptr;
        for (int i = 0; i < total; ++i) {
            const size_t begin = ranges[i].first;
            const size_t end = ranges[i].second;
            char* part =
                cohere_transcribe(handle->ctx, samples.data() + begin, static_cast<int>(end - begin), code.c_str());
            if (part == nullptr) {
                __android_log_print(ANDROID_LOG_ERROR, kTag, "window %d/%d failed", i + 1, total);
                throwIllegalState(env, "Arabic transcription failed.");
                return nullptr;
            }
            const std::string piece = core_ngram::fix_loops(part);
            free(part);
            const size_t start = piece.find_first_not_of(" \n");
            if (start != std::string::npos) {
                if (!text.empty()) text += ' ';
                text.append(piece, start, std::string::npos);
            }
            if (!keepGoing(env, control, onChunk, i + 1, total)) return nullptr;
        }
    } catch (const std::bad_alloc&) {
        throwOutOfMemory(env, "Not enough memory for Arabic transcription.");
        return nullptr;
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "transcribe failed: %s", error.what());
        throwIllegalState(env, "Arabic transcription failed.");
        return nullptr;
    }
    // Raw UTF-8 bytes: NewStringUTF expects modified UTF-8 and aborts under CheckJNI on byte
    // sequences a decoder might emit; Kotlin decodes leniently instead.
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    if (out == nullptr) return nullptr;
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_local_voicenotes_inference_NativeCohere_close(JNIEnv*, jobject, jlong handlePtr) {
    auto* handle = reinterpret_cast<Handle*>(handlePtr);
    if (handle == nullptr) return;
    if (handle->ctx != nullptr) cohere_free(handle->ctx);
    delete handle;
}
