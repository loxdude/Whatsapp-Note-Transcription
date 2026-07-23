package com.local.voicenotes.inference

internal object NativeParakeetBridge {
    init { System.loadLibrary("voicenote_jni") }

    external fun isSupportedModel(modelPath: String): Boolean
    external fun preloadNative(modelPath: String)
    external fun transcribeNative(modelPath: String, pcm: FloatArray, threads: Int): String
    external fun progressNative(): Int
    external fun cancelNative()
    external fun releaseNative()
}
