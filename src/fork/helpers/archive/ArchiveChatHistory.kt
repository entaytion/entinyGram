package desu.inugram.helpers.archive

import android.os.Bundle
import desu.inugram.InuConfig
import desu.inugram.helpers.archive.EditHistoryHelper.EditEntry
import desu.inugram.helpers.chat.ChatHelper
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import org.telegram.SQLite.SQLiteDatabase
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.BulletinFactory

object ArchiveChatHistory {
    private const val TAG = "ArchiveChatHistory"
    private const val ARG_DIALOG = "inu_archive_dialog"
    private const val ARG_KIND = "inu_archive_kind"
    private const val ARG_MSG = "inu_archive_msg"
    private const val PAGE_SIZE = 50
    private const val MENU_SWITCH = 0x494e0001
    private const val MENU_CLEAR = 0x494e0002
    private const val SYNTHETIC_BASE = 1_900_000_000
    private const val OPTION_DELETE_ENTRY = 800_001
    private const val DIFF_LIMIT = 1_500_000L
    private val TOKEN = Regex("\\s+|[\\p{L}\\p{N}_]+|.", RegexOption.DOT_MATCHES_ALL)

    const val KIND_DELETED = 0
    const val KIND_EDITED = 1

    private class Screen(val account: Int, val dialogId: Long, var kind: Int, val msgId: Int) {
        var offset = 0
        var loaded = false
        val rowIds = HashMap<Int, Long>()
    }

    private val screens = WeakHashMap<ChatActivity, Screen>()

    @JvmStatic
    @JvmOverloads
    fun create(dialogId: Long, kind: Int, msgId: Int = 0): ChatActivity = ChatActivity(Bundle().apply {
        putInt("chatMode", ChatActivity.inu_MODE_ARCHIVE)
        putLong(ARG_DIALOG, dialogId)
        putInt(ARG_KIND, kind)
        putInt(ARG_MSG, msgId)
    })

    @JvmStatic
    @JvmOverloads
    fun open(fragment: BaseFragment, dialogId: Long, kind: Int, msgId: Int = 0) {
        fragment.presentFragment(create(dialogId, kind, msgId))
    }

    @JvmStatic
    fun hasMenu(activity: ChatActivity): Boolean = screens[activity]?.msgId == 0

    @JvmStatic
    fun fillMenu(activity: ChatActivity, item: ActionBarMenuItem) {
        val screen = screens[activity] ?: return
        val edited = screen.kind == KIND_EDITED
        item.lazilyAddSubItem(
            MENU_SWITCH,
            if (edited) R.drawable.msg_delete else R.drawable.msg_edit,
            LocaleController.getString(if (edited) R.string.InuCacheDeletedCategory else R.string.InuCacheEditsCategory),
        )
        item.lazilyAddSubItem(MENU_CLEAR, R.drawable.msg_clear, LocaleController.getString(R.string.InuCacheClearAll))
            .setColors(Theme.getColor(Theme.key_text_RedRegular), Theme.getColor(Theme.key_text_RedRegular))
    }

    @JvmStatic
    fun onMenuItemClick(activity: ChatActivity, id: Int): Boolean {
        val screen = screens[activity] ?: return false
        when (id) {
            MENU_SWITCH -> activity.presentFragment(
                create(screen.dialogId, if (screen.kind == KIND_EDITED) KIND_DELETED else KIND_EDITED),
                true,
                true,
            )
            MENU_CLEAR -> confirmClear(activity, screen)
            else -> return false
        }
        return true
    }

