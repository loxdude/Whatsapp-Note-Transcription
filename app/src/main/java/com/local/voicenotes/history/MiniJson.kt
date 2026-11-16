package com.local.voicenotes.history

/**
 * Minimal JSON reader/writer for the history file. android's org.json is not usable in JVM
 * unit tests and no JSON library is available offline, so the store uses this small codec.
 * Values: null, Boolean, Long/Double (numbers), String, List<Any?>, Map<String, Any?>.
 */
internal object MiniJson {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is Int, is Long -> out.append(value)
            is Number -> out.append(value.toDouble())
            is String -> appendString(out, value)
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) out.append(',')
                    appendString(out, key as String)
                    out.append(':')
                    append(out, item)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    append(out, item)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("Unsupported JSON value: ${value::class}")
        }
    }

    private fun appendString(out: StringBuilder, value: String) {
        out.append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ' || c == '\u2028' || c == '\u2029') {
                    out.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", c.code))
                } else out.append(c)
            }
        }
        out.append('"')
    }

    /** Parses [text]; throws [IllegalArgumentException] on malformed input. */
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.value()
        parser.skipWhitespace()
        require(parser.atEnd()) { "Trailing characters at ${parser.pos}" }
        return value
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun peek(): Char {
            require(pos < s.length) { "Unexpected end of JSON" }
            return s[pos]
        }

        private fun expect(c: Char) {
            require(peek() == c) { "Expected '$c' at $pos" }
            pos++
        }

        fun value(): Any? {
            skipWhitespace()
            return when (val c = peek()) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, pos)) { "Invalid literal at $pos" }
            pos += word.length
            return value
        }

        private fun number(): Number {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val token = s.substring(start, pos)
            return token.toLongOrNull() ?: token.toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid number '$token' at $start")
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = peek()
                pos++
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        val e = peek()
                        pos++
                        when (e) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                require(pos + 4 <= s.length) { "Truncated escape at $pos" }
                                out.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Invalid escape '\\$e' at $pos")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun array(): List<Any?> {
            expect('[')
            val items = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { pos++; return items }
            while (true) {
                items += value()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return items }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { pos++; return map }
            while (true) {
                skipWhitespace()
                val key = string()
                skipWhitespace()
                expect(':')
                map[key] = value()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
        }
    }
}
