package com.local.voicenotes.inference

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MistralPcmStagingTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun writesEquivalentLittleEndianWavAcrossFullAndPartialChunks() {
        val pcm = FloatArray(4097) { index ->
            floatArrayOf(-2f, -1f, -0.5f, 0f, 0.5f, 1f, 2f)[index % 7]
        }
        var checks = 0
        val source = MistralPcmStaging.stage(tmp.root, pcm) { checks++ }
        try {
            assertEquals("audio.wav", source.fileName)
            assertEquals("audio/wav", source.mimeType)
            assertTrue(source.temporary)
            val bytes = source.file.readBytes()
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", bytes.copyOfRange(0, 4).decodeToString())
            assertEquals(bytes.size - 8, header.getInt(4))
            assertEquals("WAVE", bytes.copyOfRange(8, 12).decodeToString())
            assertEquals("fmt ", bytes.copyOfRange(12, 16).decodeToString())
            assertEquals(16, header.getInt(16))
            assertEquals(1, header.getShort(20).toInt())
            assertEquals(1, header.getShort(22).toInt())
            assertEquals(16_000, header.getInt(24))
            assertEquals(32_000, header.getInt(28))
            assertEquals(2, header.getShort(32).toInt())
            assertEquals(16, header.getShort(34).toInt())
            assertEquals("data", bytes.copyOfRange(36, 40).decodeToString())
            assertEquals(pcm.size * 2, header.getInt(40))
            val expected = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { expected.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
            assertArrayEquals(expected.array(), bytes.copyOfRange(44, bytes.size))
            assertTrue("checks must cover chunk conversion and writes", checks >= 8)
        } finally {
            source.file.delete()
        }
    }
}
