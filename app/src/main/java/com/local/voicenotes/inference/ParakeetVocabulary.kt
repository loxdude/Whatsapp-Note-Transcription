package com.local.voicenotes.inference

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * The 8192-piece SentencePiece vocabulary of Parakeet Ultra (identical to the Parakeet TDT
 * 0.6B v3 tokenizer), read from the bundled `parakeet_vocab.bin` asset:
 * big-endian `int magic, int count, count x (int length, UTF-8 bytes)`.
 * Pure Kotlin so it can be tested on the JVM.
 */
internal class ParakeetVocabulary(val pieces: List<String>) {
    init {
        require(pieces.size == SIZE) { "Unsupported Parakeet vocabulary size ${pieces.size}." }
    }

    /** Joins the pieces of [ids]; ids below [FIRST_TEXT_ID] and special `<...>` pieces are skipped. */
    fun detokenize(ids: List<Int>): String {
        val output = StringBuilder()
        val bytes = ArrayList<Byte>()
        fun flushBytes() {
            if (bytes.isNotEmpty()) {
                output.append(bytes.toByteArray().toString(Charsets.UTF_8))
                bytes.clear()
            }
        }
        for (id in ids) {
            if (id !in pieces.indices || id < FIRST_TEXT_ID) continue
            val piece = pieces[id]
            val match = BYTE_TOKEN.matchEntire(piece)
            if (match != null) {
                bytes += match.groupValues[1].toInt(16).toByte()
            } else {
                flushBytes()
                if (!piece.startsWith("<") || !piece.endsWith(">")) output.append(piece)
            }
        }
        flushBytes()
        return output.toString().replace('\u2581', ' ').replace(WHITESPACE, " ").trim()
    }

    companion object {
        const val ASSET = "parakeet_vocab.bin"
        const val SIZE = 8192
        /** Ids below this are control tokens (language tags, timestamps, ...). */
        private const val FIRST_TEXT_ID = 274
        private const val MAGIC = 0x504b5643 // PKVC
        private val BYTE_TOKEN = Regex("""<0x([0-9A-Fa-f]{2})>""")
        private val WHITESPACE = Regex("""\s+""")

        fun read(stream: InputStream): ParakeetVocabulary =
            DataInputStream(BufferedInputStream(stream)).use { input ->
                require(input.readInt() == MAGIC) { "Invalid Parakeet vocabulary asset." }
                val pieces = List(input.readInt()) {
                    val bytes = ByteArray(input.readInt())
                    input.readFully(bytes)
                    String(bytes, StandardCharsets.UTF_8)
                }
                require(input.read() == -1) { "Trailing data in the Parakeet vocabulary asset." }
                ParakeetVocabulary(pieces)
            }
    }
}
