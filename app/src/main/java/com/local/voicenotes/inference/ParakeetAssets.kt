package com.local.voicenotes.inference

import android.content.Context
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.nio.charset.StandardCharsets

internal data class ParakeetAssets(
    val vocabulary: List<String>,
    val window: FloatArray,
    val melFilterbank: FloatArray
) {
    companion object {
        private const val MAGIC = 0x504b4153 // PKAS

        fun load(context: Context): ParakeetAssets =
            DataInputStream(BufferedInputStream(context.assets.open("parakeet_frontend.bin"))).use { input ->
                require(input.readInt() == MAGIC) { "Invalid Parakeet frontend asset." }
                val vocab = List(input.readInt()) {
                    val bytes = ByteArray(input.readInt())
                    input.readFully(bytes)
                    String(bytes, StandardCharsets.UTF_8)
                }
                val window = FloatArray(input.readInt()) { input.readFloat() }
                val filters = FloatArray(input.readInt()) { input.readFloat() }
                require(vocab.size == 8192 && window.size == 400 && filters.size == 128 * 257) {
                    "Unsupported Parakeet frontend asset dimensions."
                }
                ParakeetAssets(vocab, window, filters)
            }
    }
}
