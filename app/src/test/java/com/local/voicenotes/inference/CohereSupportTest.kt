package com.local.voicenotes.inference

import org.junit.Assert.assertEquals
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
}
