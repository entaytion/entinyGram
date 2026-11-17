package desu.inugram.helpers.archive

import desu.inugram.InuConfig
import org.telegram.messenger.ChatObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory
import java.util.Collections
import java.util.WeakHashMap

object KeepRemovedChats {

    private const val KEY = "inu_kept_removed_chats"
    private val told: MutableSet<BaseFragment> = Collections.newSetFromMap(WeakHashMap())

    private fun removed(chat: TLRPC.Chat?): Boolean =
        chat != null && !chat.deactivated && chat.migrated_to == null &&
            (chat is TLRPC.TL_channelForbidden || chat is TLRPC.TL_chatForbidden || chat.kicked)

    private fun kept(account: Int): Set<String> =
        UserConfig.getInstance(account).preferences.getStringSet(KEY, null) ?: emptySet()

    @JvmStatic
    fun isKept(account: Int, chat: TLRPC.Chat?): Boolean =
        InuConfig.KEEP_REMOVED_CHATS.value && removed(chat) && (-chat!!.id).toString() in kept(account)

    @JvmStatic
    @Synchronized
    fun keep(account: Int, chat: TLRPC.Chat?): Boolean {
        if (!InuConfig.KEEP_REMOVED_CHATS.value || !removed(chat)) return false
        val id = (-chat!!.id).toString()
        val ids = kept(account)
        if (id !in ids) UserConfig.getInstance(account).preferences.edit().putStringSet(KEY, HashSet(ids + id)).commit()
        return true
    }

    @JvmStatic
    fun notInChat(account: Int, chat: TLRPC.Chat?): Boolean =
        ChatObject.isNotInChat(chat) && !isKept(account, chat)

    @JvmStatic
    @Synchronized
    fun forget(account: Int, dialogId: Long, onlyHistory: Int) {
        if (dialogId >= 0 || onlyHistory != 0 && onlyHistory != 3) return
        val ids = kept(account)
        val id = dialogId.toString()
        if (id in ids) UserConfig.getInstance(account).preferences.edit().putStringSet(KEY, HashSet(ids - id)).apply()
    }

    @JvmStatic
    fun onCantLoad(fragment: BaseFragment, chat: TLRPC.Chat?): Boolean {
        val fresh = chat?.let { MessagesController.getInstance(fragment.currentAccount).getChat(it.id) } ?: chat
        if (!isKept(fragment.currentAccount, fresh)) return false
        if (!told.add(fragment)) return true
        BulletinFactory.of(fragment)
            .createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.InuKeepRemovedChatsOpened))
            .show()
        return true
    }
}
