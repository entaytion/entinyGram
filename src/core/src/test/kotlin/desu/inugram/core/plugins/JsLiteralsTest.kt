package desu.inugram.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Test

class JsLiteralsTest {
    private val payload = "iVBORw0KGgo+/=".repeat(100)

    private fun collapse(source: String) = JsLiterals.collapseEncodedStrings(source, 1000) { "<$it>" }

    @Test
    fun a_long_base64_string_is_collapsed_and_the_code_around_it_kept() {
        assertEquals("const a = '<1400>'; draw(a)\n", collapse("const a = '$payload'; draw(a)\n"))
    }

    @Test
    fun a_data_uri_prefix_stays_visible() {
        assertEquals("x(`data:image/png;base64,<1400>`)", collapse("x(`data:image/png;base64,$payload`)"))
    }

    @Test
    fun a_long_string_with_non_base64_characters_is_kept() {
        val source = "const a = \"${"hello world ".repeat(100)}\""
        assertEquals(source, collapse(source))
    }

    @Test
    fun code_misparsed_as_a_string_after_a_regex_quote_is_kept() {
        val source = "const r = /'/; ${"steal(x);".repeat(200)} const s = ''"
        assertEquals(source, collapse(source))
    }

    @Test
    fun a_short_base64_string_and_a_long_comment_are_kept() {
        val source = "const a = 'QUJD' // $payload\n/* $payload */"
        assertEquals(source, collapse(source))
    }
}
