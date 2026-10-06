package desu.inugram.helpers.dialogs

import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.UserConfig
import org.telegram.messenger.support.LongSparseIntArray
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment

// entiny: local folders live purely client-side; rows persist via MessagesStorage.saveDialogFilter and are shielded from remote prune
object LocalFoldersHelper {

    const val LOCAL_FILTER_ID_BASE = 100000

    @JvmStatic
    fun isLocal(filterId: Int): Boolean = filterId >= LOCAL_FILTER_ID_BASE

    @JvmStatic
    fun hasLocalFolders(): Boolean {
        for (filter in MessagesController.getInstance(UserConfig.selectedAccount).dialogFilters) {
            if (isLocal(filter.id)) return true
        }
        return false
    }

    @JvmStatic
    fun nextLocalFilterId(): Int {
        var maxId = LOCAL_FILTER_ID_BASE - 1
        for (filter in MessagesController.getInstance(UserConfig.selectedAccount).dialogFilters) {
            if (isLocal(filter.id) && filter.id > maxId) maxId = filter.id
        }
        return maxId + 1
    }

    @JvmStatic
    fun processAddLocalFilter(
        filter: MessagesController.DialogFilter,
        newFilterFlags: Int,
        newFilterName: String,
        newFilterNameEntities: ArrayList<TLRPC.MessageEntity>,
        newFilterNoanimate: Boolean,
        newFilterColor: Int,
        newAlwaysShow: ArrayList<Long>,
        newNeverShow: ArrayList<Long>,
        newPinned: LongSparseIntArray,
        creatingNew: Boolean,
        atBegin: Boolean,
        hasUserChanged: Boolean,
        resetUnreadCounter: Boolean,
        progress: Boolean,
        fragment: BaseFragment,
        onFinish: Runnable?
    ) {
        if (filter.flags != newFilterFlags || hasUserChanged) {
            filter.pendingUnreadCount = -1
            if (resetUnreadCounter) {
                filter.unreadCount = -1
            }
        }
        filter.flags = newFilterFlags
        filter.name = newFilterName
        filter.entities = newFilterNameEntities
        filter.color = newFilterColor
        filter.neverShow = newNeverShow
        filter.alwaysShow = newAlwaysShow
        filter.title_noanimate = newFilterNoanimate
        filter.pinnedDialogs = newPinned
        if (creatingNew) {
            fragment.messagesController.addFilter(filter, atBegin)
        } else {
            fragment.messagesController.onFilterUpdate(filter)
        }
        fragment.messagesStorage.saveDialogFilter(filter, atBegin, true)
        fragment.messagesStorage.saveDialogFiltersOrder()
        saveIconsLater(fragment.messagesStorage, fragment.messagesController.dialogFilters)
        onFinish?.run()
    }

    @JvmStatic
    fun saveIconsLater(storage: MessagesStorage, filters: List<MessagesController.DialogFilter>) {
        val snapshot = HashMap<Int, String?>()
        for (filter in filters) snapshot[filter.id] = filter.inu_emoticon
        storage.storageQueue.postRunnable { FolderHelper.saveMetaMap(storage, snapshot) }
    }
}
