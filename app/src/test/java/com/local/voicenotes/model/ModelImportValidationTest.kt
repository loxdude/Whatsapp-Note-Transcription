package com.local.voicenotes.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ModelImportValidationTest {
    private val name = "parakeet_ultra_encoder_30s_i8.tflite"
    private val header = byteArrayOf(0, 0, 0, 0, 'T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte())

    @Test fun fragmentedHeaderIsFullyReadBeforeGrantAndPersistence() = runBlocking {
        val events = mutableListOf<String>()
        val result = validateThenImport(name, 99, {
            object : ByteArrayInputStream(header) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    events += "read"
                    return super.read(b, off, minOf(1, len))
                }
            }
        }) { evaluation ->
            events += "grant"; events += "persist"
            assertTrue(evaluation.enabled)
            "model"
        }
        assertEquals("model", result)
        assertEquals(List(8) { "read" } + listOf("grant", "persist"), events)
    }

    @Test fun rejectedImportsNeverReachGrantOrPersistenceCallback() = runBlocking {
        for ((file, bytes) in listOf(
            "parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite" to header,
            name to header.copyOf(7),
            name to ByteArray(8)
        )) {
            var committed = false
            try {
                validateThenImport(file, 99, { ByteArrayInputStream(bytes) }) { committed = true }
                fail("accepted $file")
            } catch (expected: Exception) { assertFalse("side effect for $file", committed) }
        }
    }
}
