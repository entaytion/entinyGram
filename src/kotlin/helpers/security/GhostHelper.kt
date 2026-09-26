package desu.inugram.helpers.security

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.core.content.ContextCompat
import desu.inugram.InuConfig
import desu.inugram.ui.settings.RadioDialogBuilder
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.RequestDelegate
import org.telegram.tgnet.RequestDelegateTimestamp
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_account
import org.telegram.tgnet.tl.TL_stories
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.SimpleTextView
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ColoredImageSpan
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

enum class SuppressKind {
    READ,
    TYPING,
    ONLINE,
    VOICE_READ,
    STORY_READ,
}

object GhostHelper {

    private val temporarilyAllowedDialogs: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap())
    private val offlineRunnables: ConcurrentHashMap<Int, Runnable> = ConcurrentHashMap()

    @JvmStatic
    fun isGhostActive(): Boolean = InuConfig.GHOST_MODE_ENABLED.value

    @JvmStatic
    fun setGhostMode(enabled: Boolean) {
        InuConfig.GHOST_MODE_ENABLED.value = enabled
        syncPresence(UserConfig.selectedAccount)
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged)
        // entiny: dialogs list rows only rebind their ghost badge on updateInterfaces, not mainUserInfoChanged
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL)
    }

    @JvmStatic
    fun toggleGhostMode(): Boolean {
        val newState = !InuConfig.GHOST_MODE_ENABLED.value
        setGhostMode(newState)
        return newState
    }

    @JvmStatic
    fun applyChatTitleGhost(parentFragment: ChatActivity?, titleTextView: SimpleTextView?) {
        if (titleTextView == null) return
        val dialogId = parentFragment?.dialogId ?: 0L
        if (dialogId != 0L && isGhostActiveForDialog(dialogId) && !InuConfig.GHOST_HIDE_APP_BAR_ICON.value) {
            val ghost = ContextCompat.getDrawable(titleTextView.context, R.drawable.inu_ghost_filled)?.mutate()
            if (ghost != null) {
                ghost.setBounds(0, 0, AndroidUtilities.dp(15f), AndroidUtilities.dp(15f))
                val color = Theme.getColor(Theme.key_actionBarDefaultSubtitle, parentFragment?.resourceProvider)
                ghost.setColorFilter(PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN))
            }
            titleTextView.setLeftDrawable(ghost)
        } else {
            titleTextView.setLeftDrawable(null)
        }
    }

    // entiny: dialogs-list counterpart of applyChatTitleGhost; folded into the name text so the existing
    // ellipsize/measure pass in DialogCell absorbs it without touching its badge-reservation math
    @JvmStatic
    fun applyDialogListGhost(name: CharSequence?, dialogId: Long): CharSequence? {
        if (name == null || dialogId == 0L || InuConfig.GHOST_HIDE_APP_BAR_ICON.value || !isGhostActiveForDialog(dialogId)) return name
        val ssb = SpannableStringBuilder("  ").append(name)
        val span = ColoredImageSpan(R.drawable.inu_ghost_filled, ColoredImageSpan.ALIGN_CENTER).apply {
            setSize(AndroidUtilities.dp(15f))
            setTranslateX(AndroidUtilities.dp(-2f).toFloat())
            setOverrideColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        }
        ssb.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return ssb
    }

    const val OVERRIDE_DEFAULT = 0
    const val OVERRIDE_ALWAYS = 1
    const val OVERRIDE_NEVER = 2

    class ChatOverride(@JvmField val read: Int, @JvmField val typing: Int) {
        val isDefault: Boolean get() = read == OVERRIDE_DEFAULT && typing == OVERRIDE_DEFAULT
    }

    private val DEFAULT_OVERRIDE = ChatOverride(OVERRIDE_DEFAULT, OVERRIDE_DEFAULT)

    @Volatile
    private var overridesSource: Set<String>? = null

    @Volatile
    private var overridesCache: Map<Long, ChatOverride> = emptyMap()

    // entiny: entries are "dialogId:read:typing" with signed ids, so user 123 and chat -123 never collide
    private fun overrides(): Map<Long, ChatOverride> {
        migrateLegacyLists()
        val source = InuConfig.GHOST_CHAT_OVERRIDES.value
        if (source === overridesSource) return overridesCache
        val parsed = parseOverrides(source)
        overridesCache = parsed
        overridesSource = source
        return parsed
    }

    private fun parseOverrides(source: Set<String>): HashMap<Long, ChatOverride> {
        val parsed = HashMap<Long, ChatOverride>()
        for (entry in source) {
            val parts = entry.split(':')
            if (parts.size != 3) continue
            val id = parts[0].toLongOrNull() ?: continue
            val read = parts[1].toIntOrNull()?.takeIf { it in OVERRIDE_DEFAULT..OVERRIDE_NEVER } ?: continue
            val typing = parts[2].toIntOrNull()?.takeIf { it in OVERRIDE_DEFAULT..OVERRIDE_NEVER } ?: continue
            val o = ChatOverride(read, typing)
            if (id != 0L && !o.isDefault) parsed[id] = o
        }
        return parsed
    }

    private fun saveOverrides(map: Map<Long, ChatOverride>) {
        InuConfig.GHOST_CHAT_OVERRIDES.value =
            map.filterValues { !it.isDefault }.mapTo(HashSet()) { (id, o) -> "$id:${o.read}:${o.typing}" }
    }

    // entiny: old whitelist/ghost-chat sets are folded in once; re-runs if an old backup import refills them
    @Synchronized
    private fun migrateLegacyLists() {
        val whitelist = InuConfig.GHOST_WHITELIST_DIALOGS.value
        val targets = InuConfig.GHOST_TARGET_DIALOGS.value
        if (InuConfig.GHOST_OVERRIDES_MIGRATED.value && whitelist.isEmpty() && targets.isEmpty()) return
        val map = parseOverrides(InuConfig.GHOST_CHAT_OVERRIDES.value)
        for (key in whitelist) key.toLongOrNull()?.takeIf { it != 0L }?.let { map.putIfAbsent(it, ChatOverride(OVERRIDE_NEVER, OVERRIDE_NEVER)) }
        for (key in targets) key.toLongOrNull()?.takeIf { it != 0L }?.let { map[it] = ChatOverride(OVERRIDE_ALWAYS, OVERRIDE_ALWAYS) }
        saveOverrides(map)
        if (whitelist.isNotEmpty()) InuConfig.GHOST_WHITELIST_DIALOGS.value = emptySet()
        if (targets.isNotEmpty()) InuConfig.GHOST_TARGET_DIALOGS.value = emptySet()
        InuConfig.GHOST_OVERRIDES_MIGRATED.value = true
    }

    @JvmStatic
    fun getChatOverride(dialogId: Long): ChatOverride =
        if (dialogId == 0L) DEFAULT_OVERRIDE else overrides()[dialogId] ?: DEFAULT_OVERRIDE

    @JvmStatic
    fun setChatOverride(dialogId: Long, read: Int, typing: Int) {
        if (dialogId == 0L) return
        val map = HashMap(overrides())
        map[dialogId] = ChatOverride(read, typing)
        saveOverrides(map)
    }

    @JvmStatic
    fun removeChatOverride(dialogId: Long) {
        val map = HashMap(overrides())
        if (map.remove(dialogId) != null) saveOverrides(map)
    }

    // entiny: never pruned -- dropping a chat that is just not loaded yet would silently change its behaviour
    @JvmStatic
    fun getOverriddenDialogs(): List<Long> = overrides().keys.sorted()

    @JvmStatic
    fun isGhostActiveForDialog(dialogId: Long): Boolean {
        val o = getChatOverride(dialogId)
        if (o.read == OVERRIDE_ALWAYS || o.typing == OVERRIDE_ALWAYS) return true
        if (!isGhostActive() || !isInGlobalScope(dialogId)) return false
        return !(o.read == OVERRIDE_NEVER && o.typing == OVERRIDE_NEVER)
    }

    // entiny: global ghost can skip whole chat types, e.g. channels where hidden reads only pile up unread elsewhere
    @JvmStatic
    fun isInGlobalScope(dialogId: Long): Boolean {
        if (dialogId == 0L) return true
        if (dialogId > 0 || DialogObject.isEncryptedDialog(dialogId)) return InuConfig.GHOST_SCOPE_USERS.value
        val chat = findChat(-dialogId) ?: return InuConfig.GHOST_SCOPE_GROUPS.value
        return if (ChatObject.isChannelAndNotMegaGroup(chat)) InuConfig.GHOST_SCOPE_CHANNELS.value else InuConfig.GHOST_SCOPE_GROUPS.value
    }

    private fun findChat(chatId: Long): TLRPC.Chat? {
        MessagesController.getInstance(UserConfig.selectedAccount).getChat(chatId)?.let { return it }
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            MessagesController.getInstance(a).getChat(chatId)?.let { return it }
        }
        return null
    }

    @JvmStatic
    fun shouldSuppress(dialogId: Long, kind: SuppressKind): Boolean {
        val o = getChatOverride(dialogId)
        val chatOverride = when (kind) {
            SuppressKind.READ, SuppressKind.VOICE_READ -> o.read
            SuppressKind.TYPING -> o.typing
            SuppressKind.ONLINE, SuppressKind.STORY_READ -> OVERRIDE_DEFAULT
        }
        if (chatOverride == OVERRIDE_NEVER) return false
        if (dialogId != 0L && temporarilyAllowedDialogs.contains(dialogId)) return false
        val suppress = if (chatOverride == OVERRIDE_ALWAYS) {
            true
        } else if (!InuConfig.GHOST_MODE_ENABLED.value || !isInGlobalScope(dialogId)) {
            false
        } else when (kind) {
            SuppressKind.READ -> InuConfig.GHOST_HIDE_READ.value
            SuppressKind.TYPING -> InuConfig.GHOST_HIDE_TYPING.value
            SuppressKind.ONLINE -> InuConfig.GHOST_PRESENCE_MODE.value == InuConfig.GhostPresenceModeItem.HIDDEN
            SuppressKind.VOICE_READ -> InuConfig.GHOST_HIDE_VOICE_READ.value || InuConfig.GHOST_HIDE_READ.value
            SuppressKind.STORY_READ -> InuConfig.GHOST_HIDE_STORY_READ.value
        }
        if (suppress) {
            val dump = "suppress dialogId=$dialogId kind=$kind override=${o.read}/${o.typing} read=${InuConfig.GHOST_HIDE_READ.value} " +
                "voiceRead=${InuConfig.GHOST_HIDE_VOICE_READ.value} storyRead=${InuConfig.GHOST_HIDE_STORY_READ.value} " +
                "typing=${InuConfig.GHOST_HIDE_TYPING.value} presence=${InuConfig.GHOST_PRESENCE_MODE.value}"
            android.util.Log.d("GhostMode", dump)
            org.telegram.messenger.FileLog.d("GhostMode: $dump")
        }
        return suppress
    }

    @JvmStatic
    fun overrideLabel(state: Int): String = LocaleController.getString(
        when (state) {
            OVERRIDE_ALWAYS -> R.string.InuGhostOverrideAlways
            OVERRIDE_NEVER -> R.string.InuGhostOverrideNever
            else -> R.string.InuGhostOverrideDefault
        }
    )

    @JvmStatic
    fun overrideSummary(dialogId: Long): String {
        val o = getChatOverride(dialogId)
        return LocaleController.formatString(R.string.InuGhostOverrideRead, overrideLabel(o.read)) + ", " +
            LocaleController.formatString(R.string.InuGhostOverrideTyping, overrideLabel(o.typing))
    }

    // entiny: chat/profile menu entry -- per-chat read and typing overrides plus mark-as-read when reads are hidden
    @JvmStatic
    @JvmOverloads
    fun showChatOverridesDialog(fragment: BaseFragment, account: Int, dialogId: Long, onChanged: Runnable? = null) {
        val context = fragment.parentActivity ?: return
        if (dialogId == 0L) return
        val o = getChatOverride(dialogId)
        val rows = arrayListOf<CharSequence>(
            LocaleController.formatString(R.string.InuGhostOverrideRead, overrideLabel(o.read)),
            LocaleController.formatString(R.string.InuGhostOverrideTyping, overrideLabel(o.typing)),
        )
        if (shouldSuppress(dialogId, SuppressKind.READ)) rows.add(LocaleController.getString(R.string.InuMarkChatAsRead))
        fragment.showDialog(
            AlertDialog.Builder(context, fragment.resourceProvider)
                .setTitle(LocaleController.getString(R.string.InuGhostMode))
                .setItems(rows.toTypedArray()) { _, which ->
                    when (which) {
                        0, 1 -> pickOverrideState(fragment, dialogId, which == 0, onChanged)
                        2 -> {
                            markDialogAsRead(account, dialogId)
                            BulletinFactory.of(fragment)
                                .createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.InuMarkChatAsReadDone))
                                .show()
                        }
                    }
                }
                .create()
        )
    }

    private fun pickOverrideState(fragment: BaseFragment, dialogId: Long, read: Boolean, onChanged: Runnable?) {
        val context = fragment.parentActivity ?: return
        val o = getChatOverride(dialogId)
        val states = intArrayOf(OVERRIDE_DEFAULT, OVERRIDE_ALWAYS, OVERRIDE_NEVER)
        val current = if (read) o.read else o.typing
        fragment.showDialog(
            RadioDialogBuilder(context, fragment.resourceProvider)
                .setTitle(LocaleController.getString(if (read) R.string.InuGhostOverrideReadTitle else R.string.InuGhostOverrideTypingTitle))
                .setSubtitle(LocaleController.getString(if (read) R.string.InuGhostOverrideReadInfo else R.string.InuGhostOverrideTypingInfo))
                .setItems(states.map { RadioDialogBuilder.Item(overrideLabel(it)) }, states.indexOf(current)) { _, which ->
                    val state = states[which]
                    val latest = getChatOverride(dialogId)
                    if (read) setChatOverride(dialogId, state, latest.typing) else setChatOverride(dialogId, latest.read, state)
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged)
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL)
                    onChanged?.run()
                }
                .create()
        )
    }

    @JvmStatic
    fun shouldSuppressRead(dialogId: Long): Boolean = shouldSuppress(dialogId, SuppressKind.READ)

    @JvmStatic
    fun shouldSuppressLocalRead(dialogId: Long): Boolean {
        return !InuConfig.GHOST_MARK_READ_LOCALLY.value && shouldSuppress(dialogId, SuppressKind.READ)
    }

    @JvmStatic
    fun processSendRequest(
        request: TLObject,
        account: Int,
        onComplete: RequestDelegate?,
        onCompleteTimestamp: RequestDelegateTimestamp?,
    ): Boolean {
        val dialogId = extractDialogId(request)

        return when (request) {
            is TLRPC.TL_messages_setTyping,
            is TLRPC.TL_messages_setEncryptedTyping -> {
                if (shouldSuppress(dialogId, SuppressKind.TYPING)) {
                    if (onComplete != null) onComplete.run(null, null)
                    else onCompleteTimestamp?.run(null, null, 0L)
                    true
                } else {
                    false
                }
            }
            is TLRPC.TL_channels_readHistory,
            is TLRPC.TL_messages_readHistory,
            is TLRPC.TL_messages_readEncryptedHistory,
            is TLRPC.TL_messages_readDiscussion,
            is TLRPC.TL_messages_readSavedHistory,
            is TLRPC.TL_messages_markDialogUnread -> {
                shouldSuppress(dialogId, SuppressKind.READ)
            }
            is TLRPC.TL_messages_readMessageContents,
            is TLRPC.TL_channels_readMessageContents -> {
                shouldSuppress(dialogId, SuppressKind.VOICE_READ)
            }
            is TL_stories.TL_stories_readStories,
            is TL_stories.TL_stories_incrementStoryViews -> {
                shouldSuppress(dialogId, SuppressKind.STORY_READ)
            }
            is TL_account.updateStatus -> {
                if (shouldSuppress(0L, SuppressKind.ONLINE)) {
                    request.offline = true
                }
                if (autoOfflineEnabled() && !request.offline) {
                    scheduleOffline(account)
                }
                false
            }
            is TLRPC.TL_messages_sendMessage,
            is TLRPC.TL_messages_sendMedia,
            is TLRPC.TL_messages_sendMultiMedia,
            is TLRPC.TL_messages_sendInlineBotResult,
            is TLRPC.TL_messages_sendReaction,
            is TLRPC.TL_messages_forwardMessages,
            is TLRPC.TL_messages_sendVote,
            is TLRPC.TL_messages_sendQuickReplyMessages -> {
                if (dialogId != 0L && InuConfig.GHOST_READ_ON_SEND.value && shouldSuppress(dialogId, SuppressKind.READ)) {
                    AndroidUtilities.runOnUIThread {
                        markDialogAsRead(account, dialogId)
                    }
                }
                // entiny: server flips account online upon sending messages; skipped where typing is never hidden
                if (autoOfflineEnabled() && getChatOverride(dialogId).typing != OVERRIDE_NEVER) {
                    scheduleOffline(account)
                }
                false
            }
            else -> false
        }
    }

    private fun extractDialogId(request: TLObject): Long {
        return when (request) {
            is TLRPC.TL_messages_setTyping -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TLRPC.TL_messages_setEncryptedTyping -> request.peer?.chat_id?.toLong() ?: 0L
            is TLRPC.TL_messages_readHistory -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TLRPC.TL_messages_readDiscussion -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TLRPC.TL_channels_readHistory -> {
                val channelId = request.channel?.channel_id ?: 0L
                if (channelId != 0L) -channelId else 0L
            }
            is TLRPC.TL_channels_readMessageContents -> {
                val channelId = request.channel?.channel_id ?: 0L
                if (channelId != 0L) -channelId else 0L
            }
            is TLRPC.TL_messages_sendMessage -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TLRPC.TL_messages_sendMedia -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TLRPC.TL_messages_sendMultiMedia -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TLRPC.TL_messages_sendInlineBotResult -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TLRPC.TL_messages_sendReaction -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TLRPC.TL_messages_forwardMessages -> request.to_peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TLRPC.TL_messages_sendVote -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TLRPC.TL_messages_sendQuickReplyMessages -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TL_stories.TL_stories_readStories -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            is TL_stories.TL_stories_incrementStoryViews -> request.peer?.let { DialogObject.getPeerDialogId(it) } ?: 0L
            else -> 0L
        }
    }

    @JvmStatic
    fun markDialogAsRead(account: Int, dialogId: Long, maxId: Int = 0) {
        val controller = MessagesController.getInstance(account) ?: return
        val effectiveMaxId = if (maxId > 0) maxId else {
            val dialog = controller.dialogs_dict.get(dialogId)
            dialog?.top_message ?: 0
        }

        val chat = if (DialogObject.isChatDialog(dialogId)) controller.getChat(-dialogId) else null
        val req: TLObject = if (chat != null && ChatObject.isChannel(chat)) {
            val inputChannel = controller.getInputChannel(-dialogId) ?: return
            TLRPC.TL_channels_readHistory().apply {
                channel = inputChannel
                max_id = effectiveMaxId
            }
        } else {
            val inputPeer = controller.getInputPeer(dialogId) ?: return
            TLRPC.TL_messages_readHistory().apply {
                peer = inputPeer
                max_id = effectiveMaxId
            }
        }

        temporarilyAllowedDialogs.add(dialogId)
        try {
            ConnectionsManager.getInstance(account).sendRequest(req) { _, error ->
                try {
                    if (error == null) {
                        AndroidUtilities.runOnUIThread {
                            controller.markDialogAsRead(dialogId, effectiveMaxId, 0, 0, false, 0, 0, true, 0)
                        }
                    }
                } finally {
                    temporarilyAllowedDialogs.remove(dialogId)
                }
            }
        } catch (_: Exception) {
            temporarilyAllowedDialogs.remove(dialogId)
        }
    }

    // entiny: do not touch MessagesController.ignoreSetOnline because ChatActivity uses it to gate local read marking
    @JvmStatic
    fun syncPresence(account: Int) {
        val hide = InuConfig.GHOST_MODE_ENABLED.value &&
            InuConfig.GHOST_PRESENCE_MODE.value != InuConfig.GhostPresenceModeItem.NORMAL
        sendStatus(account, offline = hide)
    }

    @JvmStatic
    fun sendStatus(account: Int, offline: Boolean) {
        val req = TL_account.updateStatus()
        req.offline = offline
        try {
            ConnectionsManager.getInstance(account).sendRequest(req) { _, _ -> }
        } catch (_: Exception) {
        }
    }

    private fun autoOfflineEnabled(): Boolean =
        InuConfig.GHOST_MODE_ENABLED.value && InuConfig.GHOST_AUTO_OFFLINE.value

    private fun scheduleOffline(account: Int) {
        offlineRunnables.remove(account)?.let { Utilities.stageQueue.cancelRunnable(it) }
        val runnable = Runnable {
            offlineRunnables.remove(account)
            if (autoOfflineEnabled()) {
                sendStatus(account, offline = true)
            }
        }
        offlineRunnables[account] = runnable
        Utilities.stageQueue.postRunnable(runnable, OFFLINE_REASSERT_DELAY_MS)
    }

    private const val OFFLINE_REASSERT_DELAY_MS = 2500L
}
