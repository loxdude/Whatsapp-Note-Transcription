package com.local.voicenotes.inference

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class MistralTranscriptionRequestsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun cancellationAfterStagingDeletesTempWithoutExecutingRequest() = runBlocking {
        val requests = MistralTranscriptionRequests()
        var executions = 0
        try {
            requests.run(
                readKey = { "synthetic-key" },
                stage = {
                    val file = tmp.newFile().apply { writeText("synthetic audio") }
                    requests.cancel()
                    MistralStagedAudio(file, "audio.wav", "audio/wav", true)
                },
                send = { _, _, _ -> executions++ }
            )
            fail("cancellation ignored between staging and HTTP")
        } catch (_: CancellationException) {
        }
        assertEquals(0, executions)
        assertTrue(tmp.root.listFiles()!!.isEmpty())
    }

    @Test fun oldRequestCancellationCannotCancelNextRequestsCall() = runBlocking {
        val requests = MistralTranscriptionRequests()
        var previous: MistralTranscriptionRequests.Request? = null
        var oldCallCancellations = 0
        var newCallCancellations = 0
        val original = tmp.newFile().apply { writeText("synthetic audio") }
        fun source() = MistralStagedAudio(original, "audio.wav", "audio/wav", false)
        requests.run({ "synthetic-key" }, { source() }) { _, _, request ->
            previous = request
            request.attachCall { oldCallCancellations++ }
        }
        requests.run({ "synthetic-key" }, { source() }) { _, _, request ->
            request.attachCall { newCallCancellations++ }
            previous!!.cancel() // A late cancel() snapshot still references the old request.
            request.ensureActive()
            assertEquals(0, newCallCancellations)
            requests.cancel()
            assertEquals(1, newCallCancellations)
        }
        assertEquals(1, oldCallCancellations)
        assertTrue("original URI file is never a staging temp", original.isFile)
    }

    @Test fun cancellationRacingWithCallPublicationCancelsItBeforeExecution() = runBlocking {
        val requests = MistralTranscriptionRequests()
        var callCancelled = false
        var executions = 0
        try {
            requests.run(
                readKey = { "synthetic-key" },
                stage = { MistralPcmStaging.stage(tmp.root, floatArrayOf(0f), it) },
                send = { _, _, request ->
                    // Model cancel() between newCall() and publishing the cancellation hook.
                    requests.cancel()
                    request.attachCall { callCancelled = true }
                    request.ensureActive()
                    executions++
                }
            )
            fail("request executed despite cancellation during call publication")
        } catch (_: CancellationException) {
        }
        assertTrue(callCancelled)
        assertEquals(0, executions)
        assertTrue(tmp.root.listFiles()!!.isEmpty())
    }

    @Test fun keyOrStagingFailureReleasesMarkerForNextRequest() = runBlocking {
        val requests = MistralTranscriptionRequests()
        for (failInKey in listOf(true, false)) {
            try {
                requests.run<Unit>(
                    readKey = { if (failInKey) error("synthetic key failure") else "synthetic-key" },
                    stage = { error("synthetic staging failure") },
                    send = { _, _, _ -> fail("unexpected HTTP") }
                )
                fail("expected failure")
            } catch (_: IllegalStateException) {
            }
        }
        assertEquals("next", requests.run(
            readKey = { "synthetic-key" },
            stage = { MistralPcmStaging.stage(tmp.root, floatArrayOf(0f), it) },
            send = { _, _, _ -> "next" }
        ))
        assertTrue(tmp.root.listFiles()!!.isEmpty())
    }

    @Test fun pcmCancellationAtFirstChunkStopsBeforeRequestAndRemovesPartialFile() {
        assertPcmChunkCancellation(cancelJob = false)
    }

    @Test fun coroutineCancellationAtFirstChunkStopsBeforeRequestAndRemovesPartialFile() {
        assertPcmChunkCancellation(cancelJob = true)
    }

    private fun assertPcmChunkCancellation(cancelJob: Boolean) {
        val requests = MistralTranscriptionRequests()
        val job = kotlinx.coroutines.Job()
        var executions = 0
        var cancelledAtBytes = 0L
        try {
            runBlocking(job) {
                requests.run(
                    readKey = { "synthetic-key" },
                    stage = { ensureActive ->
                        MistralPcmStaging.stage(tmp.root, FloatArray(20_000) { 0.5f }) {
                            val written = tmp.root.listFiles()!!.singleOrNull()?.length() ?: 0L
                            if (written >= 44 + 4096 * 2 && cancelledAtBytes == 0L) {
                                cancelledAtBytes = written
                                if (cancelJob) job.cancel() else requests.cancel()
                            }
                            ensureActive()
                        }
                    },
                    send = { _, _, _ -> executions++; "unexpected" }
                )
            }
            fail("PCM staging ignored cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals("must stop at the first PCM chunk, not encode all PCM", 44L + 4096 * 2, cancelledAtBytes)
        assertEquals(0, executions)
        assertTrue("partial WAV must be removed", tmp.root.listFiles()!!.isEmpty())
        assertEquals("next", runBlocking {
            requests.run(
                readKey = { "synthetic-key" },
                stage = { MistralPcmStaging.stage(tmp.root, floatArrayOf(0f), it) },
                send = { _, source, _ -> executions++; assertEquals(46, source.file.length().toInt()); "next" }
            )
        })
        assertEquals(1, executions)
        assertTrue(tmp.root.listFiles()!!.isEmpty())
    }

    @Test fun cancelDuringUninterruptibleKeyReadNeverStagesOrExecutesAndNextRequestWorks() {
        val requests = MistralTranscriptionRequests()
        val enteredKeyRead = CountDownLatch(1)
        val releaseKeyRead = CountDownLatch(1)
        var staged = 0
        var executions = 0
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit<Boolean> {
                runBlocking {
                    try {
                        requests.run(
                            readKey = {
                                enteredKeyRead.countDown()
                                check(releaseKeyRead.await(5, TimeUnit.SECONDS))
                                "synthetic-key"
                            },
                            stage = { ensureActive ->
                                staged++
                                MistralPcmStaging.stage(tmp.root, FloatArray(16), ensureActive)
                            },
                            send = { _, _, _ -> executions++; "unexpected" }
                        )
                        false
                    } catch (_: CancellationException) { true }
                }
            }
            assertTrue(enteredKeyRead.await(5, TimeUnit.SECONDS))
            requests.cancel()
            releaseKeyRead.countDown()
            assertTrue("cancel during key read was ignored", result.get(5, TimeUnit.SECONDS))
            assertEquals(0, staged)
            assertEquals(0, executions)
            assertTrue(tmp.root.listFiles()!!.isEmpty())
            // Idle cancellation must not poison the following request's marker.
            requests.cancel()
            assertEquals("next", runBlocking {
                requests.run(
                    readKey = { "synthetic-key" },
                    stage = { MistralPcmStaging.stage(tmp.root, FloatArray(16), it) },
                    send = { _, source, _ ->
                        executions++
                        assertTrue(source.file.isFile)
                        "next"
                    }
                )
            })
            assertEquals(1, executions)
            assertTrue(tmp.root.listFiles()!!.isEmpty())
        } finally {
            releaseKeyRead.countDown()
            pool.shutdownNow()
        }
    }
}
