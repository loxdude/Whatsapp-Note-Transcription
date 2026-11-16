package com.local.voicenotes.audio

/**
 * Growable float buffer with a hard size cap.
 *
 * Memory: presize with [initialCapacity] from the known duration so that no growth copy is
 * needed. [toArray] returns the backing array without copying when it is exactly full, and
 * otherwise a trimmed copy. Growth is 1.5x (not 2x) to limit over-allocation near the cap.
 * The accumulator must not be appended to after [toArray].
 */
internal class FloatAccumulator(initialCapacity: Int, private val maxSize: Int) {
    init {
        require(maxSize > 0) { "maxSize must be positive" }
    }

    private var values = FloatArray(initialCapacity.coerceIn(1, maxSize))
    var size = 0
        private set

    val capacity: Int get() = values.size

    fun append(chunk: FloatArray) = append(chunk, 0, chunk.size)

    fun append(chunk: FloatArray, offset: Int, length: Int) {
        if (length == 0) return
        require(length <= maxSize - size) { "Decoded audio exceeds the 15-minute limit." }
        val needed = size + length
        if (needed > values.size) {
            var next = values.size.toLong()
            while (next < needed) next = (next + (next shr 1) + 1).coerceAtMost(maxSize.toLong())
            values = values.copyOf(next.toInt())
        }
        chunk.copyInto(values, size, offset, offset + length)
        size = needed
    }

    /** Returns the backing array when exactly full (no copy); otherwise a trimmed copy. */
    fun toArray(): FloatArray = if (size == values.size) values else values.copyOf(size)
}
