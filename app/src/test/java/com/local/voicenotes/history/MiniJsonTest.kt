package com.local.voicenotes.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MiniJsonTest {
    @Test fun roundTripsNestedValues() {
        val value = linkedMapOf(
            "s" to "a\"b\\c\n\u0000é😀", "n" to 12345678901234L, "neg" to -3L, "d" to 1.5,
            "t" to true, "f" to false, "z" to null, "list" to listOf(1L, "x", emptyList<Any>(), emptyMap<String, Any>())
        )
        assertEquals(value, MiniJson.parse(MiniJson.write(value)))
    }

    @Test fun parsesWhitespaceAndEscapes() {
        assertEquals(mapOf("k" to listOf("\u00e9/")), MiniJson.parse(" { \"k\" : [ \"\\u00e9\\/\" ] } "))
    }

    @Test fun rejectsMalformedInput() {
        listOf("", "{", "[1,]", "{\"a\" 1}", "tru", "\"open", "{} x", "[1 2]", "\"\\q\"").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { MiniJson.parse(input) }
        }
    }
}
