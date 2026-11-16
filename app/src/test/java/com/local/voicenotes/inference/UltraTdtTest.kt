package com.local.voicenotes.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraTdtTest {
    /** One joint step's decision. */
    private data class Step(val token: Int, val duration: Int)

    private fun logits(step: Step) = FloatArray(UltraTdt.LOGITS) { -1f }.also {
        it[step.token] = 5f
        it[UltraTdt.TOKEN_LOGITS + step.duration] = 3f
    }

    /** State = tokens fed to predict so far; joint answers from [script] per call. */
    private class FakeNetwork(private val script: (call: Int, frame: Int, history: List<Int>) -> FloatArray) :
        UltraTdtNetwork<List<Int>> {
        val startCalls = ArrayList<Int>()
        val jointFrames = ArrayList<Int>()
        val predicted = ArrayList<Int>()

        override fun start(): List<Int> = emptyList<Int>().also { startCalls += 1 }
        override fun predict(token: Int, state: List<Int>): List<Int> = (state + token).also { predicted += token }
        override fun joint(frame: Int, state: List<Int>): FloatArray {
            jointFrames += frame
            return script(jointFrames.size - 1, frame, state)
        }
    }

    private fun network(vararg steps: Step, fallback: Step = Step(UltraTdt.BLANK, 1)) =
        FakeNetwork { call, _, _ -> logits(steps.getOrElse(call) { fallback }) }

    @Test fun emitsTokensAdvancesByDurationAndFeedsPredict() {
        val net = network(Step(100, 2), Step(UltraTdt.BLANK, 3), Step(200, 0), Step(201, 1))
        val out = UltraTdt.decode(validFrames = 6, network = net)
        assertEquals(listOf(UltraEmission(0, 100), UltraEmission(5, 200), UltraEmission(5, 201)), out)
        assertEquals(listOf(0, 2, 5, 5), net.jointFrames)
        assertEquals(listOf(100, 200, 201), net.predicted)
        assertEquals(1, net.startCalls.size)
    }

    @Test fun jointSeesTheStateAfterEachEmittedToken() {
        val seen = ArrayList<List<Int>>()
        val net = FakeNetwork { call, _, history ->
            seen += history
            logits(if (call < 2) Step(10 + call, 1) else Step(UltraTdt.BLANK, 1))
        }
        UltraTdt.decode(4, net)
        assertEquals(listOf(emptyList(), listOf(10), listOf(10, 11), listOf(10, 11)), seen)
    }

    @Test fun eosStopsTheWindowWithoutEmitting() {
        val net = network(Step(100, 1), Step(UltraTdt.EOS, 1), Step(300, 1))
        assertEquals(listOf(UltraEmission(0, 100)), UltraTdt.decode(10, net))
        assertEquals(listOf(0, 1), net.jointFrames)
        assertEquals(listOf(100), net.predicted)
    }

    @Test fun blankWithDurationZeroMovesOneFrame() {
        val net = network(fallback = Step(UltraTdt.BLANK, 0))
        assertEquals(emptyList<UltraEmission>(), UltraTdt.decode(5, net))
        assertEquals(listOf(0, 1, 2, 3, 4), net.jointFrames)
    }

    @Test fun tenSymbolsOnOneFrameMoveOneFrame() {
        val net = network(fallback = Step(42, 0))
        val out = UltraTdt.decode(3, net)
        // Ten tokens per frame, then the cap moves on; the run counter restarts on each frame.
        assertEquals(30, out.size)
        assertEquals(List(10) { 0 } + List(10) { 1 } + List(10) { 2 }, out.map { it.frame })
    }

    @Test fun nonZeroDurationResetsTheSymbolRun() {
        // 9 tokens on frame 0, a blank skips to frame 1 (resetting the run), then 10 more on frame 1.
        val steps = List(9) { Step(7, 0) } + Step(UltraTdt.BLANK, 1) + List(12) { Step(8, 0) }
        val net = network(*steps.toTypedArray())
        val out = UltraTdt.decode(2, net)
        assertEquals(List(9) { 0 } + List(10) { 1 }, out.map { it.frame })
    }

    @Test fun stopsAfter3750Steps() {
        // Every step emits with duration 0, so the 10-symbol cap advances one frame per 10 steps.
        val net = network(fallback = Step(42, 0))
        val out = UltraTdt.decode(validFrames = 1_000_000, network = net)
        assertEquals(UltraTdt.MAX_STEPS, net.jointFrames.size)
        assertEquals(UltraTdt.MAX_STEPS, out.size)
        assertEquals(374, out.last().frame)
    }

    @Test fun stopsAtTheLastValidFrame() {
        val net = network(Step(5, 3), Step(6, 4), fallback = Step(UltraTdt.BLANK, 4))
        val out = UltraTdt.decode(validFrames = 9, network = net)
        assertEquals(listOf(UltraEmission(0, 5), UltraEmission(3, 6)), out)
        assertEquals(listOf(0, 3, 7), net.jointFrames)
        assertEquals(emptyList<UltraEmission>(), UltraTdt.decode(0, network()))
    }

    @Test fun tiesPickTheFirstIndexLikeNumpy() {
        val net = FakeNetwork { call, _, _ ->
            FloatArray(UltraTdt.LOGITS).also { if (call > 0) it[UltraTdt.BLANK] = 1f }
        }
        // Call 0: all token logits 0 -> token 0, durations all 0 -> duration 0.
        val out = UltraTdt.decode(2, net)
        assertEquals(UltraEmission(0, 0), out.first())
    }

    @Test fun cancellationIsCheckedEveryStep() {
        var checks = 0
        assertThrows(IllegalStateException::class.java) {
            UltraTdt.decode(100, network(fallback = Step(UltraTdt.BLANK, 1))) { if (++checks > 5) throw IllegalStateException("cancelled") }
        }
        assertTrue(checks == 6)
    }
}
