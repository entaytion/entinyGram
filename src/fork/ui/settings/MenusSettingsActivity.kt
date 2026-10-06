package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class MenusSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuMenus)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(false)
    }

    private fun entry(id: Int, iconRes: Int, titleRes: Int, descRes: Int): UItem =
        mkTwoLineEntry(id, iconRes, LocaleController.getString(titleRes), LocaleController.getString(descRes))

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuMenus)))
        // entiny: stock ⋮ button on the chats screen is not created while the side menu is on
        if (InuConfig.NAVIGATION_DRAWER.value) {
            items.add(entry(BUTTON_DRAWER, R.drawable.inu_tabler_menu_2, R.string.InuSideMenu, R.string.InuMenuSideDesc))
        } else {
            items.add(entry(BUTTON_DIALOGS, R.drawable.inu_tabler_list, R.string.InuDialogsMenuOrder, R.string.InuMenuDialogsDesc))
        }
        items.add(entry(BUTTON_CHAT, R.drawable.inu_tabler_menu_2, R.string.InuChatMenuOrder, R.string.InuMenuChatDesc))
        items.add(entry(BUTTON_MESSAGE, R.drawable.inu_tabler_list, R.string.InuMessageMenuOrder, R.string.InuMenuMessageDesc))
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuUserProfile)))
        items.add(entry(BUTTON_PROFILE_SETTINGS, R.drawable.inu_tabler_menu_2, R.string.InuProfileSettingsRowsOrder, R.string.InuMenuProfileSettingsDesc))
        items.add(entry(BUTTON_PROFILE_INFO, R.drawable.inu_tabler_menu_2, R.string.InuProfileInfoRowsOrder, R.string.InuMenuProfileInfoDesc))
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val target: BaseFragment = when (item.id) {
            BUTTON_DRAWER -> DrawerMenuOrderActivity()
            BUTTON_DIALOGS -> DialogsMenuOrderActivity()
            BUTTON_CHAT -> ChatMenuOrderActivity()
            BUTTON_MESSAGE -> MessageMenuOrderActivity()
            BUTTON_PROFILE_SETTINGS -> ProfileSettingsMenuOrderActivity()
            BUTTON_PROFILE_INFO -> ProfileInfoMenuOrderActivity()
            else -> return
        }
        presentFragment(target)
    }

    companion object {
        private val BUTTON_DRAWER = InuUtils.generateId()
        private val BUTTON_DIALOGS = InuUtils.generateId()
        private val BUTTON_CHAT = InuUtils.generateId()
        private val BUTTON_MESSAGE = InuUtils.generateId()
        private val BUTTON_PROFILE_SETTINGS = InuUtils.generateId()
        private val BUTTON_PROFILE_INFO = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "menus",
            titleRes = R.string.InuMenus,
            iconRes = R.drawable.inu_tabler_menu_2,
            factory = ::MenusSettingsActivity,
            entries = listOf(
                // entiny: slugs kept verbatim from the former Chats/Messages/Behavior pages so old deeplinks keep resolving
                SearchRegistry.Entry("dialogs-menu-order", R.string.InuDialogsMenuOrder, BUTTON_DIALOGS),
                SearchRegistry.Entry("chat-menu-order", R.string.InuChatMenuOrder, BUTTON_CHAT),
                SearchRegistry.Entry("message-menu-order", R.string.InuMessageMenuOrder, BUTTON_MESSAGE),
                SearchRegistry.Entry("profile-settings-rows-order", R.string.InuProfileSettingsRowsOrder, BUTTON_PROFILE_SETTINGS),
                SearchRegistry.Entry("profile-info-rows-order", R.string.InuProfileInfoRowsOrder, BUTTON_PROFILE_INFO),
            ),
        )
    }
}
