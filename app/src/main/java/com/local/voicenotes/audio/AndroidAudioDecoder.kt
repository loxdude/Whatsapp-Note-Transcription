package com.local.voicenotes.audio

import android.content.ContentResolver
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

class AndroidAudioDecoder(private val resolver: ContentResolver) {
    companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        const val MAX_DURATION_US = 15L * 60L * 1_000_000L
        private const val TIMEOUT_US = 10_000L
    }

    suspend fun decode(uri: Uri, onProgress: (Float) -> Unit): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        val descriptor = resolver.openAssetFileDescriptor(uri, "r")
            ?: error("The selected audio file cannot be opened.")
        try {
            extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("The selected file has no supported audio track.")
            extractor.selectTrack(track)
            val sourceFormat = extractor.getTrackFormat(track)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME) ?: error("Audio format is missing.")
            val durationUs = sourceFormat.longOrNull(MediaFormat.KEY_DURATION) ?: descriptor.declaredLength
                .takeIf { it > 0 }?.let { 0L } ?: 0L
            require(durationUs <= 0L || durationUs <= MAX_DURATION_US) {
                "Audio is longer than the 15-minute limit."
            }
            val expectedSamples = if (durationUs > 0) {
                ((durationUs * TARGET_SAMPLE_RATE / 1_000_000L) + TARGET_SAMPLE_RATE).toInt()
            } else TARGET_SAMPLE_RATE * 60
            val output = FloatAccumulator(expectedSamples, TARGET_SAMPLE_RATE * 60 * 15)
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(sourceFormat, null, null, 0)
                codec.start()
                decodeLoop(codec, extractor, durationUs, output, onProgress)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            onProgress(1f)
            output.toArray()
        } finally {
            extractor.release()
            descriptor.close()
        }
    }

    private suspend fun decodeLoop(
        codec: MediaCodec,
        extractor: MediaExtractor,
        durationUs: Long,
        output: FloatAccumulator,
        onProgress: (Float) -> Unit
    ) {
        var inputEnded = false
        var outputEnded = false
        var sampleRate = 0
        var channels = 0
        var pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT
        val info = MediaCodec.BufferInfo()
        while (!outputEnded) {
            coroutineContext.ensureActive()
            if (!inputEnded) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index) ?: error("Decoder input buffer unavailable.")
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            when (val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmEncoding = format.intOrNull(MediaFormat.KEY_PCM_ENCODING)
                        ?: android.media.AudioFormat.ENCODING_PCM_16BIT
                }
                MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (index >= 0) {
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(index) ?: error("Decoder output buffer unavailable.")
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.order(ByteOrder.nativeOrder())
                        require(sampleRate > 0 && channels > 0) { "Decoder returned invalid PCM metadata." }
                        appendResampled(buffer, pcmEncoding, channels, sampleRate, output)
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    private fun appendResampled(
        buffer: java.nio.ByteBuffer,
        encoding: Int,
        channels: Int,
        sourceRate: Int,
        output: FloatAccumulator
    ) {
        val bytesPerSample = if (encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val frames = buffer.remaining() / bytesPerSample / channels
        if (frames <= 0) return
        val mono = FloatArray(frames)
        repeat(frames) { frame ->
            var sum = 0f
            repeat(channels) {
                sum += if (encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                    buffer.float.coerceIn(-1f, 1f)
                } else {
                    buffer.short / 32768f
                }
            }
            mono[frame] = sum / channels
        }
        if (sourceRate == TARGET_SAMPLE_RATE) {
            output.append(mono)
            return
        }
        val targetFrames = (frames.toDouble() * TARGET_SAMPLE_RATE / sourceRate).roundToInt().coerceAtLeast(1)
        val resampled = FloatArray(targetFrames)
        val scale = (frames - 1).toDouble() / (targetFrames - 1).coerceAtLeast(1)
        repeat(targetFrames) { i ->
            val position = i * scale
            val left = position.toInt().coerceAtMost(frames - 1)
            val right = (left + 1).coerceAtMost(frames - 1)
            val fraction = (position - left).toFloat()
            resampled[i] = mono[left] + (mono[right] - mono[left]) * fraction
        }
        output.append(resampled)
    }

    private fun MediaFormat.longOrNull(key: String) = runCatching { getLong(key) }.getOrNull()
    private fun MediaFormat.intOrNull(key: String) = runCatching { getInteger(key) }.getOrNull()
}

internal class FloatAccumulator(initialCapacity: Int, private val maxSize: Int) {
    private var values = FloatArray(initialCapacity.coerceIn(1, maxSize))
    private var size = 0

    fun append(chunk: FloatArray) {
        require(size + chunk.size <= maxSize) { "Decoded audio exceeds the 15-minute limit." }
        if (size + chunk.size > values.size) {
            var next = values.size
            while (next < size + chunk.size) next = (next * 2).coerceAtMost(maxSize)
            values = values.copyOf(next)
        }
        chunk.copyInto(values, size)
        size += chunk.size
    }

    fun toArray(): FloatArray = values.copyOf(size)
}

