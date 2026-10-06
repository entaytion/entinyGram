package desu.inugram.helpers.font

import desu.inugram.helpers.font.SfntParser.Script

data class StyleCoverage(val regular: Boolean, val bold: Boolean, val upright: Boolean, val italic: Boolean) {
    infix fun or(o: StyleCoverage) =
        StyleCoverage(regular || o.regular, bold || o.bold, upright || o.upright, italic || o.italic)
}

internal object StackCoverage {
    fun style(primary: String?, fallbacks: List<String>): StyleCoverage {
        if (primary == null || primary == FontConfig.SYSTEM_STACK_ID) return StyleCoverage(true, true, true, true)
        var c = StyleCoverage(false, false, false, false)
        for (tok in listOf(primary) + fallbacks) FontLibrary.getStyleCoverageFor(FontId.parse(tok))?.let { c = c or it }
        return c
    }

    fun scripts(primary: String?, fallbacks: List<String>): Set<Script>? {
        if (primary == null || primary == FontConfig.SYSTEM_STACK_ID) return null
        val out = HashSet<Script>()
        for (tok in listOf(primary) + fallbacks) FontLibrary.getScriptCoverageFor(FontId.parse(tok))?.let { out.addAll(it) }
        return out
    }
}
