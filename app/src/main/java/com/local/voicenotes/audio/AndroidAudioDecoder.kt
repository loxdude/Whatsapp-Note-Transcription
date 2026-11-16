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

class AndroidAudioDecoder(private val resolver: ContentResolver) {
    companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        const val MAX_DURATION_US = 15L * 60L * 1_000_000L
        private const val TIMEOUT_US = 10_000L
        /** Hard cap on decoded 16 kHz samples (15 minutes, 14.4M floats). */
        internal const val MAX_OUTPUT_SAMPLES = TARGET_SAMPLE_RATE * 60 * 15
        /** Presize slack over the container duration (~0.26 s) for codec priming/rounding. */
        private const val PRESIZE_SLACK_SAMPLES = 4_096
        /** Initial capacity when the duration is unknown (grows 1.5x up to the cap). */
        private const val UNKNOWN_DURATION_SAMPLES = TARGET_SAMPLE_RATE * 60

        internal fun initialCapacityFor(durationUs: Long): Int = if (durationUs > 0) {
            val estimate = (durationUs * TARGET_SAMPLE_RATE + 500_000L) / 1_000_000L + PRESIZE_SLACK_SAMPLES
            estimate.coerceIn(1L, MAX_OUTPUT_SAMPLES.toLong()).toInt()
        } else UNKNOWN_DURATION_SAMPLES
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
            val output = FloatAccumulator(initialCapacityFor(durationUs), MAX_OUTPUT_SAMPLES)
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
        var pcmFormat = PcmSampleFormat.PCM_16BIT
        var resampler: StreamingResampler? = null
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
                    pcmFormat = PcmSampleFormat.fromAndroidEncoding(format.intOrNull(MediaFormat.KEY_PCM_ENCODING))
                    require(sampleRate > 0 && channels > 0) { "Decoder returned invalid PCM metadata." }
                    val current = resampler
                    if (current == null || current.inRate != sampleRate) {
                        // Rate change mid-stream: finish the old stream before switching.
                        current?.let { output.append(it.flush()) }
                        resampler = StreamingResampler(sampleRate, TARGET_SAMPLE_RATE)
                    }
                }
                MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (index >= 0) {
                    if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(index) ?: error("Decoder output buffer unavailable.")
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.order(ByteOrder.nativeOrder())
                        val active = resampler ?: error("Decoder returned PCM before its output format.")
                        val mono = PcmDownmix.toMono(buffer, pcmFormat, channels)
                        output.append(active.process(mono))
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
        resampler?.let { output.append(it.flush()) }
    }

    private fun MediaFormat.longOrNull(key: String) = runCatching { getLong(key) }.getOrNull()
    private fun MediaFormat.intOrNull(key: String) = runCatching { getInteger(key) }.getOrNull()
}
