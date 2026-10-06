package desu.inugram.helpers.translate.engine

import desu.inugram.helpers.InuUtils
import org.telegram.tgnet.TLRPC

object EntityKeeper {

    // entiny: OPEN/CLOSE must not be a prefix of VAULT_OPEN (or vice versa) - a mangled vault
    // token surviving into unmark() must never be misread as an entity marker
    private const val OPEN = "<inue"
    private const val CLOSE = "</inue"
    private const val TAG_END = '>'

    // entiny: <inuxN> placeholder protects machine-readable tokens (urls, mentions) from being translated
    private const val VAULT_OPEN = "<inux"
    private val VAULT_TOKEN = Regex("<inux(\\d+)>")

    // entiny: matches every marker this file ever emits (entity + vault) - providers that need to
    // XML-escape the surrounding text (DeepL's tag_handling=xml) must skip exactly these spans
    val MARKER_PATTERN = Regex("</?inue\\d+>|<inux\\d+>")

    private val PROTECTED = listOf(
        Regex("""\b(?:https?|tg|ton)://[^\s<]+"""),
        Regex("""\bwww\.[^\s<]+"""),
        Regex("""\b(?:t|telegram)\.me/[^\s<]+"""),
        Regex("""\b[\w.+-]+@[\w-]+\.[\w.-]*\w"""),
        Regex("""(?<![\w@/])@[A-Za-z]\w{2,}"""),
        Regex("""(?<![\w#])#\w+"""),
    )

    fun protect(marked: String): Pair<String, List<String>> {
        if (marked.isEmpty()) return marked to emptyList()
        val vault = ArrayList<String>()
        var text = marked
        for (pattern in PROTECTED) {
            if (vault.size > MAX_PROTECTED) break
            text = pattern.replace(text) { m ->
                if (vault.size > MAX_PROTECTED) {
                    m.value
                } else {
                    vault.add(m.value)
                    "$VAULT_OPEN${vault.size - 1}$TAG_END"
                }
            }
        }
        return text to vault
    }

    fun restore(translated: String, vault: List<String>): String {
        if (vault.isEmpty() || translated.indexOf(VAULT_OPEN) < 0) return translated
        // entiny: Regex.replace lambda appends result verbatim, so restored url needs no $/\ escaping
        return VAULT_TOKEN.replace(translated) { m ->
            val idx = m.groupValues[1].toIntOrNull()
            if (idx != null && idx in vault.indices) vault[idx] else ""
        }
    }

    private const val MAX_PROTECTED = 64

    // entiny: returns the marked text plus the exact (filtered, index-stable) entity list the
    // markers reference - unmark() MUST be given this same list, never the caller's raw entities,
    // or marker index N resolves against the wrong entity once anything gets dropped/reordered
    fun mark(text: String, entities: List<TLRPC.MessageEntity>?): Pair<String, List<TLRPC.MessageEntity>> {
        if (text.isEmpty() || entities.isNullOrEmpty()) return text to emptyList()

        // entiny: drop crossing entities and sort by start asc / length desc to process parents first
        val sorted = entities
            .filter { it.offset >= 0 && it.length > 0 && it.offset + it.length <= text.length }
            .sortedWith(compareBy({ it.offset }, { -it.length }))
        val kept = ArrayList<TLRPC.MessageEntity>(sorted.size)
        var frontier = -1
        for (e in sorted) {
            val end = e.offset + e.length
            if (e.offset < frontier && end > frontier) continue
            kept.add(e)
            if (end > frontier) frontier = end
        }
        if (kept.isEmpty()) return text to emptyList()

        val events = ArrayList<IntArray>(kept.size * 2)
        for ((i, e) in kept.withIndex()) {
            events.add(intArrayOf(e.offset, 0, i))
            events.add(intArrayOf(e.offset + e.length, 1, i))
        }
        events.sortWith { a, b ->
            val byPos = a[0].compareTo(b[0])
            if (byPos != 0) return@sortWith byPos
            // entiny: closes before opens so adjacent spans close before next one opens
            val byKind = b[1].compareTo(a[1])
            if (byKind != 0) return@sortWith byKind
            val la = kept[a[2]].length
            val lb = kept[b[2]].length
            if (a[1] == 0) lb.compareTo(la) else la.compareTo(lb)
        }

        val sb = StringBuilder(text.length + events.size * 12)
        var pos = 0
        for (ev in events) {
            val at = ev[0].coerceIn(pos, text.length)
            sb.append(text, pos, at)
            pos = at
            if (ev[1] == 0) sb.append(OPEN).append(ev[2]).append(TAG_END)
            else sb.append(CLOSE).append(ev[2]).append(TAG_END)
        }
        sb.append(text, pos, text.length)
        return sb.toString() to kept
    }

