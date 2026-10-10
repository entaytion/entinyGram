package desu.inugram.helpers

import android.app.NotificationManager
import android.os.SystemClock
import androidx.collection.LongSparseArray
import androidx.core.content.edit
import desu.inugram.InuConfig
import desu.inugram.helpers.chat.BlockedMessagesHelper
// #if PLUGINS
import desu.inugram.helpers.plugins.platform.PluginNotifications
// #endif
import desu.inugram.helpers.security.ParanoiaHelper
import desu.inugram.helpers.security.PasscodeHelper
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationsController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import java.util.concurrent.ConcurrentHashMap

object NotificationsHelper {
    private val prefs by lazy {
        InuPrefs.of("inugram_notifications")
    }

    @Volatile
    private var perChatCheckedAt = 0L

    @Volatile
    private var perChatSlotsLeft = false

    @JvmStatic
    fun keepAliveForeground(): Boolean = InuConfig.UNIFIED_PUSH.value || InuConfig.FOREGROUND_PUSH_SERVICE.value

    @JvmStatic
    fun smallIconRes(): Int = when (InuConfig.NOTIFICATION_ICON.value) {
        InuConfig.NotificationIconItem.TELEGRAM -> R.drawable.notification
        InuConfig.NotificationIconItem.OLD_ENTINYGRAM -> R.drawable.icon_notification_old_inu
        else -> R.drawable.icon_notification_inu
    }

    @JvmStatic
    fun shouldSuppressNotifications(account: Int): Boolean =
        PasscodeHelper.isAccountHidden(account) ||
            ParanoiaHelper.shouldSuppressNotifications()
            // #if PLUGINS
            || PluginNotifications.areNotificationsSuppressed(account)
            // #endif

    @JvmStatic
    fun shouldSuppressMessageNotification(messageObject: MessageObject?): Boolean {
        if (messageObject == null) return false
        return BlockedMessagesHelper.shouldHide(messageObject)
            || ParanoiaHelper.isHidden(messageObject.currentAccount, messageObject.dialogId)
    }

    // entiny: stock reads a disabled OS channel back as a mute for the whole chat type or chat
    @JvmStatic
    fun syncSystemChannelMute(): Boolean = InuConfig.NOTIF_CHANNEL_SYNC.value

    // entiny: per-chat channels are capped by the system, so stop before the slot limit is hit
    @JvmStatic
    fun perChatChannelsEnabled(manager: NotificationManager): Boolean {
        if (!InuConfig.PER_CHAT_NOTIF_CHANNELS.value) return false
        val now = SystemClock.elapsedRealtime()
        if (now - perChatCheckedAt > PER_CHAT_RECHECK_MS) {
            perChatCheckedAt = now
            perChatSlotsLeft = try {
                (manager.notificationChannels?.size ?: 0) < MAX_OS_NOTIFICATION_CHANNELS
            } catch (e: Exception) {
                false
            }
        }
        return perChatSlotsLeft
    }

    @JvmStatic
    fun resetDefaultNotificationChannels() {
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            val controller = NotificationsController.getInstance(a)
            controller.deleteNotificationChannelGlobal(NotificationsController.TYPE_GROUP)
            controller.deleteNotificationChannelGlobal(NotificationsController.TYPE_PRIVATE)
            controller.deleteNotificationChannelGlobal(NotificationsController.TYPE_CHANNEL)
        }
    }

    // Stock's `NotificationsController.wearNotificationsIds` (dialogId -> notification id) is the only record of
    // what is on screen, and every cancel path diffs against it вЂ” but it is in-memory only, while posted
    // notifications outlive the process. Mirroring it to disk is what makes those cancel paths survive a restart.
    private fun getWearIdsKey(account: Int) = "wear_ids_$account"

    @JvmStatic
    fun loadWearNotificationIds(account: Int, into: LongSparseArray<Int>) {
        into.clear()
        val stored = prefs.getString(getWearIdsKey(account), null) ?: return
        for (entry in stored.splitToSequence(',')) {
            val separator = entry.indexOf(':')
            if (separator <= 0) continue
            val dialogId = entry.substring(0, separator).toLongOrNull() ?: continue
            val notificationId = entry.substring(separator + 1).toIntOrNull() ?: continue
            into.put(dialogId, notificationId)
        }
    }

    // Every showOrUpdateNotification re-notify()s ALL per-chat notifications, and notification bridges
    // (Mi Fitness etc.) re-forward every onNotificationPosted without deduping by key or respecting
    // FLAG_ONLY_ALERT_ONCE вЂ” so unchanged reposts must be skipped on our side. Signatures are keyed by
    // notification id; per-account maps are only touched from that account's notificationsQueue.
    private val postedSignatures = ConcurrentHashMap<Int, MutableMap<Int, String>>()

    @JvmStatic
    fun computeNotificationSignature(
        channelId: String?,
        name: String?,
        messages: List<MessageObject>?,
        storyCount: Int,
        maxId: Int,
        locked: Boolean,
        hasAvatar: Boolean,
    ): String = buildString {
        append(channelId).append('|').append(name).append('|').append(storyCount).append('|')
        append(maxId).append('|').append(locked).append('|').append(hasAvatar)
        messages?.forEach {
            append('|').append(it.id).append(':').append(it.messageOwner?.edit_date ?: 0)
        }
    }

    @JvmStatic
    fun shouldSkipNotify(account: Int, notificationId: Int, signature: String?): Boolean {
        if (signature == null) return false
        val map = postedSignatures.getOrPut(account) { HashMap() }
        if (map[notificationId] == signature) return true
        map[notificationId] = signature
        return false
    }

    @JvmStatic
    fun removePostedSignature(account: Int, notificationId: Int) {
        postedSignatures[account]?.remove(notificationId)
    }

    @JvmStatic
    fun clearPostedSignatures(account: Int) {
        postedSignatures[account]?.clear()
    }

    @JvmStatic
    fun saveWearNotificationIds(account: Int, ids: LongSparseArray<Int>) {
        val key = getWearIdsKey(account)
        val stored = (0 until ids.size()).joinToString(",") { "${ids.keyAt(it)}:${ids.valueAt(it)}" }
        if (prefs.getString(key, "") == stored) return
        // commit: this races a process death that may come right after posting the notifications
        prefs.edit(commit = true) {
            if (stored.isEmpty()) remove(key) else putString(key, stored)
        }
    }

    private const val MAX_OS_NOTIFICATION_CHANNELS = 4500
    private const val PER_CHAT_RECHECK_MS = 60000L
}
