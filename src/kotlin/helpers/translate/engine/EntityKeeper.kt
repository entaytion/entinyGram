package desu.inugram.helpers.translate.engine

import desu.inugram.helpers.InuUtils
import org.telegram.tgnet.TLRPC

/**
 * Preserves [TLRPC.MessageEntity] formatting across a text → translation → text round trip.
 *
 * Entities are wrapped into sentinel `<inuN>…</inuN>` markup before sending the text to a
 * translation provider and unwrapped afterwards by mapping translated spans back onto cloned
 * entities. Providers are instructed (or naturally inclined) to keep such markup intact, and
 * anything that gets mangled is dropped rather than crashing the message layout.
 */
object EntityKeeper {

    private const val OPEN = "<inu"
    private const val CLOSE = "</inu"
    private const val TAG_END = '>'

    /** Wraps entity ranges of [text] in `<inuN>` markers, resolving crossing overlaps safely. */
    fun mark(text: String, entities: List<TLRPC.MessageEntity>?): String {
        if (text.isEmpty() || entities.isNullOrEmpty()) return text

        // Drop entities that cross an already-open span (they can't be nested in valid markup).
        // Sorting by start asc / length desc processes parents before their children.
        val sorted = entities
            .filter { it.offset >= 0 && it.length > 0 && it.offset + it.length <= text.length }
            .sortedWith(compareBy({ it.offset }, { -it.length }))
        val kept = ArrayList<TLRPC.MessageEntity>(sorted.size)
        var frontier = -1
        for (e in sorted) {
            val end = e.offset + e.length
            if (e.offset < frontier && end > frontier) continue // crossing → drop
            kept.add(e)
            if (end > frontier) frontier = end
        }
        if (kept.isEmpty()) return text

        // Events: [position, kind (0 = open, 1 = close), keptIndex]
        val events = ArrayList<IntArray>(kept.size * 2)
        for ((i, e) in kept.withIndex()) {
            events.add(intArrayOf(e.offset, 0, i))
            events.add(intArrayOf(e.offset + e.length, 1, i))
        }
        events.sortWith { a, b ->
            val byPos = a[0].compareTo(b[0])
            if (byPos != 0) return@sortWith byPos
            // closes (1) before opens (0): a span ending here and one starting here are adjacent,
            // never nested, so the previous span must close before the next one opens
            val byKind = b[1].compareTo(a[1])
            if (byKind != 0) return@sortWith byKind
            val la = kept[a[2]].length
            val lb = kept[b[2]].length
            if (a[1] == 0) lb.compareTo(la) else la.compareTo(lb) // longest opens first, shortest closes first
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
        return sb.toString()
    }

    /**
     * Unwraps `<inuN>` markup left in [marked] after translation and rebuilds entities on the
     * translated text. Entities whose markers were mangled by the provider are skipped.
     */
    fun unmark(marked: String, originalEntities: List<TLRPC.MessageEntity>?): Pair<String, ArrayList<TLRPC.MessageEntity>> {
        if (marked.isEmpty() || originalEntities.isNullOrEmpty()) return marked to ArrayList()
        if (marked.indexOf(OPEN) < 0) return marked to ArrayList()

        val out = StringBuilder(marked.length)
        val result = ArrayList<TLRPC.MessageEntity>(originalEntities.size)
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
            // plain text before the tag is translated output
            out.append(marked, i, nextTag)

            var j = nextTag + (if (isClose) CLOSE.length else OPEN.length)
            if (j >= n || !marked[j].isDigit()) {
                out.append(marked, nextTag, j) // stray text, keep literally
                i = j
                continue
            }
            val idxStart = j
            while (j < n && marked[j].isDigit()) j++
            if (j >= n || marked[j] != TAG_END) {
                out.append(marked, nextTag, j)
                i = j
                continue
            }
            val idx = marked.substring(idxStart, j).toIntOrNull()
            if (idx == null || idx !in originalEntities.indices) {
                out.append(marked, nextTag, j + 1)
                i = j + 1
                continue
            }

            if (!isClose) {
                stack.add(idx)
                starts.add(out.length)
                i = j + 1
                continue
            }

            // close tag: the span ends at the current output length (the tag itself is not part of it)
            var match = -1
            for (k in stack.indices.reversed()) {
                if (stack[k] == idx) {
                    match = k
                    break
                }
            }
            if (match < 0) {
                out.append(marked, nextTag, j + 1) // orphan close → literal text
                i = j + 1
                continue
            }
            val start = starts.removeAt(match)
            stack.removeAt(match)
            val length = out.length - start
            if (length > 0) {
                cloneEntity(originalEntities[idx], start, length)?.let { result.add(it) }
            }
            i = j + 1
        }

        // Entities close in nesting order (inner first), so sort by translated position.
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
