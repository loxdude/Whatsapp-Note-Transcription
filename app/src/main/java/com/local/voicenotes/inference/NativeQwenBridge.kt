package com.local.voicenotes.inference

internal object NativeQwenBridge {
    init { System.loadLibrary("voicenote_jni") }

    external fun detectBackend(modelPath: String): String
    external fun transcribeNative(modelPath: String, pcm: FloatArray, language: String, threads: Int): String
    external fun progressNative(): Int
    external fun cancelNative()
    external fun releaseNative()
}

