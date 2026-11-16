package com.local.voicenotes.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParakeetV3DecodingTest {
    private val frames = 4

    /** Predictor = (chunk it is bound to, labels fed since its fresh start). */
    private data class FakePredictor(val chunk: Int, val history: List<Int>, val logits: FloatArray)

    /**
     * Fake `decode_1`: on frame f the arg-max token is (chunk * 100 + history.size + 1) on the
     * first frame of a chunk only while the predictor has not emitted yet in this chunk;
     * every other step is blank with duration 1. A predictor carried over from a previous chunk
     * would therefore change what the chunk emits.
     */
    private inner class FakeStepper : ParakeetV3Stepper<Int, FakePredictor> {
        val freshChunks = ArrayList<Int>()
        val advanced = ArrayList<Pair<Int, Int>>()

        private fun logitsFor(chunk: Int, history: List<Int>) = FloatArray(frames * ParakeetV3Decoding.LOGITS) { -1f }.also {
            for (frame in 0 until frames) {
                val row = frame * ParakeetV3Decoding.LOGITS
                val token = if (frame == 0 && history.isEmpty()) chunk * 100 + 1 + history.size else ParakeetV3Decoding.BLANK
                it[row + token] = 5f
                it[row + ParakeetV3Decoding.VOCAB_WITH_BLANK + 1] = 2f
            }
        }

        override fun fresh(encoder: Int) = FakePredictor(encoder, emptyList(), logitsFor(encoder, emptyList()))
            .also { freshChunks += encoder }

        override fun advance(encoder: Int, token: Int, previous: FakePredictor): FakePredictor {
            advanced += encoder to token
            val history = previous.history + token
            return FakePredictor(encoder, history, logitsFor(encoder, history))
        }

        override fun logits(predictor: FakePredictor) = predictor.logits
    }

    @Test fun everyChunkStartsFromAFreshPredictor() {
        val stepper = FakeStepper()
        val tokens = (0 until 3).flatMap { chunk -> ParakeetV3Decoding.decodeChunk(chunk, frames, stepper) }
        // Each chunk emits its own first token: none inherits the previous chunk's history.
        assertEquals(listOf(1, 101, 201), tokens)
        assertEquals(listOf(0, 1, 2), stepper.freshChunks)
        assertEquals(listOf(0 to 1, 1 to 101, 2 to 201), stepper.advanced)
    }

    @Test fun chunkResultDoesNotDependOnPreviousChunks() {
        val alone = ParakeetV3Decoding.decodeChunk(2, frames, FakeStepper())
        val shared = FakeStepper()
        ParakeetV3Decoding.decodeChunk(0, frames, shared)
        ParakeetV3Decoding.decodeChunk(1, frames, shared)
        assertEquals(alone, ParakeetV3Decoding.decodeChunk(2, frames, shared))
    }

    /** Scripted logits per call, ignoring state, for the frame/duration rules. */
    private class Scripted(private val steps: List<Pair<Int, Int>>, private val rows: Int) : ParakeetV3Stepper<Unit, Int> {
        override fun fresh(encoder: Unit) = 0
        override fun advance(encoder: Unit, token: Int, previous: Int) = previous + 1
        override fun logits(predictor: Int): FloatArray = FloatArray(rows * ParakeetV3Decoding.LOGITS).also {
            // Every frame row answers the same scripted (token, duration) for this predictor step.
            val (token, duration) = steps.getOrElse(predictor) { ParakeetV3Decoding.BLANK to 1 }
            for (frame in 0 until rows) {
                val row = frame * ParakeetV3Decoding.LOGITS
                it[row + token] = 5f
                it[row + ParakeetV3Decoding.VOCAB_WITH_BLANK + duration] = 3f
            }
        }
    }

    @Test fun keepsTheExistingFrameRules() {
        // Tokens with duration 0 stay on the frame up to 10 symbols, then move one frame.
        val capped = ParakeetV3Decoding.decodeChunk(Unit, 2, Scripted(List(25) { 50 to 0 }, 2))
        assertTrue(capped.all { it == 50 })
        assertEquals(20, capped.size)
        // A token with duration 3 skips three frames.
        assertEquals(listOf(7, 8), ParakeetV3Decoding.decodeChunk(Unit, 6, Scripted(listOf(7 to 3, 8 to 3), 6)))
    }
}
