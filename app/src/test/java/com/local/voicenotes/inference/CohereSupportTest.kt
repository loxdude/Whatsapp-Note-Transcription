package com.local.voicenotes.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CohereSupportTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun tagsAndTheirSpacesAreRemoved() {
        assertEquals("مرحبا كيف حالك", CohereText.clean("مرحبا <hesitation> كيف  <noise>حالك <hesitation>"))
        assertEquals("a\nb", CohereText.clean("<laughter> a \n<hesitation> b"))
        assertEquals("x < y", CohereText.clean("x < y"))
    }

    @Test fun threadsCountTheCoresAboveTheSlowestCluster() {
        // Snapdragon 8 Gen 3: 1 prime, 5 performance, 2 efficiency cores.
        assertEquals(6, CpuCores.inferenceThreads(listOf(2_265_600L, 2_265_600, 2_960_000, 2_960_000, 3_148_800, 3_148_800, 3_148_800, 3_302_400)))
        // Exynos 9825: 2 + 2 big, 4 little.
        assertEquals(4, CpuCores.inferenceThreads(listOf(1_950_000L, 1_950_000, 1_950_000, 1_950_000, 2_400_000, 2_400_000, 2_730_000, 2_730_000)))
        assertEquals(2, CpuCores.inferenceThreads(listOf(1_800_000L, 1_800_000, 1_800_000, 2_400_000)))
        assertEquals(6, CpuCores.inferenceThreads(List(8) { 2_000_000L }))
        assertEquals(4, CpuCores.inferenceThreads(emptyList()))
    }

    @Test fun maxFrequenciesAreReadFromSysfs() {
        val root = tmp.newFolder("cpu")
        listOf("cpu0" to "1800000", "cpu1" to "2400000\n").forEach { (core, khz) ->
            File(root, "$core/cpufreq").mkdirs()
            File(root, "$core/cpufreq/cpuinfo_max_freq").writeText(khz)
        }
        File(root, "cpu2").mkdirs() // offline core without cpufreq
        File(root, "cpufreq").mkdirs()
        assertEquals(listOf(1_800_000L, 2_400_000L), CpuCores.readMaxFreqsKhz(root).sorted())
    }

    @Test fun progressMovesWithinAWindowButWaitsBeforeTheEnd() {
        val p = CohereProgressEstimate(samples = 16_000 * 20, msPerSecond = 500) // one 20 s window, ~10 s
        p.start(0)
        assertEquals(0.01f, p.fraction(0), 1e-4f)
        assertEquals(0.5f, p.fraction(5_000), 1e-3f)
        val late = p.fraction(15_000)
        assertTrue(late > 0.85f && late < p.fraction(20_000) && p.fraction(20_000) < 0.95f)
        assertEquals(0.95f, p.fraction(120_000), 1e-3f)
        p.window(1, 1, 9_000)
        assertEquals(1f, p.fraction(9_000), 0f)
        assertEquals(450L, p.measured())
    }

    @Test fun progressWeightsTheShortLastWindowByItsLength() {
        val p = CohereProgressEstimate(samples = 16_000 * 36, msPerSecond = 100) // 30 s + 6 s
        p.start(0)
        assertEquals(0.5f * 30 / 36, p.fraction(1_500), 1e-3f)
        p.window(1, 2, 3_000)
        assertEquals(30f / 36, p.fraction(3_000), 1e-3f)
    }

    @Test fun progressCountsFinishedWindows() {
        val p = CohereProgressEstimate(samples = 16_000 * 60, msPerSecond = 100) // two 30 s windows
        p.start(0)
        p.window(1, 2, 2_000)
        assertEquals(0.5f, p.fraction(2_000), 1e-3f)
        assertEquals(0.75f, p.fraction(3_500), 1e-3f)
        assertEquals(null, p.measured())
    }
}