    // entiny: `kept` must be the exact list mark() returned alongside `marked` - see mark() doc
    fun unmark(marked: String, kept: List<TLRPC.MessageEntity>?): Pair<String, ArrayList<TLRPC.MessageEntity>> {
        if (marked.isEmpty() || kept.isNullOrEmpty()) return marked to ArrayList()
        if (marked.indexOf(OPEN) < 0) return marked to ArrayList()

        val out = StringBuilder(marked.length)
        val result = ArrayList<TLRPC.MessageEntity>(kept.size)
        val stack = ArrayList<Int>(4)  // original indices of currently open markers
        val starts = ArrayList<Int>(4) // translated offset where each open marker began
        var i = 0
        val n = marked.length
        while (i < n) {
            val openAt = marked.indexOf(OPEN, i)
            val closeAt = marked.indexOf(CLOSE, i)
            val nextTag: Int
            val isClose: Boolean
            when {
                openAt < 0 && closeAt < 0 -> {
                    out.append(marked, i, n)
                    break
                }
                closeAt < 0 || (openAt >= 0 && openAt < closeAt) -> {
                    nextTag = openAt
                    isClose = false
                }
                else -> {
                    nextTag = closeAt
                    isClose = true
                }
            }
            out.append(marked, i, nextTag)

            // entiny: anything that isn't a well-formed <inueN>/</inueN> marker is dropped rather
            // than re-appended - a provider-mangled marker leaking into the visible message is
            // worse than losing the few stray characters of a malformed tag
            var j = nextTag + (if (isClose) CLOSE.length else OPEN.length)
            if (j >= n || !marked[j].isDigit()) {
                i = j
                continue
            }
            val idxStart = j
            while (j < n && marked[j].isDigit()) j++
            if (j >= n || marked[j] != TAG_END) {
                i = j
                continue
            }
            val idx = marked.substring(idxStart, j).toIntOrNull()
            if (idx == null || idx !in kept.indices) {
                i = j + 1
                continue
            }

            if (!isClose) {
                stack.add(idx)
                starts.add(out.length)
                i = j + 1
                continue
            }

            var match = -1
            for (k in stack.indices.reversed()) {
                if (stack[k] == idx) {
                    match = k
                    break
                }
            }
            if (match < 0) {
                i = j + 1
                continue
            }
            val start = starts.removeAt(match)
            stack.removeAt(match)
            val length = out.length - start
            if (length > 0) {
                cloneEntity(kept[idx], start, length)?.let { result.add(it) }
            }
            i = j + 1
        }

        result.sortWith(compareBy({ it.offset }, { -it.length }))
        return out.toString() to result
    }

    private fun cloneEntity(original: TLRPC.MessageEntity, offset: Int, length: Int): TLRPC.MessageEntity? {
        val clone = InuUtils.cloneTLObject(original, TLRPC.MessageEntity::TLdeserialize) as? TLRPC.MessageEntity ?: return null
        if (clone is TLRPC.TL_messageEntityCustomEmoji && original is TLRPC.TL_messageEntityCustomEmoji) {
            clone.document = original.document
        }
        clone.offset = offset
        clone.length = length
        return clone
    }
}
