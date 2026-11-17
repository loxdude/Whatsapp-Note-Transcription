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
        assertEquals(448_000, UltraWindows.hopSamples(30)) // 28 s
        assertEquals(480_000, UltraWindows.graphSamples(30))
        // Only the 30 s graphs ship; shorter windows are rejected.
        for (seconds in listOf(10, 15, 20)) {
            assertThrows(IllegalArgumentException::class.java) { UltraWindows.windowSamples(seconds) }
        }
    }

    @Test fun audioUpToOneWindowIsOnePaddedWindowKeepingEverything() {
        for ((samples, seconds) in listOf(1 to 30, 5_000 to 30, 100_000 to 30, 479_840 to 30)) {
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
        val plan = UltraWindows.plan(1_000_000, 30) // windows at 0 s, 28 s, 56 s; cuts at 28.995 s and 56.995 s
        // A word at 28.96 s (frame 362 of window 0, frame 12 of window 1) belongs to window 0.
        assertTrue(plan[0].keeps(362))
        assertFalse(plan[1].keeps(12))
        // At 29.04 s it belongs to window 1.
        assertFalse(plan[0].keeps(363))
        assertTrue(plan[1].keeps(13))
        val emissions = listOf(
            listOf(UltraEmission(362, 1), UltraEmission(363, 99)), // 28.96 s kept, 29.04 s dropped
            listOf(UltraEmission(12, 98), UltraEmission(13, 2), UltraEmission(100, 3), UltraEmission(363, 97)),
            listOf(UltraEmission(12, 96), UltraEmission(13, 4), UltraEmission(300, 5)) // 56.96 s dropped
        )
        assertEquals(listOf(1, 2, 3, 4, 5), UltraWindows.stitch(plan, emissions))
    }
}
