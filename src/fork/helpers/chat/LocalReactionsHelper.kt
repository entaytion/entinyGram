package desu.inugram.helpers.chat

import android.content.Context
import desu.inugram.InuConfig
import desu.inugram.helpers.LocalPremiumHelper
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble
import java.util.concurrent.ConcurrentHashMap

// entiny: extra reactions beyond the server limit live only in prefs and are merged back into message reactions on this device
object LocalReactionsHelper {
    private const val PREFS = "inu_local_reactions"
    private const val SEP = '\u001f'

    private val prefs by lazy { ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val entries: ConcurrentHashMap<String, List<String>> by lazy {
        val map = ConcurrentHashMap<String, List<String>>()
        for ((key, value) in prefs.all) {
            val list = (value as? String)?.split(SEP)?.filter { it.isNotEmpty() } ?: continue
            if (list.isNotEmpty()) map[key] = list
        }
        map
    }

    private fun key(account: Int, owner: TLRPC.Message) = "$account:${MessageObject.getDialogId(owner)}:${owner.id}"

    private fun persist(key: String, list: List<String>) {
        if (list.isEmpty()) {
            if (entries.remove(key) == null) return
            prefs.edit().remove(key).apply()
        } else {
            entries[key] = list
            prefs.edit().putString(key, list.joinToString(SEP.toString())).apply()
        }
    }

    private fun toKey(reaction: TLRPC.Reaction?): String? = when (reaction) {
        is TLRPC.TL_reactionEmoji -> "e:" + reaction.emoticon
        is TLRPC.TL_reactionCustomEmoji -> "c:" + reaction.document_id
        else -> null
    }

    private fun fromKey(key: String): TLRPC.Reaction? = when {
        key.startsWith("e:") -> TLRPC.TL_reactionEmoji().also { it.emoticon = key.substring(2) }
        key.startsWith("c:") -> key.substring(2).toLongOrNull()?.let { id -> TLRPC.TL_reactionCustomEmoji().also { it.document_id = id } }
        else -> null
    }

    @JvmStatic
    fun maxCount(controller: MessagesController, base: Int): Int {
        if (!InuConfig.LOCAL_REACTIONS.value) return base
        return maxOf(base, controller.reactionsUserMaxPremium)
    }

    private fun serverMax(account: Int): Int {
        val controller = MessagesController.getInstance(account)
        return if (LocalPremiumHelper.isRealPremium(account)) controller.reactionsUserMaxPremium else controller.reactionsUserMaxDefault
    }

    @JvmStatic
    fun apply(account: Int, owner: TLRPC.Message?) {
        if (owner == null || !InuConfig.LOCAL_REACTIONS.value) return
        val locals = entries[key(account, owner)] ?: return
        var reactions = owner.reactions
        if (reactions == null) {
            reactions = TLRPC.TL_messageReactions()
            reactions.reactions_as_tags = MessageObject.getDialogId(owner) == UserConfig.getInstance(account).clientUserId
            owner.reactions = reactions
            owner.flags = owner.flags or 1048576
        }
        val results = reactions.results
        locals.forEachIndexed { index, k ->
            val order = index - locals.size
            val existing = results.firstOrNull { toKey(it.reaction) == k }
            if (existing != null) {
                if (!existing.chosen) {
                    existing.chosen = true
                    existing.flags = existing.flags or 1
                    existing.count++
                    existing.chosen_order = order
                }
            } else {
                val created = TLRPC.TL_reactionCount()
                created.reaction = fromKey(k) ?: return@forEachIndexed
                created.flags = 1
                created.chosen = true
                created.count = 1
                created.chosen_order = order
                results.add(created)
            }
        }
    }

    @JvmStatic
    fun splitForSend(
        account: Int,
        message: MessageObject,
        list: ArrayList<ReactionsLayoutInBubble.VisibleReaction>?,
    ): ArrayList<ReactionsLayoutInBubble.VisibleReaction>? {
        if (!InuConfig.LOCAL_REACTIONS.value) return list
        val owner = message.messageOwner ?: return list
        val results = owner.reactions?.results ?: return list
        val key = key(account, owner)
        val known = entries[key].orEmpty()
        val chosen = results
            .filter { it.chosen && toKey(it.reaction) != null }
            .sortedWith(compareByDescending<TLRPC.ReactionCount> { it.chosen_order }.thenBy { if (toKey(it.reaction) in known) 0 else 1 })
        val max = serverMax(account)
        if (chosen.size <= max) {
            persist(key, emptyList())
            return list
        }
        persist(key, chosen.drop(max).reversed().mapNotNull { toKey(it.reaction) })
        return ArrayList(chosen.take(max).map { ReactionsLayoutInBubble.VisibleReaction.fromTL(it.reaction) })
    }
}
