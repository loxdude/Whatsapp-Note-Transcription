package com.local.voicenotes.inference

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest

class BundledModelFilesTest {
    @get:Rule val temp = TemporaryFolder()

    private val bytes = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val model = BundledModel(
        "graph.tflite", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    )

    @Test fun extractsOnceAndReusesTheVerifiedCopy() {
        val dir = temp.newFolder("bundled")
        var opens = 0
        val files = BundledModelFiles(dir)
        val first = files.materialize(model, { opens++; ByteArrayInputStream(bytes) })
        assertArrayEquals(bytes, first.readBytes())
        val second = files.materialize(model, { opens++; ByteArrayInputStream(bytes) })
        assertEquals(first, second)
        assertEquals(1, opens)
        assertEquals(listOf("graph.tflite"), dir.list()!!.toList())
    }

    @Test fun corruptedCopyWithTheSameSizeIsReplaced() {
        val dir = temp.newFolder("bundled")
        val files = BundledModelFiles(dir)
        val file = files.materialize(model, { ByteArrayInputStream(bytes) })
        file.writeBytes(bytes.copyOf().also { it[123] = (it[123] + 1).toByte() })
        var opens = 0
        assertArrayEquals(bytes, files.materialize(model, { opens++; ByteArrayInputStream(bytes) }).readBytes())
        assertEquals(1, opens)
    }

    @Test fun mismatchingSourceIsNeverPublished() {
        val dir = temp.newFolder("bundled")
        val files = BundledModelFiles(dir)
        assertThrows(IOException::class.java) {
            files.materialize(model, { ByteArrayInputStream(bytes.copyOf(bytes.size - 1)) })
        }
        assertFalse(java.io.File(dir, "graph.tflite").exists())
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test fun cancellationLeavesNoPartialFile() {
        val dir = temp.newFolder("bundled")
        var checks = 0
        assertThrows(IllegalStateException::class.java) {
            BundledModelFiles(dir).materialize(model, { ByteArrayInputStream(bytes) }) {
                if (++checks == 2) throw IllegalStateException("cancelled")
            }
        }
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test fun bundledUltraGraphsAreDeclaredForEveryWindow() {
        for (seconds in UltraWindows.SUPPORTED_SECONDS) {
            assertEquals("parakeet_ultra_mel_${seconds}s.tflite", ParakeetUltraBundle.mel(seconds).assetName)
        }
        assertEquals("parakeet_ultra_decoder_i8.tflite", ParakeetUltraBundle.decoder.assetName)
    }

    @Test fun declaredChecksumsMatchTheAssetsInTheSourceTree() {
        // Gradle runs unit tests in the module directory.
        val assets = java.io.File("src/main/assets")
        org.junit.Assume.assumeTrue(assets.isDirectory)
        val dir = temp.newFolder("copies")
        for (bundled in UltraWindows.SUPPORTED_SECONDS.map(ParakeetUltraBundle::mel) + ParakeetUltraBundle.decoder) {
            val source = java.io.File(assets, bundled.assetName)
            assertEquals(bundled.assetName, bundled.size, source.length())
            // materialize() only publishes a copy whose size and SHA-256 match the declaration.
            assertEquals(bundled.size, BundledModelFiles(dir).materialize(bundled, { source.inputStream() }).length())
        }
    }
}
