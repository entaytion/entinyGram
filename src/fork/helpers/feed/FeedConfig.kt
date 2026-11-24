package desu.inugram.helpers.feed

import desu.inugram.InuConfig
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig

class FeedConfig private constructor(account: Int) {

    val categories = FeedCategories(account)

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

    fun setFolder(filterId: Int) {
        categories.select("")
        if (folderFilterId == filterId) return
        folderFilterId = filterId
        FeedChannelSet.invalidate()
    }

    fun selectCategory(id: String) {
        categories.select(id)
        folderFilterId = 0
    }

    val selectionKey: String
        get() = categories.selected?.let { "category_${it.id}" } ?: "folder_$folderFilterId"

    fun isHidden(account: Int, dialogId: Long): Boolean =
        categories.selected?.let { dialogId !in it.channels } ?: (isExcluded(dialogId) || !inFolder(account, dialogId))

    private fun inFolder(account: Int, dialogId: Long): Boolean {
        val filterId = folderFilterId
        if (filterId == 0) return true
        val filter = MessagesController.getInstance(account).dialogFilters?.firstOrNull { it.id == filterId } ?: return true
        if (filter.isDefault) return true
        return filter.includesDialog(AccountInstance.getInstance(account), dialogId)
    }

    class Snapshot(
        val generation: Int,
        val includeArchived: Boolean,
        private val excluded: Set<Long>,
        private val folderDialogs: Set<Long>?,
    ) {
        fun isHidden(dialogId: Long): Boolean = dialogId in excluded || folderDialogs?.contains(dialogId) == false
    }

    fun snapshot(account: Int): Snapshot {
        val category = categories.selected
        if (category != null) return Snapshot(generation, includeArchived, emptySet(), category.channels)
        val controller = MessagesController.getInstance(account)
        val filter = controller.dialogFilters?.firstOrNull { it.id == folderFilterId && !it.isDefault }
        val folderDialogs = filter?.let {
            val included = HashSet<Long>()
            val dialogs = controller.dialogs_dict
            for (index in 0 until dialogs.size()) {
                val dialogId = dialogs.keyAt(index)
                if (it.includesDialog(AccountInstance.getInstance(account), dialogId)) included.add(dialogId)
            }
            included
        }
        return Snapshot(generation, includeArchived, excludedSnapshot, folderDialogs)
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
        private val instances = Array(UserConfig.MAX_ACCOUNT_COUNT) { FeedConfig(it) }

        @JvmStatic
        fun getInstance(account: Int): FeedConfig = instances[account]
    }
}
