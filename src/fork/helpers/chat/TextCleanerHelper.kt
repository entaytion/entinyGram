package desu.inugram.helpers.chat

import desu.inugram.InuConfig
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.SerializedData
import org.telegram.tgnet.TLRPC
import java.util.WeakHashMap

object TextCleanerHelper {
    private class Cached(val version: Int, val text: String, val editDate: Int, val result: TLRPC.TL_textWithEntities?)

    private val cache = WeakHashMap<TLRPC.Message, Cached>()

    @JvmStatic
    fun getRewrite(message: MessageObject): TLRPC.TL_textWithEntities? {
        // #if PLUGINS
        desu.inugram.helpers.plugins.telegram.PluginMessageFilters.getRewrite(message)?.let { return it }
        // #endif
        return clean(message)
    }

    private fun clean(message: MessageObject): TLRPC.TL_textWithEntities? {
        val regex = InuConfig.REGEX_FILTER_ENABLED.value
        val zalgo = InuConfig.ZALGO_FILTER.value
        if (!regex && !zalgo) return null
        val owner = message.messageOwner ?: return null
        val text = owner.message
        if (text.isNullOrEmpty() || owner.action != null) return null
        val version = (if (regex) RegexFilterHelper.cleanVersion else 0) * 4 + (if (regex) 2 else 0) + (if (zalgo) 1 else 0)
        synchronized(cache) {
            cache[owner]?.let {
                if (it.version == version && it.editDate == owner.edit_date && it.text == text) return it.result
            }
        }
        val result = compute(message, owner, text, regex, zalgo)
        synchronized(cache) { cache[owner] = Cached(version, text, owner.edit_date, result) }
        return result
    }

    private fun compute(message: MessageObject, owner: TLRPC.Message, text: String, regex: Boolean, zalgo: Boolean): TLRPC.TL_textWithEntities? {
        val patterns = if (regex) RegexFilterHelper.cleanPatterns(message.dialogId) else emptyList()
        if (patterns.isEmpty() && !zalgo) return null
        val cut = findCuts(text, patterns, zalgo) ?: return null
        val cleaned = applyCuts(text, cut)
        if (cleaned.isBlank() && (message.type == MessageObject.TYPE_TEXT || message.type == MessageObject.TYPE_EMOJIS)) return null
        val result = TLRPC.TL_textWithEntities()
        result.text = cleaned
        result.entities = shiftEntities(owner.entities, cut)
        return result
    }

    fun preview(text: String, patterns: List<java.util.regex.Pattern>): String? =
        findCuts(text, patterns)?.let { applyCuts(text, it) }

    private fun findCuts(text: String, patterns: List<java.util.regex.Pattern>, zalgo: Boolean = false): List<IntArray>? {
        val ranges = ArrayList<IntArray>()
        for (pattern in patterns) {
            try {
                val matcher = pattern.matcher(text)
                while (matcher.find()) {
                    if (matcher.end() > matcher.start()) ranges.add(intArrayOf(matcher.start(), matcher.end()))
                }
            } catch (_: Exception) {
            }
        }
        val widened = ranges.map { widen(text, it) }.toMutableList()
        if (zalgo) zalgoRanges(text, widened)
        if (widened.isEmpty()) return null
        return mergeRanges(widened)
    }

    // entiny: only the combining diacritics blocks, past two in a row, so real accents, emoji selectors and keycaps stay
    private fun zalgoRanges(text: String, out: MutableList<IntArray>) {
        var run = 0
        var start = -1
        for (i in text.indices) {
            if (isCombining(text[i])) {
                run++
                if (run == ZALGO_KEEP + 1) start = i
            } else {
                if (start >= 0) out.add(intArrayOf(start, i))
                run = 0
                start = -1
            }
        }
        if (start >= 0) out.add(intArrayOf(start, text.length))
    }

    private fun isCombining(c: Char): Boolean =
        c in '̀'..'ͯ' || c in '᪰'..'᫿' || c in '᷀'..'᷿' || (c in '⃐'..'⃿' && c != '⃣') || c in '︠'..'︯'

    private const val ZALGO_KEEP = 2

    private fun applyCuts(text: String, cut: List<IntArray>): String {
        val out = StringBuilder(text.length)
        var last = 0
        for (r in cut) {
            out.append(text, last, r[0])
            last = r[1]
        }
        out.append(text, last, text.length)
        return out.toString()
    }

    // entiny: swallow the gap a cut leaves so no double spaces or empty lines stay behind
    private fun widen(text: String, range: IntArray): IntArray {
        var s = range[0]
        var e = range[1]
        while (e < text.length && (text[e] == ' ' || text[e] == '\t')) e++
        val lineStart = s == 0 || text[s - 1] == '\n'
        val lineEnd = e == text.length || text[e] == '\n'
        if (lineStart && lineEnd) {
            if (e < text.length) e++ else if (s > 0) s--
        }
        if (s == 0) while (e < text.length && text[e].isWhitespace()) e++
        if (e == text.length) while (s > 0 && text[s - 1].isWhitespace()) s--
        return intArrayOf(s, e)
    }

    private fun mergeRanges(ranges: List<IntArray>): List<IntArray> {
        val merged = ArrayList<IntArray>()
        for (r in ranges.sortedBy { it[0] }) {
            val prev = merged.lastOrNull()
            if (prev != null && r[0] <= prev[1]) prev[1] = maxOf(prev[1], r[1]) else merged.add(intArrayOf(r[0], r[1]))
        }
        return merged
    }

    private fun shiftOffset(pos: Int, cut: List<IntArray>): Int {
        var removed = 0
        for (r in cut) {
            if (r[0] >= pos) break
            removed += minOf(r[1], pos) - r[0]
        }
        return pos - removed
    }

    private fun shiftEntities(entities: List<TLRPC.MessageEntity>?, cut: List<IntArray>): ArrayList<TLRPC.MessageEntity> {
        val out = ArrayList<TLRPC.MessageEntity>()
        if (entities == null) return out
        for (entity in entities) {
            val start = shiftOffset(entity.offset, cut)
            val end = shiftOffset(entity.offset + entity.length, cut)
            if (end <= start) continue
            val copy = copy(entity) ?: continue
            copy.offset = start
            copy.length = end - start
            out.add(copy)
        }
        return out
    }

    private fun copy(entity: TLRPC.MessageEntity): TLRPC.MessageEntity? {
        val write = SerializedData()
        entity.serializeToStream(write)
        val read = SerializedData(write.toByteArray())
        return TLRPC.MessageEntity.TLdeserialize(read, read.readInt32(true), true)
    }
}
