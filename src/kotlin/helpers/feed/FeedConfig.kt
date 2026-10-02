package desu.inugram.helpers.feed

import desu.inugram.InuConfig

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
        private val instance = FeedConfig()

        @JvmStatic
        fun getInstance(account: Int): FeedConfig = instance
    }
}
