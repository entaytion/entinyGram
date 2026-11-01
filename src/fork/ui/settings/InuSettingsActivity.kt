package desu.inugram.ui.settings

import android.content.Context
import android.view.View
import android.widget.EditText
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.browser.Browser
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.ProfileActivity
import java.util.Locale

class InuSettingsActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuSettings)

    private var searchAdapter: ProfileActivity.SearchAdapter? = null
    private var isSearchOpen = false

    override fun createView(context: Context): View {
        return super.createView(context).also {
            listView.overScrollMode = View.OVER_SCROLL_NEVER
            listView.isVerticalScrollBarEnabled = false

            val sAdapter = object : ProfileActivity.SearchAdapter(this, context) {
                override fun notifyDataSetChanged() {
                    if (isSearchOpen) {
                        listView.adapter.update(true)
                    }
                }
            }
            searchAdapter = sAdapter
            val menu = actionBar.createMenu()
            val searchItem = menu.addItem(0, R.drawable.outline_header_search)
                .setIsSearchField(true)
            searchItem.setSearchFieldHint(LocaleController.getString(R.string.Search))
            searchItem.setActionBarMenuItemSearchListener(object : ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                override fun onSearchExpand() {
                    isSearchOpen = true
                    sAdapter.search("")
                    listView.adapter.update(false)
                }

                override fun onSearchCollapse() {
                    isSearchOpen = false
                    sAdapter.search(null)
                    listView.adapter.update(false)
                }

                override fun onTextChanged(searchField: EditText) {
                    // entiny: lowercase query to match SearchAdapter lowercased entry titles
                    sAdapter.search(searchField.text.toString().lowercase(Locale.getDefault()))
                }
            })
        }
    }

    private fun createHeaderView(): View {
        val context = context ?: return View(org.telegram.messenger.ApplicationLoader.applicationContext)
        return InuSettingsHeader(context)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        if (isSearchOpen) {
            searchAdapter?.fillItems(items)
            return
        }

        items.add(UItem.asCustomShadow(createHeaderView()))
        items.add(UItem.asShadow(null))

        items.add(mkSubPageButton(CAT_APPEARANCE, R.drawable.msg_palette, LocaleController.getString(R.string.InuCategoryAppearance)))
        items.add(mkSubPageButton(CAT_HOME, R.drawable.msg_home_solar, LocaleController.getString(R.string.InuHomeAndChatList)))
        items.add(mkSubPageButton(CAT_CHATS, R.drawable.msg_discussion, LocaleController.getString(R.string.InuChatsAndMessages)))
        items.add(mkSubPageButton(CAT_PROFILE_ACCOUNTS, R.drawable.msg_contacts, LocaleController.getString(R.string.InuProfileAndAccounts)))
        items.add(mkSubPageButton(CAT_PRIVACY, R.drawable.inu_tabler_shield_check, LocaleController.getString(R.string.InuPrivacyAndFreedom)))
        items.add(mkSubPageButton(CAT_ANNOYANCES, R.drawable.inu_tabler_shield_cancel, LocaleController.getString(R.string.InuAnnoyances)))
        items.add(mkSubPageButton(CAT_SYSTEM, R.drawable.inu_tabler_device_floppy, LocaleController.getString(R.string.InuRootSystem)))
        // #if PLUGINS
        items.add(mkSubPageButton(BUTTON_PLUGINS, R.drawable.inu_tabler_code, addExperimentalSpan(LocaleController.getString(R.string.InuPlugins))))
        // #endif
        items.add(UItem.asShadow(null))

        items.add(mkSubPageButton(CAT_ABOUT, R.drawable.msg_info, LocaleController.getString(R.string.InuAbout)))
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.instanceOf(org.telegram.ui.Cells.SettingsSearchCell.Factory::class.java)) {
            val result = item.`object` as? ProfileActivity.SearchAdapter.SearchResult
            if (result != null) {
                result.open(parentLayout)
                searchAdapter?.addRecent(result)
            }
            return
        }
        val ctx = context ?: return
        when (item.id) {
            CAT_APPEARANCE -> presentFragment(AppearanceSettingsActivity())
            CAT_HOME -> presentFragment(SchemaPageActivity(Schemas.HOME))
            CAT_CHATS -> presentFragment(SchemaPageActivity(Schemas.CHATS))
            CAT_PROFILE_ACCOUNTS -> presentFragment(SchemaPageActivity(Schemas.PROFILE))
            CAT_PRIVACY -> presentFragment(SchemaPageActivity(Schemas.PRIVACY))
            CAT_ANNOYANCES -> presentFragment(SchemaPageActivity(Schemas.ANNOYANCES))
            CAT_SYSTEM -> presentFragment(SchemaPageActivity(Schemas.SYSTEM))
            CAT_ABOUT -> presentFragment(AdditionalSettingsActivity())
            // #if PLUGINS
            BUTTON_PLUGINS -> presentFragment(PluginsActivity())
            // #endif
        }
    }

    companion object {
        private val CAT_APPEARANCE = InuUtils.generateId()
        private val CAT_CHATS = InuUtils.generateId()
        private val CAT_PROFILE_ACCOUNTS = InuUtils.generateId()
        private val CAT_PRIVACY = InuUtils.generateId()
        private val CAT_ANNOYANCES = InuUtils.generateId()
        private val CAT_HOME = InuUtils.generateId()
        private val CAT_SYSTEM = InuUtils.generateId()
        private val BUTTON_PLUGINS = InuUtils.generateId()
        private val CAT_ABOUT = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "root",
            titleRes = R.string.InuSettings,
            iconRes = R.drawable.icon_settings_inu,
            factory = ::InuSettingsActivity,
            entries = listOf(
            ),
        )
    }
}
