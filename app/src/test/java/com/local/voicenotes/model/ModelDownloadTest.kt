package com.local.voicenotes.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class ModelDownloadTest {
    @get:Rule val tmp = TemporaryFolder()

    private val payload = ByteArray(10_000) { (it * 31 + 7).toByte() }
    private val spec = DownloadSpec("https://example/model.tflite", "model.tflite", payload.size.toLong(), sha(payload))

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Serves [body] honouring ranges unless [ignoreRange]; records requested offsets. */
    private inner class Server(val body: ByteArray = payload, val ignoreRange: Boolean = false) : RangeSource {
        val offsets = mutableListOf<Long>()
        override fun open(url: String, offset: Long): RangeResponse {
            offsets += offset
            val start = if (ignoreRange) 0L else offset
            return RangeResponse(ByteArrayInputStream(body, start.toInt(), body.size - start.toInt()), start, body.size.toLong())
        }
    }

    private fun download(dir: File, source: RangeSource, free: Long = Long.MAX_VALUE) =
        ModelDownload(dir, spec, source, freeBytes = { free }, bufferSize = 1024)

    @Test fun resumesFromThePartialFileAndVerifiesTheWholeContent() {
        val dir = tmp.newFolder()
        File(dir, "model.tflite.partial").writeBytes(payload.copyOf(4_000))
        val server = Server()
        val file = download(dir, server).run({}, { _, _ -> })
        assertEquals(listOf(4_000L), server.offsets)
        assertArrayEquals(payload, file.readBytes())
        assertFalse(File(dir, "model.tflite.partial").exists())
    }

    @Test fun restartsFromZeroWhenTheServerIgnoresTheRange() {
        val dir = tmp.newFolder()
        File(dir, "model.tflite.partial").writeBytes(payload.copyOf(4_000))
        assertArrayEquals(payload, download(dir, Server(ignoreRange = true)).run({}, { _, _ -> }).readBytes())
    }

    @Test fun corruptContentIsDeletedAndNeverInstalled() {
        val dir = tmp.newFolder()
        val corrupt = payload.copyOf().also { it[5_000] = (it[5_000] + 1).toByte() }
        try {
            download(dir, Server(corrupt)).run({}, { _, _ -> })
            fail("corrupt download accepted")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("integrity"))
        }
        assertFalse(File(dir, "model.tflite").exists())
        assertFalse(File(dir, "model.tflite.partial").exists())
    }

    @Test fun cancellationKeepsThePartialBytesForAResume() {
        val dir = tmp.newFolder()
        var calls = 0
        try {
            download(dir, Server()).run({ if (++calls > 3) throw CancellationException("stop") }, { _, _ -> })
            fail("not cancelled")
        } catch (_: CancellationException) {}
        val partial = File(dir, "model.tflite.partial")
        assertTrue(partial.length() in 1 until payload.size)
        assertFalse(File(dir, "model.tflite").exists())
    }

    @Test fun refusesToStartWithoutEnoughFreeSpace() {
        val dir = tmp.newFolder()
        val server = Server()
        try {
            download(dir, server, free = 1_000).run({}, { _, _ -> })
            fail("started without space")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.startsWith("Not enough free storage"))
        }
        assertTrue(server.offsets.isEmpty())
    }

    @Test fun aWrongServerSizeFailsBeforeWritingAnything() {
        val dir = tmp.newFolder()
        try {
            download(dir, Server(payload + ByteArray(10))).run({}, { _, _ -> })
            fail("accepted wrong size")
        } catch (_: IOException) {}
        assertFalse(File(dir, "model.tflite.partial").exists())
    }
}
