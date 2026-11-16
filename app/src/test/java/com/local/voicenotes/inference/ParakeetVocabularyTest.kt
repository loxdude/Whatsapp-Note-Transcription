package com.local.voicenotes.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/** The bundled vocabulary asset (generated from the former parakeet_frontend.bin). */
class ParakeetVocabularyTest {
    private fun bundled(): ParakeetVocabulary {
        // Unit tests run with the module directory as working directory.
        val asset = listOf(File("src/main/assets"), File("app/src/main/assets"))
            .map { File(it, ParakeetVocabulary.ASSET) }.first { it.isFile }
        return asset.inputStream().use(ParakeetVocabulary::read)
    }

    @Test fun bundledAssetHas8192Pieces() {
        val vocabulary = bundled()
        assertEquals(8192, vocabulary.pieces.size)
        assertEquals("<unk>", vocabulary.pieces[0])
        assertEquals("<|endoftext|>", vocabulary.pieces[3])
        assertEquals("en", vocabulary.pieces[274])
    }

    @Test fun detokenizesAKnownSequenceIdentically() {
        // Expected output computed with the previous implementation on parakeet_frontend.bin.
        val ids = listOf(
            5, 2067, 2733, 7869, 1036, 522, 3700, 274, 7877, 1203, 1117, 7350, 2676, 1430, 646, 659,
            6724, 7868, 517, 863, 692, 426, 1815, 7867, 539, 7894, 7883, 3847, 7965, 8054, 7864, 3, 8192, -1
        )
        assertEquals(
            "Hallo zusammen, wir treffen uns morgen um acht Uhr am Bahnhof. Grüße",
            bundled().detokenize(ids)
        )
    }

    @Test fun byteTokensAreJoinedIntoUtf8AndSpecialPiecesSkipped() {
        val pieces = MutableList(8192) { "p$it" }
        pieces[300] = "\u2581Preis"
        pieces[301] = "<0xE2>"
        pieces[302] = "<0x82>"
        pieces[303] = "<0xAC>"
        pieces[304] = "<|special|>"
        val vocabulary = ParakeetVocabulary.read(encode(pieces))
        assertEquals("Preis€ Preis", vocabulary.detokenize(listOf(300, 301, 302, 303, 304, 300)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAVocabularyOfTheWrongSize() {
        ParakeetVocabulary.read(encode(listOf("a", "b")))
    }

    private fun encode(pieces: List<String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(0x504b5643)
            out.writeInt(pieces.size)
            pieces.forEach { piece ->
                val utf8 = piece.encodeToByteArray()
                out.writeInt(utf8.size)
                out.write(utf8)
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }
}
