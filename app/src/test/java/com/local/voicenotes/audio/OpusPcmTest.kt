package com.local.voicenotes.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class OpusPcmTest {
    private class Source(
        private val samples: FloatArray,
        override val totalFrames: Long = samples.size.toLong(),
        private val chunk: Int = 960
    ) : OpusPcmSource {
        var position = 0
        override fun read(target: FloatArray): Int {
            val n = minOf(chunk, target.size, samples.size - position)
            samples.copyInto(target, 0, position, position + n)
            position += n
            return n
        }
        override fun close() = Unit
    }

    @Test fun `resampling matches existing pipeline independent of Opus packet boundaries`() {
        val samples = FloatArray(48_013) { kotlin.math.sin(it * 0.1).toFloat() * 0.3f }
        val resampler = StreamingResampler(48_000, 16_000)
        val expected = resampler.process(samples) + resampler.flush()
        val actual = OpusPcm.decode(Source(samples), {}, {})
        assertArrayEquals(expected, actual, 0f)
    }

    @Test fun `oversize header fails before any audio is read`() {
        val source = Source(FloatArray(1), 48_000L * 901)
        try { OpusPcm.decode(source, {}, {}); fail("accepted too-long audio") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("15-minute")) }
        assertEquals(0, source.position)
    }

    @Test fun `unknown length is capped during decoding`() {
        val source = Source(FloatArray(101), -1, 50)
        try { OpusPcm.decode(source, {}, {}, maxInputFrames = 100); fail("accepted too many samples") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("15-minute")) }
    }

    @Test fun `cancellation is not swallowed as native fallback`() {
        val source = Source(FloatArray(48_000))
        var checks = 0
        try {
            OpusPcm.decode(source, {}, { if (++checks == 3) throw CancellationException() })
            fail("ignored cancellation")
        } catch (expected: CancellationException) { assertTrue(source.position < 48_000) }
    }

    @Test fun `native error requests fallback instead of partial output`() {
        val source = object : OpusPcmSource {
            override val totalFrames = -1L
            override fun read(target: FloatArray) = -3
            override fun close() = Unit
        }
        try { OpusPcm.decode(source, {}, {}); fail("accepted corrupt stream") }
        catch (expected: NativeOpusException) { assertTrue(expected.message!!.contains("-3")) }
    }

    @Test fun `native cancellation and parser budgets never request Android fallback`() {
        for (code in listOf(-10001, -10002)) {
            try {
                OpusPcm.checkNativeFailure(code) {}
                fail("ignored native safety signal")
            } catch (expected: NativeOpusException) {
                fail("safety signal incorrectly requested Android fallback")
            } catch (expected: CancellationException) {
                assertEquals(-10002, code)
            } catch (expected: IllegalArgumentException) {
                assertEquals(-10001, code)
            }
        }
    }

    @Test fun `cancelled job takes precedence over a native parse error`() {
        try {
            OpusPcm.checkNativeFailure(-3) { throw CancellationException() }
            fail("ignored cancellation")
        } catch (expected: CancellationException) { }
    }

    @Test fun `known length uses exact rounded capacity and metadata is read once`() {
        assertEquals(1, OpusPcm.capacityForFrames(1))
        assertEquals(1, OpusPcm.capacityForFrames(4))
        assertEquals(2, OpusPcm.capacityForFrames(5))
        assertEquals(16_000, OpusPcm.capacityForFrames(48_000))
        assertEquals(16_000 * 60 * 15, OpusPcm.capacityForFrames(48_000L * 60 * 15))
        var reads = 0
        val source = object : OpusPcmSource {
            private val data = Source(FloatArray(48_000))
            override val totalFrames: Long get() { reads++; return 48_000 }
            override fun read(target: FloatArray) = data.read(target)
            override fun close() = Unit
        }
        assertEquals(16_000, OpusPcm.decode(source, {}, {}).size)
        assertEquals(1, reads)
    }

    @Test fun `progress emits meaningful intermediate updates on a controlled clock`() {
        val progress = mutableListOf<Float>()
        var now = 0L
        OpusPcm.decode(Source(FloatArray(48_000)), { progress += it }, {}, nowNanos = { now += 120_000_000; now })
        assertTrue(progress.size > 2)
        assertTrue(progress.first() > 0f && progress.first() < 1f)
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
        assertEquals(1f, progress.last(), 0f)
    }

    @Test fun `progress completes even with unknown length`() {
        for (total in listOf(-1L, 48_000L)) {
            val progress = mutableListOf<Float>()
            OpusPcm.decode(Source(FloatArray(48_000), total), { progress += it }, {})
            assertEquals(1f, progress.last(), 0f)
            assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
            assertTrue(progress.all { it in 0f..1f })
        }
    }
}