    private fun confirmClear(activity: ChatActivity, screen: Screen) {
        val context = activity.parentActivity ?: return
        activity.showDialog(
            AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.InuCacheClearAll))
                .setMessage(subtitle(activity))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Delete)) { _, _ ->
                    val done = Runnable {
                        BulletinFactory.global()?.createSimpleBulletin(R.raw.ic_delete, LocaleController.getString(R.string.InuCacheClearAllDone))?.show()
                        activity.finishFragment()
                    }
                    if (screen.kind == KIND_EDITED) {
                        ArchiveCache.clearEditHistory(screen.account, listOf(screen.dialogId), done)
                    } else {
                        ArchiveCache.clearCache(screen.account, listOf(screen.dialogId), deletedOnly = true, onDone = done)
                    }
                }
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .create()
        )
    }

    @JvmStatic
    fun attach(activity: ChatActivity, arguments: Bundle): Boolean {
        if (!arguments.containsKey(ARG_DIALOG)) return false
        val screen = Screen(activity.currentAccount, arguments.getLong(ARG_DIALOG), arguments.getInt(ARG_KIND), arguments.getInt(ARG_MSG))
        ensurePeer(screen.account, screen.dialogId)
        screens[activity] = screen
        return true
    }

    @JvmStatic
    fun onDestroyed(activity: ChatActivity) {
        screens.remove(activity)
    }

    @JvmStatic
    fun title(activity: ChatActivity): CharSequence {
        val screen = screens[activity] ?: return ""
        val controller = MessagesController.getInstance(screen.account)
        return when {
            screen.dialogId == 0L || screen.dialogId == UserConfig.getInstance(screen.account).clientUserId ->
                LocaleController.getString(R.string.SavedMessages)
            screen.dialogId > 0 -> controller.getUser(screen.dialogId)?.let { UserObject.getUserName(it) }
            else -> controller.getChat(-screen.dialogId)?.title
        } ?: "ID ${screen.dialogId}"
    }

    @JvmStatic
    fun subtitle(activity: ChatActivity): CharSequence? {
        val screen = screens[activity] ?: return null
        return LocaleController.getString(if (screen.kind == KIND_EDITED) R.string.InuCacheEditsCategory else R.string.InuCacheDeletedCategory)
    }

    @JvmStatic
    fun applyAvatar(activity: ChatActivity, view: BackupImageView) {
        val screen = screens[activity] ?: return
        val controller = MessagesController.getInstance(screen.account)
        val drawable = AvatarDrawable()
        val self = screen.dialogId == 0L || screen.dialogId == UserConfig.getInstance(screen.account).clientUserId
        when {
            self -> {
                drawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_SAVED)
                view.setImageDrawable(drawable)
            }
            screen.dialogId > 0 -> controller.getUser(screen.dialogId).let { drawable.setInfo(screen.account, it); view.setForUserOrChat(it, drawable) }
            else -> controller.getChat(-screen.dialogId).let { drawable.setInfo(screen.account, it); view.setForUserOrChat(it, drawable) }
        }
    }

    @JvmStatic
    fun showsAvatars(activity: ChatActivity): Boolean {
        val screen = screens[activity] ?: return false
        if (screen.dialogId >= 0) return false
        val chat = MessagesController.getInstance(screen.account).getChat(-screen.dialogId)
        return chat == null || !ChatObject.isChannelAndNotMegaGroup(chat)
    }

    @JvmStatic
    fun buttonText(activity: ChatActivity): CharSequence? {
        val screen = screens[activity] ?: return null
        if (MessagesController.getInstance(screen.account).dialogs_dict.get(screen.dialogId) == null) return null
        return LocaleController.getString(R.string.InuArchiveOpenChat)
    }

    @JvmStatic
    fun onButtonClick(activity: ChatActivity) {
        val screen = screens[activity] ?: return
        activity.presentFragment(ChatActivity(Bundle().apply {
            if (screen.dialogId > 0) putLong("user_id", screen.dialogId) else putLong("chat_id", -screen.dialogId)
        }))
    }

    // entiny: the screen has no peer, so only options that never send a message id survive
    private val MESSAGE_OPTIONS = setOf(
        ChatActivity.OPTION_COPY, ChatActivity.OPTION_SAVE_TO_GALLERY, ChatActivity.OPTION_SAVE_TO_GALLERY2,
        ChatActivity.OPTION_SAVE_TO_DOWNLOADS_OR_MUSIC, ChatActivity.OPTION_SHARE, ChatActivity.OPTION_ADD_TO_STICKERS_OR_MASKS,
        ChatActivity.OPTION_ADD_TO_GIFS, ChatActivity.OPTION_ADD_STICKER_TO_FAVORITES, ChatActivity.OPTION_DELETE_STICKER_FROM_FAVORITES,
        ChatActivity.OPTION_OPEN_PROFILE, ChatActivity.OPTION_TRANSLATE,
        ChatHelper.OPTION_TRANSLATE_REVERT, ChatHelper.OPTION_DETAILS, ChatHelper.OPTION_SHOW_JSON, ChatHelper.OPTION_SHOW_IN_CHAT,
        ChatHelper.OPTION_COPY_MEDIA, ChatHelper.OPTION_SAVE_STICKER_TO_DOWNLOADS, ChatHelper.OPTION_SAVE_VOICE_TO_DOWNLOADS,
    )

    @JvmStatic
    fun filterMessageMenu(activity: ChatActivity, selected: MessageObject, items: ArrayList<CharSequence>, options: ArrayList<Int>, icons: ArrayList<Int>) {
        for (idx in options.indices.reversed()) {
            if (options[idx] !in MESSAGE_OPTIONS) {
                items.removeAt(idx)
                options.removeAt(idx)
                icons.removeAt(idx)
            }
        }
        val screen = screens[activity] ?: return
        if (screen.kind == KIND_DELETED || screen.rowIds.containsKey(selected.id)) {
            items.add(LocaleController.getString(R.string.InuArchiveDeleteEntry))
            options.add(OPTION_DELETE_ENTRY)
            icons.add(R.drawable.msg_delete)
        }
    }

    @JvmStatic
    fun processMenuOption(activity: ChatActivity, option: Int, selected: MessageObject, group: MessageObject.GroupedMessages?): Boolean {
        if (option != OPTION_DELETE_ENTRY) return false
        val screen = screens[activity] ?: return true
        val reopen = Runnable {
            if (screens[activity] === screen) activity.presentFragment(create(screen.dialogId, screen.kind, screen.msgId), true, true)
        }
        if (screen.kind == KIND_DELETED) {
            val ids = group?.messages?.map { it.realId } ?: listOf(selected.realId)
            DeletedMessagesHelper.purge(screen.account, screen.dialogId, ids, reopen)
        } else {
            val rowId = screen.rowIds[selected.id] ?: return true
            EditHistoryHelper.deleteEditHistoryEntry(screen.account, screen.dialogId, selected.realId, rowId, reopen)
        }
        return true
    }

    @JvmStatic
    fun load(activity: ChatActivity, loadIndex: Int) {
        val screen = screens[activity] ?: return
        val offset = screen.offset
        val storage = MessagesStorage.getInstance(screen.account)
        storage.storageQueue.postRunnable {
            val db = storage.database
            val page = try {
                when {
                    db == null -> Page(emptyList(), true)
                    screen.kind == KIND_DELETED -> deletedPage(db, screen, offset)
                    offset > 0 -> Page(emptyList(), true)
                    else -> editedPage(db, screen)
                }
            } catch (e: Throwable) {
                android.util.Log.d(TAG, "archive page load failed", e)
                Page(emptyList(), true)
            }
            val messages = page.entries.map { buildMessage(screen.account, screen.dialogId, it) }
            if (screen.kind == KIND_EDITED) {
                messages.forEachIndexed { index, message ->
                    message.realId = message.id
                    message.id = SYNTHETIC_BASE + messages.size - index
                }
            }
            if (db != null) resolveReplies(db, messages)
            val peers = loadPeers(storage, screen.account, messages + messages.mapNotNull { it.replyMessage }, page.entries.map { it.fromId } + screen.dialogId)
            AndroidUtilities.runOnUIThread {
                if (screens[activity] !== screen || !activity.inu_acceptsArchiveLoad(loadIndex)) return@runOnUIThread
                applyPeers(screen.account, peers)
                if (screen.kind == KIND_DELETED) {
                    for (entry in page.entries) {
                        val path = entry.mediaPath?.takeIf { it.isNotBlank() } ?: continue
                        ArchiveCache.rememberDeletedMediaPath(screen.account, screen.dialogId, entry.originalMessageId, path)
                    }
                }
                val objects = ArrayList<MessageObject>(messages.size)
                messages.forEachIndexed { index, message ->
                    val obj = localMessageObject(screen.account, message)
                    if (screen.kind == KIND_EDITED) {
                        EditHistoryHelper.markAsHistoryPreview(obj)
                        page.entries[index].rowId.takeIf { it > 0 }?.let { screen.rowIds[message.id] = it }
                    }
                    objects.add(obj)
                }
                val loadType = if (screen.loaded) 0 else 2
                screen.loaded = true
                screen.offset += page.entries.size
                post(activity, screen, objects, loadType, loadIndex, olderEnd = page.end)
            }
        }
    }

    private class Page(val entries: List<EditEntry>, val end: Boolean)

    private fun deletedPage(db: SQLiteDatabase, screen: Screen, offset: Int): Page {
        val rows = ArchiveStore.deletedMessagesInDialog(db, screen.dialogId, PAGE_SIZE, offset)
        return Page(
            rows.map { EditEntry(it.date.toLong(), it.text, it.mediaPath, originalMessageId = it.msgId, fromId = it.fromId, message = it.message) },
            rows.size < PAGE_SIZE,
        )
    }

    // entiny: one message keeps one block of versions, the current text closes it; one batch, newest first
    private fun editedPage(db: SQLiteDatabase, screen: Screen): Page {
        val stored = if (screen.msgId != 0) {
            ArchiveStore.loadEditHistory(db, screen.dialogId, screen.msgId).map { screen.msgId to it }
        } else {
            ArchiveStore.loadEditHistoryInDialog(db, screen.dialogId)
        }
        val ids = stored.mapTo(HashSet()) { it.first }
        if (screen.msgId != 0) ids.add(screen.msgId)
        val live = try {
            ArchiveStore.loadMessagesForArchive(db, screen.dialogId, ids)
        } catch (e: Throwable) {
            android.util.Log.d(TAG, "live messages load failed", e)
            emptyMap()
        }
        val groups = LinkedHashMap<Int, ArrayList<EditEntry>>()
        for ((msgId, row) in stored.sortedBy { it.second.date }) {
            groups.getOrPut(msgId) { ArrayList() }.add(
                EditEntry(
                    row.date,
                    row.text,
                    row.mediaPath,
                    row.entities,
                    row.media,
                    originalMessageId = msgId,
                    fromId = row.fromId.takeIf { it != 0L } ?: EditHistoryHelper.messageAuthor(screen.account, live[msgId]),
                    rowId = row.rowId,
                )
            )
        }
        if (screen.msgId != 0) groups.getOrPut(screen.msgId) { ArrayList() }
        val versions = ArrayList<EditEntry>()
        for ((msgId, list) in groups) {
            live[msgId]?.let { liveEntry(screen.account, msgId, it) }?.let(list::add)
            val shown = ArrayList<EditEntry>()
            for (entry in list) {
                if (shown.isNotEmpty() && EditHistoryHelper.changes(shown.last(), entry).isEmpty()) continue
                shown.add(entry)
            }
            shown.forEachIndexed { i, entry -> versions.add(withDiff(entry, shown.getOrNull(i - 1), shown.getOrNull(i + 1))) }
        }
        versions.reverse()
        return Page(versions, true)
    }

    private fun liveEntry(account: Int, msgId: Int, live: TLRPC.Message): EditEntry? {
        val media = live.media?.takeIf { it !is TLRPC.TL_messageMediaEmpty }
        if (live.message.isNullOrEmpty() && media == null) return null
        val date = if (live.edit_date > 0) live.edit_date else live.date
        return EditEntry(
            date.toLong(),
            live.message ?: "",
            null,
            live.entities?.let { ArrayList(it) },
            ArchiveStore.cloneMedia(media),
            originalMessageId = msgId,
            fromId = EditHistoryHelper.messageAuthor(account, live),
        )
    }

    // entiny: the diff rides on underline / strike entities so versions stay ordinary chat bubbles
    private fun withDiff(entry: EditEntry, previous: EditEntry?, next: EditEntry?): EditEntry {
        if (!InuConfig.SHOW_EDIT_HISTORY_DIFF.value) return entry
        val added = previous?.let { diffRanges(it.text, entry.text).second }.orEmpty()
        val removed = next?.let { diffRanges(entry.text, it.text).first }.orEmpty()
        if (added.isEmpty() && removed.isEmpty()) return entry
        val merged = ArrayList<TLRPC.MessageEntity>(entry.entities.orEmpty())
        for (r in added) merged.add(TLRPC.TL_messageEntityUnderline().apply { offset = r.first; length = r.last - r.first + 1 })
        for (r in removed) merged.add(TLRPC.TL_messageEntityStrike().apply { offset = r.first; length = r.last - r.first + 1 })
        return entry.copy(entities = merged)
    }

    // entiny: word-level LCS, returns char ranges removed from old and added to new
    private fun diffRanges(old: String, new: String): Pair<List<IntRange>, List<IntRange>> {
        if (old == new) return emptyList<IntRange>() to emptyList()
        val a = TOKEN.findAll(old).toList()
        val b = TOKEN.findAll(new).toList()
        if (a.size.toLong() * b.size > DIFF_LIMIT) return emptyList<IntRange>() to emptyList()
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size - 1 downTo 0) for (j in b.size - 1 downTo 0) {
            dp[i][j] = if (a[i].value == b[j].value) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val removed = ArrayList<IntRange>()
        val added = ArrayList<IntRange>()
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            if (i < a.size && j < b.size && a[i].value == b[j].value) {
                i++
                j++
            } else if (j >= b.size || (i < a.size && dp[i + 1][j] >= dp[i][j + 1])) {
                if (a[i].value.isNotBlank()) removed.add(a[i].range)
                i++
            } else {
                if (b[j].value.isNotBlank()) added.add(b[j].range)
                j++
            }
        }
        return removed to added
    }

    @JvmStatic
    fun reload(activity: ChatActivity, loadIndex: Int) {
        val screen = screens[activity] ?: return
        screen.offset = 0
        screen.loaded = false
        load(activity, loadIndex)
    }

    private fun post(activity: ChatActivity, screen: Screen, messages: ArrayList<MessageObject>, loadType: Int, loadIndex: Int, olderEnd: Boolean) {
        activity.inu_archiveEnds(olderEnd, true)
        NotificationCenter.getInstance(screen.account).postNotificationName(
            NotificationCenter.messagesDidLoad,
            0L, messages.size, messages, false, 0, 0, 0, 0, loadType, true,
            activity.classGuid, loadIndex, 0, 0, ChatActivity.inu_MODE_ARCHIVE,
        )
        activity.inu_archiveEnds(olderEnd, true)
    }

    // entiny: the screen has no peer, so per-message reactions and paid media checks would go out with an empty peer
    private fun localMessageObject(account: Int, message: TLRPC.Message): MessageObject =
        MessageObject(account, message, true, true).also {
            it.reactionsLastCheckTime = Long.MAX_VALUE / 2
            it.extendedMediaLastCheckTime = Long.MAX_VALUE / 2
        }

    internal fun buildMessage(account: Int, dialogId: Long, entry: EditEntry): TLRPC.Message {
        val controller = MessagesController.getInstance(account)
        val message = entry.message ?: TLRPC.TL_message().apply {
            id = entry.originalMessageId
            this.dialog_id = dialogId
            peer_id = controller.getPeer(dialogId)
            val senderId = entry.fromId.takeIf { it != 0L } ?: if (DialogObject.isUserDialog(dialogId)) dialogId else 0L
            if (senderId != 0L) from_id = controller.getPeer(senderId)
            out = senderId == UserConfig.getInstance(account).clientUserId
            message = entry.text
            entities = entry.entities ?: ArrayList()
            media = entry.media ?: TLRPC.TL_messageMediaEmpty()
            date = entry.timestamp.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        message.attachPath = entry.mediaPath ?: message.attachPath
        return message
    }

    internal fun resolveReplies(db: SQLiteDatabase, messages: List<TLRPC.Message>) {
        val byDialog = HashMap<Long, HashSet<Int>>()
        for (m in messages) {
            val replyId = m.reply_to?.reply_to_msg_id ?: 0
            if (replyId != 0 && m.replyMessage == null) {
                byDialog.getOrPut(MessageObject.getReplyToDialogId(m)) { HashSet() }.add(replyId)
            }
        }
        val found = HashMap<Pair<Long, Int>, TLRPC.Message>()
        for ((did, ids) in byDialog) {
            try {
                for ((id, m) in ArchiveStore.loadMessagesForArchive(db, did, ids)) found[did to id] = m
            } catch (e: Throwable) {
                android.util.Log.d(TAG, "archive reply load failed", e)
            }
        }
        for (m in messages) {
            val replyId = m.reply_to?.reply_to_msg_id ?: 0
            if (replyId != 0 && m.replyMessage == null) m.replyMessage = found[MessageObject.getReplyToDialogId(m) to replyId]
        }
    }

    internal fun loadPeers(
        storage: MessagesStorage,
        account: Int,
        messages: Collection<TLRPC.Message>,
        peerIds: Collection<Long>,
    ): Pair<ArrayList<TLRPC.User>, ArrayList<TLRPC.Chat>> {
        val controller = MessagesController.getInstance(account)
        val users = ArrayList<Long>()
        val chats = ArrayList<Long>()
        for (m in messages) MessagesStorage.addUsersAndChatsFromMessage(m, users, chats, null)
        for (id in peerIds) if (id > 0) users.add(id) else if (id < 0) chats.add(-id)
        val userIds = users.filterTo(HashSet()) { it > 0 && controller.getUser(it) == null }
        val chatIds = chats.filterTo(HashSet()) { it > 0 && controller.getChat(it) == null }
        val loadedUsers = ArrayList<TLRPC.User>()
        val loadedChats = ArrayList<TLRPC.Chat>()
        try {
            if (userIds.isNotEmpty()) storage.getUsersInternal(userIds, loadedUsers)
            if (chatIds.isNotEmpty()) storage.getChatsInternal(chatIds.joinToString(","), loadedChats)
        } catch (e: Throwable) {
            android.util.Log.d(TAG, "archive peers load failed", e)
        }
        return loadedUsers to loadedChats
    }

    internal fun applyPeers(account: Int, peers: Pair<ArrayList<TLRPC.User>, ArrayList<TLRPC.Chat>>) {
        val controller = MessagesController.getInstance(account)
        if (peers.first.isNotEmpty()) controller.putUsers(peers.first, true)
        if (peers.second.isNotEmpty()) controller.putChats(peers.second, true)
    }

    private fun ensurePeer(account: Int, dialogId: Long) {
        val controller = MessagesController.getInstance(account)
        if (dialogId > 0 && controller.getUser(dialogId) != null || dialogId < 0 && controller.getChat(-dialogId) != null || dialogId == 0L) return
        val storage = MessagesStorage.getInstance(account)
        val latch = CountDownLatch(1)
        var user: TLRPC.User? = null
        var chat: TLRPC.Chat? = null
        storage.storageQueue.postRunnable {
            try {
                if (dialogId > 0) user = storage.getUser(dialogId) else chat = storage.getChat(-dialogId)
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await()
        } catch (e: InterruptedException) {
            return
        }
        user?.let { controller.putUser(it, true) }
        chat?.let { controller.putChat(it, true) }
    }
}
