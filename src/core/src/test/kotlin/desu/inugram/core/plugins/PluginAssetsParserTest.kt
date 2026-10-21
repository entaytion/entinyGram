package desu.inugram.core.plugins

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginAssetsParserTest {
    private val code = "// ==InuPlugin==\n// @name Test\n// ==/InuPlugin==\nconst a = '/* ==InuAssets== */'\n"

    private fun footer(vararg lines: String) = "/* ==InuAssets==\n${lines.joinToString("\n")}\n==/InuAssets== */\n"

    @Test
    fun assets_split_off_the_code_and_decode_across_wrapped_lines() {
        val source = code + footer("@asset icons/a.png image/png", "AAEC", "/w==", "@asset empty.bin application/octet-stream")
        assertEquals(code, source.substring(0, PluginAssetsParser.findFooter(source)))
        val assets = PluginAssetsParser.parse(source)
        assertEquals(listOf("icons/a.png", "empty.bin"), assets.map { it.name })
        assertEquals(listOf("image/png", "application/octet-stream"), assets.map { it.mime })
        assertEquals(listOf(4L, 0L), assets.map { it.size })
        assertArrayEquals(byteArrayOf(0, 1, 2, -1), PluginAssetsParser.decode(source, assets[0]))
        assertEquals(0, PluginAssetsParser.decode(source, assets[1]).size)
    }

    @Test
    fun a_source_without_a_footer_is_all_code() {
        assertEquals(code.length, PluginAssetsParser.findFooter(code))
        assertTrue(PluginAssetsParser.parse(code).isEmpty())
    }

    @Test
    fun crlf_line_endings_are_accepted() {
        val source = (code + footer("@asset a.bin application/octet-stream", "AAEC")).replace("\n", "\r\n")
        assertArrayEquals(byteArrayOf(0, 1, 2), PluginAssetsParser.decode(source, PluginAssetsParser.parse(source).single()))
    }

    @Test
    fun a_malformed_footer_is_refused() {
        val broken = listOf(
            footer("@asset a.png image/png", "AA EC"),
            footer("@asset a.png image/png", "AA=C"),
            footer("@asset a.png image/png", "AAECA"),
            footer("@asset a.png", "AAEC"),
            footer("@asset a.png not-a-mime", "AAEC"),
            footer("@asset a*/b.png image/png", "AAEC"),
            footer("@asset a.png image/png", "@asset a.png image/png"),
            footer("AAEC"),
            "==/InuAssets== */\n",
        )
        for (tail in broken) {
            assertThrows(tail, PluginAssetsException::class.java) { PluginAssetsParser.parse(code + tail) }
        }
    }
}
