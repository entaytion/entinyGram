package desu.inugram.helpers

import android.content.Context
import androidx.collection.LongSparseArray
import androidx.core.content.edit
import desu.inugram.InuConfig
import desu.inugram.helpers.chat.BlockedMessagesHelper
import desu.inugram.helpers.security.ParanoiaHelper
import desu.inugram.helpers.security.PasscodeHelper
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import java.util.concurrent.ConcurrentHashMap

object NotificationsHelper {
    private val prefs by lazy {
        ApplicationLoader.applicationContext.getSharedPreferences("inugram_notifications", Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun smallIconRes(): Int = when (InuConfig.NOTIFICATION_ICON.value) {
        InuConfig.NotificationIconItem.TELEGRAM -> R.drawable.notification
        InuConfig.NotificationIconItem.OLD_ENTINYGRAM -> R.drawable.icon_notification_old_inu
        else -> R.drawable.icon_notification_inu
    }

    @JvmStatic
    fun shouldSuppressNotifications(account: Int): Boolean =
        PasscodeHelper.isAccountHidden(account) || ParanoiaHelper.shouldSuppressNotifications()

    @JvmStatic
    fun shouldSuppressMessageNotification(messageObject: MessageObject?): Boolean {
        if (messageObject == null) return false
        return BlockedMessagesHelper.shouldHide(messageObject)
            || ParanoiaHelper.isHidden(messageObject.currentAccount, messageObject.dialogId)
    }

    // Stock's `NotificationsController.wearNotificationsIds` (dialogId -> notification id) is the only record of
    // what is on screen, and every cancel path diffs against it — but it is in-memory only, while posted
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
    // FLAG_ONLY_ALERT_ONCE — so unchanged reposts must be skipped on our side.
    //
    // Signatures are keyed by dialogId+topicId, NOT by the Android notification id: stock derives that id
    // from dialogId alone, so two forum topics of the same supergroup collide on it and one topic's
    // notification would be silently skipped as "unchanged" against the other topic's signature.
    // Per-account maps are only touched from that account's notificationsQueue.
    private val postedSignatures = ConcurrentHashMap<Int, MutableMap<String, String>>()

    @JvmStatic
    fun signatureKey(dialogId: Long, topicId: Long, story: Boolean): String =
        if (story) "story" else "$dialogId:$topicId"

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
    fun shouldSkipNotify(account: Int, key: String, signature: String?): Boolean {
        if (signature == null) return false
        val map = postedSignatures.getOrPut(account) { HashMap() }
        if (map[key] == signature) return true
        map[key] = signature
        return false
    }

    // Cancel paths only know the dialogId (stock's wearNotificationsIds is dialog-keyed), so drop every
    // topic's signature for that dialog.
    @JvmStatic
    fun removePostedSignatures(account: Int, dialogId: Long) {
        val map = postedSignatures[account] ?: return
        val prefix = "$dialogId:"
        map.keys.removeAll { it.startsWith(prefix) }
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
}
