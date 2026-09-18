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

    // entiny: mirror in-memory wearNotificationsIds to disk so stock cancel paths survive process restart
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

    // entiny: dedupe reposts because bridges like Mi Fitness re-alert on unchanged onNotificationPosted calls
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
        // entiny: synchronous commit avoids losing wear id state if process dies right after posting
        prefs.edit(commit = true) {
            if (stored.isEmpty()) remove(key) else putString(key, stored)
        }
    }
}
