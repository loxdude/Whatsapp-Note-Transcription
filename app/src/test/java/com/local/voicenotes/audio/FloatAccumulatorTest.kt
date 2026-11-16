package com.local.voicenotes.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FloatAccumulatorTest {
    @Test
    fun toArrayReturnsBackingArrayWhenExactlyFull() {
        val acc = FloatAccumulator(6, 100)
        acc.append(floatArrayOf(1f, 2f, 3f))
        acc.append(floatArrayOf(4f, 5f, 6f))
        val a = acc.toArray()
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), a, 0f)
        assertSame(a, acc.toArray())
    }

    @Test
    fun toArrayTrimsWhenNotFull() {
        val acc = FloatAccumulator(10, 100)
        acc.append(floatArrayOf(1f, 2f))
        val a = acc.toArray()
        assertArrayEquals(floatArrayOf(1f, 2f), a, 0f)
        assertNotSame(a, acc.toArray())
    }

    @Test
    fun growsBeyondInitialCapacityUpToCap() {
        val acc = FloatAccumulator(1, 1000)
        val chunk = FloatArray(7) { it.toFloat() }
        repeat(142) { acc.append(chunk) }
        assertEquals(994, acc.size)
        assertTrue(acc.capacity <= 1000)
        val a = acc.toArray()
        assertEquals(994, a.size)
        for (i in a.indices) assertEquals((i % 7).toFloat(), a[i], 0f)
    }

    @Test
    fun capIsEnforced() {
        val acc = FloatAccumulator(4, 10)
        acc.append(FloatArray(10))
        assertEquals(10, acc.capacity)
        try {
            acc.append(FloatArray(1))
            fail("expected cap violation")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("15-minute"))
        }
    }

    @Test
    fun initialCapacityIsClampedToCap() {
        assertEquals(5, FloatAccumulator(1_000, 5).capacity)
        assertEquals(1, FloatAccumulator(0, 5).capacity)
    }

    @Test
    fun presizeFromKnownDurationAvoidsGrowthAndClampsToCap() {
        val oneMinuteUs = 60L * 1_000_000L
        val cap = AndroidAudioDecoder.initialCapacityFor(oneMinuteUs)
        assertTrue(cap >= 16_000 * 60 && cap <= 16_000 * 60 + 8_192)
        assertEquals(
            AndroidAudioDecoder.MAX_OUTPUT_SAMPLES,
            AndroidAudioDecoder.initialCapacityFor(AndroidAudioDecoder.MAX_DURATION_US)
        )
        assertEquals(14_400_000, AndroidAudioDecoder.MAX_OUTPUT_SAMPLES)
        assertEquals(16_000 * 60, AndroidAudioDecoder.initialCapacityFor(0))
    }
}

class PcmDownmixTest {
    @Test
    fun mapsSupportedEncodingsAndRejectsOthers() {
        assertEquals(PcmSampleFormat.PCM_16BIT, PcmSampleFormat.fromAndroidEncoding(null))
        assertEquals(PcmSampleFormat.PCM_16BIT, PcmSampleFormat.fromAndroidEncoding(2))
        assertEquals(PcmSampleFormat.PCM_FLOAT, PcmSampleFormat.fromAndroidEncoding(4))
        for (bad in intArrayOf(3 /* 8-bit */, 21 /* 24-bit packed */, 22 /* 32-bit int */, 1, 0)) {
            try {
                PcmSampleFormat.fromAndroidEncoding(bad)
                fail("encoding $bad should be rejected")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("Unsupported decoder PCM encoding $bad"))
            }
        }
    }

    @Test
    fun stereo16BitIsAveraged() {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(16384).putShort(0).putShort(-32768).putShort(-32768).flip()
        assertArrayEquals(floatArrayOf(0.25f, -1f), PcmDownmix.toMono(buf, PcmSampleFormat.PCM_16BIT, 2), 0f)
    }

    @Test
    fun floatIsClampedAndAveraged() {
        val buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        buf.putFloat(2f).putFloat(0f).putFloat(-0.5f).flip()
        assertArrayEquals(floatArrayOf(1f, 0f, -0.5f), PcmDownmix.toMono(buf, PcmSampleFormat.PCM_FLOAT, 1), 0f)
    }
}
