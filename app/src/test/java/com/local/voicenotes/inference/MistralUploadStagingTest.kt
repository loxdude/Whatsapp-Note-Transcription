package com.local.voicenotes.inference

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

class MistralUploadStagingTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun copiesAllBytesWithinLimitAndCountsThem() {
        val data = ByteArray(10_000) { it.toByte() }
        val out = ByteArrayOutputStream()
        val copied = MistralUploadStaging.copyWithLimit(ByteArrayInputStream(data), out, 10_000, bufferSize = 1024) {}
        assertEquals(10_000L, copied)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test fun rejectsStreamLargerThanLimitWithoutWritingPastIt() {
        val out = ByteArrayOutputStream()
        try {
            MistralUploadStaging.copyWithLimit(ByteArrayInputStream(ByteArray(10_001)), out, 10_000, bufferSize = 1000) {}
            fail("limit not enforced")
        } catch (e: UploadTooLargeException) {
            assertEquals(10_000L, e.limitBytes)
        }
        assertTrue(out.size() <= 10_000)
    }

    @Test fun limitIsEnforcedOnUnboundedStreamOfUnknownSize() {
        var produced = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int { produced += len; return len }
        }
        try {
            MistralUploadStaging.copyWithLimit(endless, ByteArrayOutputStream(), 1_000_000, bufferSize = 4096) {}
            fail("limit not enforced")
        } catch (_: UploadTooLargeException) {
        }
        assertTrue("read far past the limit: $produced", produced <= 1_000_000 + 4096)
    }

    @Test fun cancellationStopsCopyPromptly() {
        var checks = 0
        var reads = 0
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int { reads++; return len }
        }
        try {
            MistralUploadStaging.copyWithLimit(endless, ByteArrayOutputStream(), Long.MAX_VALUE, bufferSize = 16) {
                if (++checks > 3) throw CancellationException("cancelled")
            }
            fail("cancellation ignored")
        } catch (_: CancellationException) {
        }
        assertEquals(3, reads)
    }

    @Test fun declaredSizeCheck() {
        MistralUploadStaging.checkDeclaredSize(null, 100)
        MistralUploadStaging.checkDeclaredSize(100, 100)
        try {
            MistralUploadStaging.checkDeclaredSize(101, 100)
            fail("oversized file accepted")
        } catch (e: UploadTooLargeException) {
            assertTrue(e.message!!.contains("MB"))
        }
        assertEquals(100L * 1024 * 1024, MistralUploadStaging.MAX_UPLOAD_BYTES)
    }

    @Test fun uploadNameNeverLeaksDisplayName() {
        assertEquals("audio.mp3", MistralUploadStaging.uploadFileName("Call with Dr. Smith.mp3", "audio/mpeg"))
        assertEquals("audio.m4a", MistralUploadStaging.uploadFileName("secret.bin", "audio/mp4"))
        assertEquals("audio.ogg", MistralUploadStaging.uploadFileName(null, "audio/ogg; codecs=opus"))
        assertEquals("audio.flac", MistralUploadStaging.uploadFileName("My Recording.FLAC", "application/octet-stream"))
        assertEquals("audio", MistralUploadStaging.uploadFileName("no extension", null))
        assertEquals("audio", MistralUploadStaging.uploadFileName("evil.<script>", null))
        assertEquals("audio", MistralUploadStaging.uploadFileName("../../etc/passwd", null))
        assertEquals("audio", MistralUploadStaging.uploadFileName(null, null))
        assertEquals("audio", MistralUploadStaging.uploadFileName("x.waytoolongextension", null))
    }

    @Test fun stagingSuffixIsSanitized() {
        assertEquals(".wav", MistralUploadStaging.stagingSuffix("Meeting/notes.wav", null))
        assertEquals(".audio", MistralUploadStaging.stagingSuffix("weird name", null))
        assertEquals(".mp3", MistralUploadStaging.stagingSuffix(null, "audio/mpeg"))
    }

    @Test fun deletesOnlyOldMistralStagingFiles() {
        val dir = tmp.newFolder()
        val stale = dir.resolve("mistral-123.m4a").apply { writeText("x"); setLastModified(1_000_000) }
        val fresh = dir.resolve("mistral-456.wav").apply { writeText("x") }
        val other = dir.resolve("model-cache.bin").apply { writeText("x"); setLastModified(1_000_000) }
        val cutoff = System.currentTimeMillis() - 60_000

        assertEquals(1, MistralUploadStaging.deleteStaleStagingFiles(dir, cutoff))
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        assertTrue(other.exists())
    }

    @Test fun staleCleanupToleratesMissingDirectory() {
        assertEquals(0, MistralUploadStaging.deleteStaleStagingFiles(tmp.root.resolve("missing"), Long.MAX_VALUE))
    }
}
