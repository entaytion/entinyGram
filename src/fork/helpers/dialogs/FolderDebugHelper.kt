package desu.inugram.helpers.dialogs

import desu.inugram.helpers.DebugLogUtils
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessagesController
import org.telegram.ui.DialogsActivity

object FolderDebugHelper {
    private var lastFallbackKey: String? = null

    @JvmStatic
    fun isEnabled(): Boolean = DebugLogUtils.isEnabled()

    private fun log(message: String) {
        FileLog.d("InuFolders $message from ${DebugLogUtils.getCaller()}")
    }

    private fun describe(controller: MessagesController, filter: MessagesController.DialogFilter?): String {
        if (filter == null) return "null"
        return "{id=${filter.id} local=${filter.localId} default=${filter.isDefault} inList=${controller.dialogFilters.contains(filter)}}"
    }

    private fun describeSlots(controller: MessagesController): String =
        "slots=[${describe(controller, controller.selectedDialogFilter[0])}, ${describe(controller, controller.selectedDialogFilter[1])}]"

    private fun describe(activity: DialogsActivity): String {
        val sb = StringBuilder("da@").append(Integer.toHexString(System.identityHashCode(activity)))
            .append(" init=").append(activity.initialDialogsType)
            .append(" paused=").append(activity.isPaused())
        activity.viewPages?.forEachIndexed { i, page ->
            sb.append(" p").append(i).append("={type=").append(page.dialogsType)
                .append(" adapter=").append(page.dialogsAdapter?.dialogsType)
                .append(" sel=").append(page.selectedType)
                .append(" vis=").append(page.visibility)
                .append(" tx=").append(page.translationX).append('}')
        }
        activity.filterTabsView?.let {
            sb.append(" tab={id=").append(it.currentTabId).append(" stable=").append(it.currentTabStableId)
                .append(" vis=").append(it.visibility).append('}')
        }
        sb.append(' ').append(describeSlots(activity.messagesController))
        return sb.toString()
    }

    @JvmStatic
    fun onSelectFilter(controller: MessagesController, index: Int, filter: MessagesController.DialogFilter?) {
        if (!isEnabled()) return
        lastFallbackKey = null
        val other = controller.selectedDialogFilter[1 - index]
        log("selectDialogFilter index=$index new=${describe(controller, filter)} nullsOther=${other != null && other === filter} before ${describeSlots(controller)}")
    }

    @JvmStatic
    fun onFilterFallback(activity: DialogsActivity, dialogsType: Int) {
        if (!isEnabled()) return
        val key = "${System.identityHashCode(activity)}:$dialogsType"
        if (key == lastFallbackKey) return
        lastFallbackKey = key
        log("getDialogsArray null slot fallback type=$dialogsType ${describe(activity)}")
    }

    @JvmStatic
    fun onEvent(activity: DialogsActivity, event: String) {
        if (!isEnabled()) return
        log("$event ${describe(activity)}")
    }
}
