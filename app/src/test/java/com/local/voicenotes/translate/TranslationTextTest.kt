package com.local.voicenotes.translate

import com.local.voicenotes.model.TargetLanguage
import com.local.voicenotes.model.TranslationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTextTest {
    @Test fun promptNamesTheTargetLanguage() {
        assertEquals(
            "Translate the following text into English. Output only the translation, nothing else.\n\nمرحبا",
            TranslationText.prompt("مرحبا", "English")
        )
    }

    @Test fun customLanguageValidation() {
        assertNull(TranslationText.customLanguageError(" French "))
        assertNotNull(TranslationText.customLanguageError("   "))
        assertNotNull(TranslationText.customLanguageError("x".repeat(TranslationText.MAX_LANGUAGE_CHARS + 1)))
        assertNull(TranslationText.customLanguageError("x".repeat(TranslationText.MAX_LANGUAGE_CHARS)))
        assertEquals("Swiss German", TranslationText.normalizeLanguage("  Swiss \t German "))
    }

    @Test fun translateIsOfferedForAnyNonEmptyTranscript() {
        val on = TranslationSettings(enabled = true)
        assertTrue(TranslationText.canOffer("Hallo zusammen", on, modelInstalled = true))
        assertTrue(TranslationText.canOffer("مرحبا", on, modelInstalled = true))
        assertFalse(TranslationText.canOffer("  ", on, modelInstalled = true))
        assertFalse(TranslationText.canOffer("Hallo", on, modelInstalled = false))
        assertFalse(TranslationText.canOffer("Hallo", on.copy(enabled = false), modelInstalled = true))
        assertFalse(TranslationText.canOffer("Hallo", on.copy(target = TargetLanguage.CUSTOM), modelInstalled = true))
    }

    @Test fun chunksStayUnderTheLimitAndKeepSentencesWhole() {
        val sentence = "هذه جملة عربية قصيرة للاختبار. "
        val text = sentence.repeat(60).trim() + "\n\nSecond paragraph? Yes."
        val chunks = TranslationText.chunks(text, maxChars = 200)
        assertTrue(chunks.all { it.text.length <= 200 })
        assertTrue(chunks.dropLast(1).all { it.text.endsWith(".") })
        assertTrue(chunks.first().newParagraph)
        assertEquals(listOf(true), chunks.filter { it.text.startsWith("Second") }.map { it.newParagraph })
        assertEquals(text.replace(Regex("\\s+"), ""), chunks.joinToString("") { it.text }.replace(Regex("\\s+"), ""))
    }

    @Test fun overlongWordsAreCut() {
        val chunks = TranslationText.chunks("a".repeat(25) + " bb", maxChars = 10)
        assertEquals(listOf("aaaaaaaaaa", "aaaaaaaaaa", "aaaaa bb"), chunks.map { it.text })
    }

    @Test fun joinUsesSpacesWithinAndLineBreaksBetweenParagraphs() {
        val chunks = listOf(
            TranslationText.Chunk("a", true), TranslationText.Chunk("b", false), TranslationText.Chunk("c", true)
        )
        assertEquals("A B\nC", TranslationText.join(chunks, listOf(" A ", "B", "C")))
        assertEquals("A\nC", TranslationText.join(chunks, listOf("A", " ", "C")))
        assertTrue(TranslationText.chunks(" \n ").isEmpty())
    }
}
