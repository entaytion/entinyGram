package desu.inugram.helpers.feed

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig

class FeedChannelRegistry private constructor(
    @JvmField val currentAccount: Int,
) : NotificationCenter.NotificationCenterDelegate {

    fun interface Listener {
        fun onFeedChannelsChanged(added: HashSet<Long>, removed: HashSet<Long>)
    }

    private val channelIds = HashSet<Long>()
    private val listeners = ArrayList<Listener>()
    private var built = false
    private var rebuildScheduled = false

    private val rebuildRunnable = Runnable {
        rebuildScheduled = false
        rebuild(true)
    }

    init {
        AndroidUtilities.runOnUIThread {
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.dialogsNeedReload)
        }
    }

    private fun ensureBuilt() {
        if (built) {
            return
        }
        built = true
        rebuild(false)
    }

    private fun rebuild(notify: Boolean) {
        val messagesController = MessagesController.getInstance(currentAccount)
        val dialogs = messagesController.dialogs_dict
        val current = HashSet<Long>()
        for (index in 0 until dialogs.size()) {
            val dialog = dialogs.valueAt(index)
            if (dialog == null || !DialogObject.isChatDialog(dialog.id)) {
                continue
            }
            if (FeedController.isEligibleChannel(messagesController.getChat(-dialog.id))) {
                current.add(dialog.id)
            }
        }

        var added: HashSet<Long>? = null
        var removed: HashSet<Long>? = null
        for (dialogId in current) {
            if (!channelIds.contains(dialogId)) {
                if (added == null) {
                    added = HashSet()
                }
                added.add(dialogId)
            }
        }
        for (dialogId in channelIds) {
            if (!current.contains(dialogId)) {
                if (removed == null) {
                    removed = HashSet()
                }
                removed.add(dialogId)
            }
        }
        if (added == null && removed == null) {
            return
        }

        channelIds.clear()
        channelIds.addAll(current)
        if (!notify) {
            return
        }
        val addedSet = added ?: HashSet()
        val removedSet = removed ?: HashSet()
        for (index in listeners.size - 1 downTo 0) {
            listeners[index].onFeedChannelsChanged(addedSet, removedSet)
        }
    }

    fun addListener(listener: Listener) {
        ensureBuilt()
        if (listeners.contains(listener)) {
            return
        }
        listeners.add(listener)
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id != NotificationCenter.dialogsNeedReload) {
            return
        }
        ensureBuilt()
        if (rebuildScheduled) {
            return
        }
        rebuildScheduled = true
        AndroidUtilities.runOnUIThread(rebuildRunnable, REBUILD_DELAY)
    }

    companion object {
        private const val REBUILD_DELAY = 500L

        private val instances = arrayOfNulls<FeedChannelRegistry>(UserConfig.MAX_ACCOUNT_COUNT)
        private val locks = Array(UserConfig.MAX_ACCOUNT_COUNT) { Any() }

        @JvmStatic
        fun getInstance(num: Int): FeedChannelRegistry {
            var cached = instances[num]
            if (cached != null) {
                return cached
            }
            synchronized(locks[num]) {
                cached = instances[num]
                if (cached == null) {
                    cached = FeedChannelRegistry(num)
                    instances[num] = cached
                }
            }
            return cached!!
        }
    }
}
