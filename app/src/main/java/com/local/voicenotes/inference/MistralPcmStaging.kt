package com.local.voicenotes.inference

import java.io.File
import java.io.FileOutputStream

/** PCM compatibility staging; no Android or network dependencies. */
internal object MistralPcmStaging {
    private const val CHUNK_SAMPLES = 4096

    fun stage(cacheDir: File, pcm: FloatArray, ensureActive: () -> Unit): MistralStagedAudio {
        ensureActive()
        // Check in Long before multiplication/allocation; never encode an oversized input.
        val dataSize = pcm.size.toLong() * 2
        MistralUploadStaging.checkDeclaredSize(dataSize + 44)
        val file = File.createTempFile(MistralUploadStaging.STAGING_PREFIX, ".wav", cacheDir)
        try {
            FileOutputStream(file).use { output ->
                ensureActive()
                writeWavHeader(output, dataSize.toInt())
                val buffer = ByteArray(CHUNK_SAMPLES * 2)
                var offset = 0
                while (offset < pcm.size) {
                    ensureActive()
                    val count = minOf(CHUNK_SAMPLES, pcm.size - offset)
                    for (index in 0 until count) {
                        val value = (pcm[offset + index].coerceIn(-1f, 1f) * 32767).toInt()
                        buffer[index * 2] = value.toByte()
                        buffer[index * 2 + 1] = (value shr 8).toByte()
                    }
                    ensureActive()
                    output.write(buffer, 0, count * 2)
                    ensureActive()
                    offset += count
                }
            }
            ensureActive()
            return MistralStagedAudio(file, "audio.wav", "audio/wav", true)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    private fun writeWavHeader(output: FileOutputStream, dataSize: Int) {
        val sampleRate = 16_000
        val bitsPerSample = 16
        val channels = 1
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        writeIntLe(header, 4, dataSize + 36)
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        writeIntLe(header, 16, 16)
        writeShortLe(header, 20, 1)
        writeShortLe(header, 22, channels)
        writeIntLe(header, 24, sampleRate)
        writeIntLe(header, 28, byteRate)
        writeShortLe(header, 32, blockAlign)
        writeShortLe(header, 34, bitsPerSample)
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        writeIntLe(header, 40, dataSize)
        output.write(header)
    }

    private fun writeIntLe(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { index -> bytes[offset + index] = (value shr (index * 8)).toByte() }
    }

    private fun writeShortLe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value shr 8).toByte()
    }

}
