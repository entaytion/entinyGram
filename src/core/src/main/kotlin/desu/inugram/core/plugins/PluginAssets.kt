package desu.inugram.core.plugins

import java.util.Base64

/** [start]..[end] is the base64 payload in the source, line breaks included */
class PluginAsset(val name: String, val mime: String, val start: Int, val end: Int, val size: Long)

class PluginAssetsException(message: String) : Exception(message)

/**
 * The asset footer is a block comment after the code, so the file stays valid JS:
 * ```
 * /* ==InuAssets==
 * @asset icons/frame.png image/png
 * iVBORw0KGgo...
 * ==/InuAssets== */
 * ```
 * Base64 has no `*`, so a payload cannot close the comment.
 */
object PluginAssetsParser {
    private const val START = "/* ==InuAssets=="
    private const val END = "==/InuAssets== */"
    private const val DIRECTIVE = "@asset"
    private val MIME = Regex("""[\w.+-]+/[\w.+-]+""")

    /** where the footer starts, or the source length when there is none */
    fun findFooter(source: String): Int {
        if (!source.trimEnd().endsWith(END)) return source.length
        val start = source.lastIndexOf(START)
        if (start == -1 || (start > 0 && source[start - 1] != '\n')) return source.length
        return start
    }

    fun parse(source: String): List<PluginAsset> {
        val footer = findFooter(source)
        if (footer == source.length) {
            if (source.trimEnd().endsWith(END)) throw PluginAssetsException("asset footer has no $START line")
            return emptyList()
        }
        val assets = ArrayList<PluginAsset>()
        val names = HashSet<String>()
        var name: String? = null
        var mime = ""
        var dataStart = 0
        var dataEnd = 0
        var chars = 0L
        var padding = 0

        fun finish() {
            val current = name ?: return
            if (chars % 4 == 1L) throw PluginAssetsException("asset $current: truncated base64")
            assets.add(PluginAsset(current, mime, dataStart, dataEnd, chars * 3 / 4 - padding))
        }

        var lineStart = source.indexOf('\n', footer) + 1
        if (lineStart == 0 || source.substring(footer, lineStart).trim() != START) {
            throw PluginAssetsException("$START must be on a line of its own")
        }
        while (true) {
            val newline = source.indexOf('\n', lineStart)
            val lineEnd = if (newline == -1) source.length else newline
            val line = source.substring(lineStart, lineEnd).trim()
            when {
                line == END -> {
                    finish()
                    if (source.substring(lineEnd).isNotBlank()) throw PluginAssetsException("text after $END")
                    return assets
                }
                line.startsWith("$DIRECTIVE ") -> {
                    finish()
                    val parts = line.split(Regex("""\s+"""))
                    if (parts.size != 3) throw PluginAssetsException("expected `$DIRECTIVE <name> <mime>`, got `$line`")
                    val assetName = parts[1]
                    if (assetName.any { it.isISOControl() } || "*/" in assetName) {
                        throw PluginAssetsException("asset name $assetName has control characters or `*/`")
                    }
                    if (!names.add(assetName)) throw PluginAssetsException("duplicate asset $assetName")
                    if (!MIME.matches(parts[2])) throw PluginAssetsException("asset $assetName: bad mime type ${parts[2]}")
                    name = assetName
                    mime = parts[2]
                    dataStart = lineEnd + 1
                    dataEnd = dataStart
                    chars = 0
                    padding = 0
                }
                line.isEmpty() -> Unit
                else -> {
                    val current = name ?: throw PluginAssetsException("data before the first $DIRECTIVE")
                    for (c in line) {
                        when {
                            c == '=' -> padding++
                            padding > 0 || !isBase64Char(c) -> throw PluginAssetsException("asset $current: invalid base64")
                        }
                        chars++
                    }
                    if (padding > 2) throw PluginAssetsException("asset $current: invalid base64")
                    dataEnd = lineEnd
                }
            }
            if (newline == -1) throw PluginAssetsException("unterminated asset footer")
            lineStart = newline + 1
        }
    }

    fun decode(source: String, asset: PluginAsset): ByteArray =
        Base64.getMimeDecoder().decode(source.substring(asset.start, asset.end).toByteArray(Charsets.US_ASCII))

    private fun isBase64Char(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/'
}
