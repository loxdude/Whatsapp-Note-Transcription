package com.local.voicenotes.audio

import com.local.voicenotes.inference.ParakeetAssets
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * NeMo/Parakeet frontend for the fixed five-second LiteRT encoder:
 * 16 kHz PCM -> [1, 128, 500] normalized log-mel features.
 */
internal class ParakeetFeatureExtractor(private val assets: ParakeetAssets) {
    companion object {
        const val CHUNK_SAMPLES = 80_000
        const val MEL_BINS = 128
        const val FRAMES = 500
        private const val FFT_SIZE = 512
        private const val HOP = 160
        private const val CENTER_PAD = FFT_SIZE / 2
        private const val LOG_EPSILON = 5.9604645e-8f // 2^-24
        private const val NORM_EPSILON = 1e-5f
    }

    private val real = FloatArray(FFT_SIZE)
    private val imag = FloatArray(FFT_SIZE)
    private val power = FloatArray(FFT_SIZE / 2 + 1)

    fun extract(pcm: FloatArray, offset: Int): FloatArray {
        val emphasized = FloatArray(CHUNK_SAMPLES)
        val available = (pcm.size - offset).coerceIn(0, CHUNK_SAMPLES)
        for (i in 0 until available) {
            val current = pcm[offset + i]
            val previous = if (i == 0) 0f else pcm[offset + i - 1]
            emphasized[i] = current - 0.97f * previous
        }

        val features = FloatArray(MEL_BINS * FRAMES)
        for (frame in 0 until FRAMES) {
            real.fill(0f)
            imag.fill(0f)
            val sourceStart = frame * HOP - CENTER_PAD + (FFT_SIZE - assets.window.size) / 2
            for (i in assets.window.indices) {
                val source = sourceStart + i
                if (source in emphasized.indices) real[i + 56] = emphasized[source] * assets.window[i]
            }
            fft(real, imag)
            for (bin in power.indices) power[bin] = real[bin] * real[bin] + imag[bin] * imag[bin]
            for (mel in 0 until MEL_BINS) {
                var sum = 0f
                val filterOffset = mel * power.size
                for (bin in power.indices) sum += assets.melFilterbank[filterOffset + bin] * power[bin]
                features[mel * FRAMES + frame] = ln((sum + LOG_EPSILON).toDouble()).toFloat()
            }
        }

        // NeMo normalizes each mel channel across time with sample variance.
        for (mel in 0 until MEL_BINS) {
            val base = mel * FRAMES
            var mean = 0.0
            for (frame in 0 until FRAMES) mean += features[base + frame]
            mean /= FRAMES
            var squared = 0.0
            for (frame in 0 until FRAMES) {
                val delta = features[base + frame] - mean
                squared += delta * delta
            }
            val std = sqrt(squared / (FRAMES - 1)).toFloat() + NORM_EPSILON
            for (frame in 0 until FRAMES) features[base + frame] =
                ((features[base + frame] - mean) / std).toFloat()
        }
        return features
    }

    private fun fft(real: FloatArray, imag: FloatArray) {
        var j = 0
        for (i in 1 until FFT_SIZE) {
            var bit = FFT_SIZE shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
                val ti = imag[i]
                imag[i] = imag[j]
                imag[j] = ti
            }
        }
        var length = 2
        while (length <= FFT_SIZE) {
            val angle = -2.0 * Math.PI / length
            val wLenReal = cos(angle).toFloat()
            val wLenImag = sin(angle).toFloat()
            for (start in 0 until FFT_SIZE step length) {
                var wReal = 1f
                var wImag = 0f
                for (k in 0 until length / 2) {
                    val even = start + k
                    val odd = even + length / 2
                    val oddReal = real[odd] * wReal - imag[odd] * wImag
                    val oddImag = real[odd] * wImag + imag[odd] * wReal
                    real[odd] = real[even] - oddReal
                    imag[odd] = imag[even] - oddImag
                    real[even] += oddReal
                    imag[even] += oddImag
                    val nextReal = wReal * wLenReal - wImag * wLenImag
                    wImag = wReal * wLenImag + wImag * wLenReal
                    wReal = nextReal
                }
            }
            length = length shl 1
        }
    }
}
