package desu.inugram.core.plugins

/**
 * Finds comments and string literals. Regex literals are not parsed: a quote inside one can
 * hide code until the next quote, so nothing here may assume a string literal is inert.
 */
object JsLiterals {
    /** Comments include their markers; strings exclude their quotes. */
    class Literal(val start: Int, val end: Int, val isComment: Boolean)

    fun find(source: String): List<Literal> {
        val result = ArrayList<Literal>()
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            val next = if (i + 1 < n) source[i + 1] else '\u0000'
            when {
                c == '/' && next == '/' -> {
                    val start = i
                    while (i < n && source[i] != '\n') i++
                    result.add(Literal(start, i, true))
                }
                c == '/' && next == '*' -> {
                    val start = i
                    i += 2
                    while (i < n && !(source[i] == '*' && i + 1 < n && source[i + 1] == '/')) i++
                    i = minOf(i + 2, n)
                    result.add(Literal(start, i, true))
                }
                c == '"' || c == '\'' || c == '`' -> {
                    val start = ++i
                    while (i < n && source[i] != c) {
                        if (source[i] == '\\') i++
                        i++
                    }
                    i = minOf(i, n)
                    result.add(Literal(start, i, false))
                    i++
                }
                else -> i++
            }
        }
        return result
    }

    /** Keeps quotes and newlines so line structure survives. */
    fun stripStringsAndComments(source: String): String {
        val out = StringBuilder(source.length)
        var last = 0
        for (literal in find(source)) {
            out.append(source, last, literal.start)
            for (i in literal.start until literal.end) {
                if (source[i] == '\n') out.append('\n')
            }
            last = literal.end
        }
        out.append(source, last, source.length)
        return out.toString()
    }

    /**
     * Replaces the contents of long base64/hex strings with [placeholder]. A `data:` URI prefix
     * stays visible. Only base64 characters may be hidden, so a misparsed "string" that is
     * actually code is never collapsed.
     */
    fun collapseEncodedStrings(source: String, minLength: Int, placeholder: (length: Int) -> String): String {
        val out = StringBuilder()
        var last = 0
        for (literal in find(source)) {
            if (literal.isComment || literal.end - literal.start < minLength) continue
            var start = literal.start
            if (source.startsWith("data:", start)) {
                val comma = source.indexOf(',', start)
                if (comma == -1 || comma >= literal.end) continue
                start = comma + 1
            }
            if (literal.end - start < minLength) continue
            if (!(start until literal.end).all { isBase64Char(source[it]) }) continue
            out.append(source, last, start).append(placeholder(literal.end - start))
            last = literal.end
        }
        if (last == 0) return source
        out.append(source, last, source.length)
        return out.toString()
    }

    private fun isBase64Char(c: Char) =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '=' || c == '-' || c == '_'
}
