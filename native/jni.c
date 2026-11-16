#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "decoder.h"
#define JNI_METHOD(name) Java_com_local_voicenotes_audio_NativeOpus_##name

typedef struct {
    VnDecoder *decoder;
    JNIEnv *env; /* Set for each synchronous operation; never used across threads. */
    jobject control;
    jmethodID is_cancelled;
} JniDecoder;

static int cancelled(void *opaque) {
    JniDecoder *d = opaque;
    JNIEnv *env = d->env;
    if ((*env)->ExceptionCheck(env)) return 1;
    jboolean result = (*env)->CallBooleanMethod(env, d->control, d->is_cancelled);
    return (*env)->ExceptionCheck(env) || result == JNI_TRUE;
}
JNIEXPORT jlong JNICALL JNI_METHOD(open)(JNIEnv *env, jobject self, jint fd, jlong offset, jlong length, jobject control, jintArray status) {
    (void)self;
    if (!control || !status || (*env)->GetArrayLength(env, status) < 1) return 0;
    JniDecoder *d = calloc(1, sizeof(*d));
    if (!d) { jint code = -129; (*env)->SetIntArrayRegion(env, status, 0, 1, &code); return 0; }
    d->env = env;
    d->control = (*env)->NewGlobalRef(env, control);
    if (!d->control) { free(d); return 0; }
    jclass type = (*env)->GetObjectClass(env, control);
    if (type) { d->is_cancelled = (*env)->GetMethodID(env, type, "isCancelled", "()Z"); (*env)->DeleteLocalRef(env, type); }
    if (!d->control || !d->is_cancelled || (*env)->ExceptionCheck(env)) {
        if (d->control) (*env)->DeleteGlobalRef(env, d->control);
        free(d); return 0;
    }
    int error = 0;
    d->decoder = vn_open_controlled(fd, offset, length, &error, cancelled, d);
    if (!d->decoder) {
        (*env)->DeleteGlobalRef(env, d->control); free(d);
        jint code = error < 0 ? error : -131;
        (*env)->SetIntArrayRegion(env, status, 0, 1, &code);
        return 0;
    }
    return (jlong)(intptr_t)d;
}
JNIEXPORT jint JNICALL JNI_METHOD(read)(JNIEnv *env, jobject self, jlong handle, jfloatArray pcm) {
    (void)self;
    if (handle == 0 || !pcm) return -131;
    jsize capacity = (*env)->GetArrayLength(env, pcm);
    if (capacity < 1 || capacity > 5760) return -131;
    JniDecoder *d = (JniDecoder *)(intptr_t)handle;
    d->env = env;
    float buffer[5760];
    int n = vn_read(d->decoder, buffer, capacity);
    if (n > 0) (*env)->SetFloatArrayRegion(env, pcm, 0, n, buffer);
    return n;
}
JNIEXPORT jlong JNICALL JNI_METHOD(total)(JNIEnv *env, jobject self, jlong handle) {
    (void)env; (void)self;
    return handle != 0 ? vn_total(((JniDecoder *)(intptr_t)handle)->decoder) : -1;
}
JNIEXPORT void JNICALL JNI_METHOD(close)(JNIEnv *env, jobject self, jlong handle) {
    (void)self;
    if (handle == 0) return;
    JniDecoder *d = (JniDecoder *)(intptr_t)handle;
    vn_close(d->decoder);
    (*env)->DeleteGlobalRef(env, d->control);
    free(d);
}
