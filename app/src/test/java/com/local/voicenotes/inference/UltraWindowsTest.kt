package com.local.voicenotes.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraWindowsTest {
    private fun spans(plan: List<UltraWindow>) = plan.map { Triple(it.startSample, it.lengthSamples, it.keepFromSeconds to it.keepUntilSeconds) }

    @Test fun windowAndHopLengthsFollowTheModelCard() {
        assertEquals(479_840, UltraWindows.windowSamples(30))
        assertEquals(320_000, UltraWindows.windowSamples(20))
        assertEquals(160_000, UltraWindows.windowSamples(10))
        assertEquals(448_000, UltraWindows.hopSamples(30)) // 28 s
        assertEquals(288_000, UltraWindows.hopSamples(20)) // 18 s
        assertEquals(128_000, UltraWindows.hopSamples(10)) // 8 s
        assertEquals(480_000, UltraWindows.graphSamples(30))
        assertThrows(IllegalArgumentException::class.java) { UltraWindows.windowSamples(15) }
    }

    @Test fun audioUpToOneWindowIsOnePaddedWindowKeepingEverything() {
        for ((samples, seconds) in listOf(1 to 30, 100_000 to 30, 479_840 to 30, 160_000 to 10, 5_000 to 10)) {
            val plan = UltraWindows.plan(samples, seconds)
            assertEquals(1, plan.size)
            assertEquals(0, plan[0].startSample)
            assertEquals(samples, plan[0].lengthSamples)
            assertEquals(0.0, plan[0].keepFromSeconds, 0.0)
            assertEquals(Double.POSITIVE_INFINITY, plan[0].keepUntilSeconds, 0.0)
            assertTrue(plan[0].keeps(0) && plan[0].keeps(10_000))
        }
    }

    // Expected values computed with the reference ultra.py transcribe(win_s, hop_s = win_s - 2).
    @Test fun longerAudioMatchesTheReferencePlan() {
        assertEquals(
            listOf(
                Triple(0, 479_840, 0.0 to 28.994999999999997),
                Triple(448_000, 479_840, 28.995 to 56.995),
                Triple(896_000, 104_000, 56.995 to Double.POSITIVE_INFINITY)
            ),
            spans(UltraWindows.plan(1_000_000, 30))
        )
        assertEquals(
            listOf(Triple(0, 479_840, 0.0 to 28.994999999999997), Triple(448_000, 31_841, 28.995 to Double.POSITIVE_INFINITY)),
            spans(UltraWindows.plan(479_841, 30))
        )
        assertEquals(
            listOf(Triple(0, 160_000, 0.0 to 9.0), Triple(128_000, 32_001, 9.0 to Double.POSITIVE_INFINITY)),
            spans(UltraWindows.plan(160_001, 10))
        )
        assertEquals(
            listOf(
                Triple(0, 160_000, 0.0 to 9.0),
                Triple(128_000, 160_000, 9.0 to 17.0),
                Triple(256_000, 144_000, 17.0 to Double.POSITIVE_INFINITY)
            ),
            spans(UltraWindows.plan(400_000, 10))
        )
        assertEquals(
            listOf(
                Triple(0, 320_000, 0.0 to 19.0),
                Triple(288_000, 320_000, 19.0 to 37.0),
                Triple(576_000, 320_000, 37.0 to 55.0),
                Triple(864_000, 36_000, 55.0 to Double.POSITIVE_INFINITY)
            ),
            spans(UltraWindows.plan(900_000, 20))
        )
    }

    @Test fun lastWindowReachesTheEndAndEveryWindowFitsItsGraph() {
        for (seconds in UltraWindows.SUPPORTED_SECONDS) {
            val window = UltraWindows.windowSamples(seconds)
            for (samples in listOf(window + 1, 2 * window, 15 * 60 * 16_000, 3 * window - 7)) {
                val plan = UltraWindows.plan(samples, seconds)
                val last = plan.last()
                assertEquals(samples, last.startSample + last.lengthSamples)
                assertTrue(plan.all { it.lengthSamples in 1..window })
                // The last window starts only because the previous one did not reach the end.
                assertTrue(plan[plan.size - 2].startSample + window < samples)
                // The last window holds more than the overlap (~2 s), so it is never tiny.
                assertTrue(last.lengthSamples > window - UltraWindows.hopSamples(seconds))
                plan.zipWithNext().forEach { (a, b) ->
                    assertEquals(UltraWindows.hopSamples(seconds), b.startSample - a.startSample)
                    assertEquals(a.keepUntilSeconds, b.keepFromSeconds, 1e-9)
                }
            }
        }
    }

    /** Synthetic speech: one token every [every] frames of absolute time, id = absolute frame index. */
    private fun timeline(plan: List<UltraWindow>, totalFrames: Int, every: Int): List<List<UltraEmission>> =
        plan.map { window ->
            val firstFrame = window.startSample / 1_280 // 80 ms = 1,280 samples
            val frames = (window.lengthSamples + 1_279) / 1_280
            (0 until frames).filter { (firstFrame + it) % every == 0 && firstFrame + it < totalFrames }
                .map { UltraEmission(it, firstFrame + it) }
        }

    @Test fun stitchingKeepsEveryTokenExactlyOnceAcrossBoundaries() {
        for (seconds in UltraWindows.SUPPORTED_SECONDS) {
            for (samples in listOf(1_000_000, UltraWindows.windowSamples(seconds) + 1, 3_200_000)) {
                val plan = UltraWindows.plan(samples, seconds)
                val totalFrames = samples / 1_280
                for (every in listOf(1, 3, 7)) {
                    val tokens = UltraWindows.stitch(plan, timeline(plan, totalFrames, every))
                    val expected = (0 until totalFrames).filter { it % every == 0 }
                    assertEquals("$seconds s, $samples samples, every $every", expected, tokens)
                }
            }
        }
    }

    @Test fun stitchingUsesTheMiddleOfTheOverlap() {
        val plan = UltraWindows.plan(400_000, 10) // windows at 0 s, 8 s, 16 s; cuts at 9 s and 17 s
        // A word at 8.96 s (frame 112 of window 0, frame 12 of window 1) belongs to window 0.
        assertTrue(plan[0].keeps(112))
        assertFalse(plan[1].keeps(12))
        // At 9.04 s it belongs to window 1.
        assertFalse(plan[0].keeps(113))
        assertTrue(plan[1].keeps(13))
        val emissions = listOf(
            listOf(UltraEmission(112, 1), UltraEmission(113, 99)), // 8.96 s kept, 9.04 s dropped
            listOf(UltraEmission(12, 98), UltraEmission(13, 2), UltraEmission(100, 3), UltraEmission(113, 97)),
            listOf(UltraEmission(12, 96), UltraEmission(13, 4), UltraEmission(1_000, 5)) // 16.96 s dropped
        )
        assertEquals(listOf(1, 2, 3, 4, 5), UltraWindows.stitch(plan, emissions))
    }
}
