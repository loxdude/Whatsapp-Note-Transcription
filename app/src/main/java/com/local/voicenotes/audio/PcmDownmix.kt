package com.local.voicenotes.audio

import java.nio.ByteBuffer

/** PCM sample formats the decoder accepts from MediaCodec. */
enum class PcmSampleFormat(val bytesPerSample: Int) {
    PCM_16BIT(2),
    PCM_FLOAT(4);

    companion object {
        // Values of android.media.AudioFormat.ENCODING_PCM_16BIT / ENCODING_PCM_FLOAT
        // (duplicated so this file stays free of Android dependencies for JVM tests).
        const val ANDROID_ENCODING_PCM_16BIT = 2
        const val ANDROID_ENCODING_PCM_FLOAT = 4

        /** Maps an Android PCM encoding; a missing key means 16-bit per the MediaCodec contract. */
        fun fromAndroidEncoding(encoding: Int?): PcmSampleFormat = when (encoding) {
            null, ANDROID_ENCODING_PCM_16BIT -> PCM_16BIT
            ANDROID_ENCODING_PCM_FLOAT -> PCM_FLOAT
            else -> throw IllegalArgumentException(
                "Unsupported decoder PCM encoding $encoding; only 16-bit and float PCM are supported."
            )
        }
    }
}

internal object PcmDownmix {
    /**
     * Reads interleaved PCM from [buffer] (position..limit, byte order already set) and returns
     * mono samples by averaging channels. Trailing partial frames are ignored.
     */
    fun toMono(buffer: ByteBuffer, format: PcmSampleFormat, channels: Int): FloatArray {
        require(channels > 0) { "Invalid channel count $channels" }
        val frames = buffer.remaining() / format.bytesPerSample / channels
        val mono = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += when (format) {
                    PcmSampleFormat.PCM_FLOAT -> buffer.float.coerceIn(-1f, 1f)
                    PcmSampleFormat.PCM_16BIT -> buffer.short / 32768f
                }
            }
            mono[frame] = sum / channels
        }
        return mono
    }
}
