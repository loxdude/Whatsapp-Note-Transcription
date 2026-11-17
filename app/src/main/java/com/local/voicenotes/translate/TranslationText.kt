package com.local.voicenotes.translate

import com.local.voicenotes.model.TranslationSettings

/** Pure text rules of the on-demand translation, kept Android-free for unit tests. */
object TranslationText {
    /**
     * Source-agnostic: transcripts can come from Parakeet, the Arabic model or Mistral. The
     * markers and the "even if" sentence stop E2B from answering in another language when the
     * text talks about one (it once "translated" an Arabic note about Standard Arabic into it).
     */
    fun prompt(text: String, language: String): String =
        "You are a translator. Translate the text between the markers into $language. " +
            "Write the whole answer in $language, even if the text mentions other languages. " +
            "Output only the translation.\n\n<<<\n$text\n>>>"

    const val MAX_LANGUAGE_CHARS = 40

    /** Collapses whitespace in a typed language name. */
    fun normalizeLanguage(input: String): String = input.trim().replace(Regex("\\s+"), " ")

    /** Error for a custom target language typed by the user, or null if it can be used. */
    fun customLanguageError(input: String): String? {
        val name = normalizeLanguage(input)
        return when {
            name.isEmpty() -> "Enter a language."
            name.length > MAX_LANGUAGE_CHARS -> "Use at most $MAX_LANGUAGE_CHARS characters."
            name.any(Character::isISOControl) -> "Use plain text."
            else -> null
        }
    }

    const val MAX_CHUNK_CHARS = 600

    /** One piece sent to the model; [newParagraph] marks the start of a source paragraph. */
    data class Chunk(val text: String, val newParagraph: Boolean)

    // Ends of sentences in Latin and Arabic script (Arabic question mark and full stop).
    private val sentenceEnd = Regex("(?<=[.!?\u061F\u06D4\u2026])\\s+")

    /**
     * Splits [text] into chunks of at most [maxChars] characters along paragraphs, then
     * sentences, then words. A single word longer than [maxChars] is cut hard.
     */
    fun chunks(text: String, maxChars: Int = MAX_CHUNK_CHARS): List<Chunk> {
        require(maxChars > 0)
        val result = ArrayList<Chunk>()
        text.split(Regex("\\n\\s*")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { paragraph ->
            val pieces = paragraph.split(sentenceEnd).flatMap { splitLong(it.trim(), maxChars) }.filter { it.isNotEmpty() }
            var current = StringBuilder()
            var first = true
            fun flush() {
                if (current.isEmpty()) return
                result += Chunk(current.toString(), newParagraph = first)
                first = false
                current = StringBuilder()
            }
            for (piece in pieces) {
                if (current.isNotEmpty() && current.length + 1 + piece.length > maxChars) flush()
                if (current.isNotEmpty()) current.append(' ')
                current.append(piece)
            }
            flush()
        }
        return result
    }

    private fun splitLong(sentence: String, maxChars: Int): List<String> {
        if (sentence.length <= maxChars) return listOf(sentence)
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (word in sentence.split(Regex("\\s+"))) {
            var rest = word
            while (rest.length > maxChars) {
                if (current.isNotEmpty()) { out += current.toString(); current.clear() }
                out += rest.take(maxChars)
                rest = rest.drop(maxChars)
            }
            if (rest.isEmpty()) continue
            if (current.isNotEmpty() && current.length + 1 + rest.length > maxChars) { out += current.toString(); current.clear() }
            if (current.isNotEmpty()) current.append(' ')
            current.append(rest)
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    /** Joins translated chunks: a space within a paragraph, a line break between paragraphs. */
    fun join(chunks: List<Chunk>, translations: List<String>): String {
        require(chunks.size == translations.size)
        val out = StringBuilder()
        chunks.forEachIndexed { index, chunk ->
            val piece = translations[index].trim()
            if (piece.isEmpty()) return@forEachIndexed
            if (out.isNotEmpty()) out.append(if (chunk.newParagraph) "\n" else " ")
            out.append(piece)
        }
        return out.toString()
    }

    /**
     * Translate is offered under any non-empty transcript (whatever engine made it) once the
     * feature is on, its model installed and its target language valid.
     */
    fun canOffer(transcript: String, settings: TranslationSettings, modelInstalled: Boolean): Boolean =
        settings.enabled && settings.languageReady && modelInstalled && transcript.isNotBlank()

    /** Output token budget per chunk, proportional to its length (a token covers several characters). */
    fun maxOutputTokens(chunk: String): Int = (chunk.length + 128).coerceIn(256, 1024)
}
