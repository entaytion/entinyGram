package desu.inugram.ui.settings

import android.view.View
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.dialogs.SystemFoldersHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class SystemFoldersSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuSystemFolders)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        for (entry in SystemFoldersHelper.entries()) {
            val text = LocaleController.getString(entry.type.titleRes)
            items.add(UItem.asCheck(ROW_BASE + entry.type.ordinal, text).setChecked(entry.enabled))
        }
        if (SystemFoldersHelper.missingTypes().isNotEmpty()) {
            items.add(UItem.asButton(BUTTON_ADD_RECOMMENDED, LocaleController.getString(R.string.InuSystemFoldersAddRecommended)))
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuSystemFoldersInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id == BUTTON_ADD_RECOMMENDED) {
            SystemFoldersHelper.addRecommended()
            return
        }
        val type = SystemFoldersHelper.Type.entries.getOrNull(item.id - ROW_BASE) ?: return
        val entry = SystemFoldersHelper.entries().firstOrNull { it.type == type } ?: return
        val new = !entry.enabled
        SystemFoldersHelper.setEnabled(type, new)
        listView?.adapter?.update(true)
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        val type = SystemFoldersHelper.Type.entries.getOrNull(item.id - ROW_BASE) ?: return false
        ItemOptions.makeOptions(this, view)
            .add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true) {
                SystemFoldersHelper.delete(type)
                listView?.adapter?.update(true)
            }
            .show()
        return true
    }

    companion object {
        private val BUTTON_ADD_RECOMMENDED = InuUtils.generateId()
        private const val ROW_BASE = 23500
    }
}
