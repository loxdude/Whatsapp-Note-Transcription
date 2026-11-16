package com.local.voicenotes.audio

import kotlin.math.*

/** Qwen/Whisper 16 kHz frontend: periodic Hann(400), hop 160, Slaney mel, log10. */
internal class QwenFeatureExtractor {
    private val window = FloatArray(400) { (0.5 - 0.5 * cos(2 * PI * it / 400)).toFloat() }
    private val cosines = FloatArray(201 * 400) { cos(2 * PI * (it / 400) * (it % 400) / 400).toFloat() }
    private val sines = FloatArray(201 * 400) { sin(2 * PI * (it / 400) * (it % 400) / 400).toFloat() }
    private fun mel(hz: Double) = if (hz < 1000) hz / (200.0 / 3) else 15 + ln(hz / 1000) / (ln(6.4) / 27)
    private fun hz(mel: Double) = if (mel < 15) mel * (200.0 / 3) else 1000 * exp((mel - 15) * ln(6.4) / 27)
    private val edges = DoubleArray(130) { hz(it * mel(8000.0) / 129) }
    private val filters = FloatArray(128 * 201) {
        val m = it / 201
        val frequency = (it % 201) * 40.0
        (max(0.0, min((frequency - edges[m]) / (edges[m+1] - edges[m]),
            (edges[m+2] - frequency) / (edges[m+2] - edges[m+1]))) * 2 / (edges[m+2] - edges[m])).toFloat()
    }

    fun extract(pcm: FloatArray, offset: Int, checkActive: () -> Unit): FloatArray {
        val samples = FloatArray(80000)
        pcm.copyInto(samples, startIndex = offset, endIndex = min(pcm.size, offset + 80000))
        val features = FloatArray(128 * 500)
        val frameSamples = FloatArray(400)
        val power = FloatArray(201)
        for (frame in 0 until 500) {
            if (frame % 20 == 0) checkActive()
            for (i in 0 until 400) {
                val raw = frame * 160 - 200 + i
                val source = when { raw < 0 -> -raw; raw >= samples.size -> 2*samples.size-2-raw; else -> raw }
                frameSamples[i] = samples[source] * window[i]
            }
            for (bin in 0..200) {
                var real = 0.0
                var imaginary = 0.0
                val base = bin * 400
                for (i in 0 until 400) {
                    real += frameSamples[i] * cosines[base+i]
                    imaginary += frameSamples[i] * sines[base+i]
                }
                power[bin] = (real*real + imaginary*imaginary).toFloat()
            }
            for (m in 0 until 128) {
                var energy = 0.0
                for (bin in 0..200) energy += filters[m*201+bin] * power[bin]
                features[m*500+frame] = log10(max(energy, 1e-10)).toFloat()
            }
        }
        val floor = features.max() - 8f
        for (i in features.indices) features[i] = (max(features[i], floor) + 4f) / 4f
        return features
    }
}
