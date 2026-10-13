package desu.inugram.ui.settings

import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import android.view.View
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import desu.inugram.helpers.menu.ChatMenuConfig
// #if PLUGINS
import desu.inugram.helpers.plugins.ui.PluginActions
// #endif
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R

class ChatMenuOrderActivity : MenuOrderActivity<ChatMenuConfig.Item>() {
    override val config get() = InuConfig.CHAT_MENU_ITEMS
    override val infoStringRes = R.string.InuChatMenuOrderInfo
    override val headerStringRes = R.string.InuChatMenuItems
    override val resetStringRes = R.string.InuChatMenuReset
    // #if PLUGINS
    override val pluginActionKind = PluginActions.KIND_CHAT
    // #endif

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuChatMenuOrder)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        super.fillItems(items, adapter)
        items.add(UItem.asShadow(null))
        items.add(UItem.asCheck(TOGGLE_GROUP_ARCHIVES, LocaleController.getString(R.string.InuGroupChatArchives)).setChecked(InuConfig.GROUP_CHAT_ARCHIVES.value))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id == TOGGLE_GROUP_ARCHIVES) {
            InuConfig.GROUP_CHAT_ARCHIVES.toggle()
            listView.adapter.update(true)
        } else {
            super.onClick(item, view, position, x, y)
        }
    }

    companion object {
        private val TOGGLE_GROUP_ARCHIVES = InuUtils.generateId()
    }
}
