package desu.inugram.helpers.feed

import desu.inugram.InuConfig
import android.os.SystemClock
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.MessagesController

class FeedConfig private constructor() {

    val includeArchived: Boolean
        get() = InuConfig.FEED_INCLUDE_ARCHIVED.value

    val generation: Int
        get() = FeedChannelSet.generation

    val excludedSnapshot: Set<Long>
        get() {
            val result = HashSet<Long>()
            for (value in InuConfig.FEED_EXCLUDED_CHANNELS.value) {
                try {
                    result.add(value.toLong())
                } catch (ignored: NumberFormatException) {
                }
            }
            return result
        }

    fun isExcluded(dialogId: Long): Boolean =
        InuConfig.FEED_EXCLUDED_CHANNELS.value.contains(dialogId.toString())

    @Volatile
    var folderFilterId: Int = 0
        private set

    private val folderMembership = HashMap<Long, Boolean>()
    private var folderMembershipAt = 0L

    fun setFolder(filterId: Int) {
        synchronized(folderMembership) { folderMembership.clear() }
        folderFilterId = filterId
        FeedChannelSet.invalidate()
    }

    fun isHidden(account: Int, dialogId: Long): Boolean = isExcluded(dialogId) || !inFolder(account, dialogId)

    private fun inFolder(account: Int, dialogId: Long): Boolean {
        val filterId = folderFilterId
        if (filterId == 0) return true
        val filter = MessagesController.getInstance(account).dialogFilters?.firstOrNull { it.id == filterId } ?: return true
        if (filter.isDefault) return true
        synchronized(folderMembership) {
            val now = SystemClock.elapsedRealtime()
            if (now - folderMembershipAt > MEMBERSHIP_TTL_MS) {
                folderMembership.clear()
                folderMembershipAt = now
            }
            return folderMembership.getOrPut(dialogId) { filter.includesDialog(AccountInstance.getInstance(account), dialogId) }
        }
    }

    fun setExcluded(dialogId: Long, excluded: Boolean) {
        val updated = HashSet(InuConfig.FEED_EXCLUDED_CHANNELS.value)
        val changed = if (excluded) updated.add(dialogId.toString()) else updated.remove(dialogId.toString())
        if (changed) {
            apply(updated)
        }
    }

    fun removeExcluded(ids: Set<Long>) {
        val updated = HashSet(InuConfig.FEED_EXCLUDED_CHANNELS.value)
        var changed = false
        for (id in ids) {
            changed = changed or updated.remove(id.toString())
        }
        if (changed) {
            apply(updated)
        }
    }

    fun excludeAll(ids: Collection<Long>) {
        val updated = HashSet(InuConfig.FEED_EXCLUDED_CHANNELS.value)
        var changed = false
        for (id in ids) {
            changed = changed or updated.add(id.toString())
        }
        if (changed) {
            apply(updated)
        }
    }

    private fun apply(updated: Set<String>) {
        InuConfig.FEED_EXCLUDED_CHANNELS.value = updated
        FeedChannelSet.invalidate()
    }

    companion object {
        private const val MEMBERSHIP_TTL_MS = 5_000L
        private val instance = FeedConfig()

        @JvmStatic
        fun getInstance(account: Int): FeedConfig = instance
    }
}
