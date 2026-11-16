package com.local.voicenotes.inference

/**
 * The stateful Parakeet TDT v3 export's decoder seen by the greedy loop: `decode_1` takes one
 * chunk's encoder output plus a label and predictor state and returns the logits of every frame
 * of the chunk together with the next predictor state.
 */
internal interface ParakeetV3Stepper<E, P> {
    /** Predictor at the start of a chunk: BLANK label, zero h/c, bound to [encoder]. */
    fun fresh(encoder: E): P

    /** Feeds [token] with the state of [previous]. */
    fun advance(encoder: E, token: Int, previous: P): P

    /** Logits [frames x 8198] of [predictor]. */
    fun logits(predictor: P): FloatArray
}

internal object ParakeetV3Decoding {
    const val BLANK = 8192
    const val VOCAB_WITH_BLANK = 8193
    const val LOGITS = 8198
    const val DURATIONS = 5
    const val MAX_SYMBOLS_PER_FRAME = 10

    /**
     * Greedy decoding of one 5 s chunk. Every chunk starts from a fresh predictor: carrying the
     * predictor over from the previous chunk measured 30.9% WER against 18.1% with a fresh one
     * (29 German voice notes), at the same cost.
     */
    fun <E, P> decodeChunk(
        encoder: E,
        frames: Int,
        stepper: ParakeetV3Stepper<E, P>,
        checkCancelled: () -> Unit = {}
    ): List<Int> {
        val emitted = ArrayList<Int>()
        var predictor = stepper.fresh(encoder)
        var frame = 0
        var inner = 0
        while (frame < frames) {
            checkCancelled()
            val logits = stepper.logits(predictor)
            val row = frame * LOGITS
            var token = 0
            var tokenValue = logits[row]
            for (id in 1 until VOCAB_WITH_BLANK) {
                val value = logits[row + id]
                if (value > tokenValue) {
                    tokenValue = value
                    token = id
                }
            }
            var duration = 0
            var durationValue = logits[row + VOCAB_WITH_BLANK]
            for (id in 1 until DURATIONS) {
                val value = logits[row + VOCAB_WITH_BLANK + id]
                if (value > durationValue) {
                    durationValue = value
                    duration = id
                }
            }
            if (token != BLANK) {
                emitted += token
                predictor = stepper.advance(encoder, token, predictor)
            }
            if (duration > 0) {
                frame += duration
                inner = 0
            } else {
                inner++
                if (inner >= MAX_SYMBOLS_PER_FRAME) {
                    frame++
                    inner = 0
                }
            }
        }
        return emitted
    }
}
