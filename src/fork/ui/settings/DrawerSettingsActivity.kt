package desu.inugram.ui.settings

import android.os.Build
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class DrawerSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuSideMenu)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuSideMenu)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_NAVIGATION_DRAWER,
                R.string.InuNavigationDrawer,
                R.string.InuNavigationDrawerInfo,
                InuConfig.NAVIGATION_DRAWER.value,
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && InuConfig.NAVIGATION_DRAWER.value) {
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_DRAWER_BACK_GESTURE,
                    R.string.InuDrawerBackGesture,
                    R.string.InuDrawerBackGestureInfo,
                    InuConfig.DRAWER_BACK_GESTURE.value,
                )
            )
        }
        if (InuConfig.NAVIGATION_DRAWER.value) {
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_DRAWER_M3_SECTIONS,
                    R.string.InuDrawerM3Sections,
                    R.string.InuDrawerM3SectionsInfo,
                    InuConfig.DRAWER_M3_SECTIONS.value,
                )
            )
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_SHOW_DRAWER_ACCOUNTS,
                    R.string.InuShowDrawerAccounts,
                    R.string.InuShowDrawerAccountsInfo,
                    InuConfig.SHOW_DRAWER_ACCOUNTS.value,
                )
            )
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_HIDE_BOTTOM_TABS,
                    R.string.InuBottomTabsHide,
                    R.string.InuBottomTabsHideInfo,
                    InuConfig.BOTTOM_TABS_HIDE.value,
                )
            )
            items.add(mkSubPageButton(BUTTON_DRAWER_MENU_ORDER, R.drawable.inu_tabler_list, LocaleController.getString(R.string.InuDrawerMenuOrder)))
        }
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_NAVIGATION_DRAWER -> {
                val new = InuConfig.NAVIGATION_DRAWER.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                listView.adapter.update(true)
                showRestartBulletin()
            }

            TOGGLE_DRAWER_BACK_GESTURE -> {
                val new = InuConfig.DRAWER_BACK_GESTURE.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                showRestartBulletin()
            }

            TOGGLE_DRAWER_M3_SECTIONS -> {
                val new = InuConfig.DRAWER_M3_SECTIONS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface)
            }

            TOGGLE_SHOW_DRAWER_ACCOUNTS -> {
                val new = InuConfig.SHOW_DRAWER_ACCOUNTS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface)
            }

            TOGGLE_HIDE_BOTTOM_TABS -> {
                val new = InuConfig.BOTTOM_TABS_HIDE.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                listView.adapter.update(true)
                softRebuild()
            }

            BUTTON_DRAWER_MENU_ORDER -> presentFragment(DrawerMenuOrderActivity())
        }
    }

    companion object {
        private val TOGGLE_NAVIGATION_DRAWER = InuUtils.generateId()
        private val TOGGLE_DRAWER_BACK_GESTURE = InuUtils.generateId()
        private val TOGGLE_DRAWER_M3_SECTIONS = InuUtils.generateId()
        private val TOGGLE_SHOW_DRAWER_ACCOUNTS = InuUtils.generateId()
        private val TOGGLE_HIDE_BOTTOM_TABS = InuUtils.generateId()
        private val BUTTON_DRAWER_MENU_ORDER = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "side-menu",
            titleRes = R.string.InuSideMenu,
            iconRes = R.drawable.inu_tabler_menu_2,
            factory = ::DrawerSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("hide-bottom-tabs", R.string.InuBottomTabsHide, TOGGLE_HIDE_BOTTOM_TABS),
                // entiny: slugs kept verbatim from AppearanceSettingsActivity so old deeplinks keep resolving
                SearchRegistry.Entry("navigation-drawer", R.string.InuNavigationDrawer, TOGGLE_NAVIGATION_DRAWER),
                SearchRegistry.Entry("drawer-back-gesture", R.string.InuDrawerBackGesture, TOGGLE_DRAWER_BACK_GESTURE),
                SearchRegistry.Entry("drawer-m3-sections", R.string.InuDrawerM3Sections, TOGGLE_DRAWER_M3_SECTIONS),
                SearchRegistry.Entry("show-drawer-accounts", R.string.InuShowDrawerAccounts, TOGGLE_SHOW_DRAWER_ACCOUNTS),
            ),
        )
    }
}
