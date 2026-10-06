package desu.inugram.ui.settings

import android.content.Context
import android.view.View
import android.widget.EditText
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.browser.Browser
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.ProfileActivity
import java.util.Locale

class InuSettingsActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuSettings)

    private var searchAdapter: ProfileActivity.SearchAdapter? = null
    private var isSearchOpen = false
    private var headerTapCount = 0
    private var lastHeaderTapAt = 0L

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
        return InuSettingsHeader(context).apply {
            onHeaderClick = { onHeaderTap() }
        }
    }

    private fun onHeaderTap() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastHeaderTapAt > HEADER_TAP_WINDOW_MS) headerTapCount = 0
        lastHeaderTapAt = now
        headerTapCount++
        if (headerTapCount == HEADER_TAP_COUNT) {
            headerTapCount = 0
            val alreadyUnlocked = InuConfig.NICHE_SETTINGS_UNLOCKED.value
            if (!alreadyUnlocked) {
                InuConfig.NICHE_SETTINGS_UNLOCKED.value = true
                listView.adapter.update(true)
            }
            BulletinFactory.of(this).createSimpleBulletin(
                R.raw.chats_infotip,
                LocaleController.getString(
                    if (alreadyUnlocked) R.string.InuNicheAlreadyUnlockedToast
                    else R.string.InuNicheUnlockedToast
                )
            ).show()
        }
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        if (isSearchOpen) {
            searchAdapter?.fillItems(items)
            return
        }

        items.add(UItem.asCustomShadow(createHeaderView()))
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuRootInterface)))
        items.add(mkSubPageButton(CAT_APPEARANCE, R.drawable.msg_palette, LocaleController.getString(R.string.InuCategoryAppearance)))
        items.add(mkSubPageButton(CAT_CHATS, R.drawable.msg_viewchats, LocaleController.getString(R.string.InuCategoryChats)))
        items.add(mkSubPageButton(CAT_MESSAGES, R.drawable.msg_discussion, LocaleController.getString(R.string.InuMessages)))
        items.add(mkSubPageButton(CAT_ANNOYANCES, R.drawable.inu_tabler_shield_cancel, LocaleController.getString(R.string.InuAnnoyances)))
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuRootPrivacy)))
        items.add(mkSubPageButton(CAT_AI, R.drawable.inu_tabler_sparkles, LocaleController.getString(R.string.InuAiCompose)))
        items.add(mkSubPageButton(CAT_TRANSLATOR, R.drawable.msg_translate, LocaleController.getString(R.string.InuTranslator)))
        items.add(mkSubPageButton(CAT_BEHAVIOR, R.drawable.inu_tabler_adjustments_horizontal, LocaleController.getString(R.string.InuCategoryBehavior)))
        items.add(mkSubPageButton(CAT_PRIVACY, R.drawable.inu_tabler_shield_check, LocaleController.getString(R.string.InuCategoryPrivacy)))
        items.add(mkSubPageButton(BUTTON_TOS, R.drawable.inu_tabler_lock_open, LocaleController.getString(R.string.InuTOS)))
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuRootSystem)))
        // #if PLUGINS
        items.add(mkSubPageButton(BUTTON_PLUGINS, R.drawable.inu_tabler_code, addExperimentalSpan(LocaleController.getString(R.string.InuPlugins))))
        // #endif
        items.add(mkSubPageButton(CAT_SYSTEM, R.drawable.inu_tabler_device_floppy, LocaleController.getString(R.string.InuCategoryBackup)))
        if (InuConfig.NICHE_SETTINGS_UNLOCKED.value) {
            items.add(mkSubPageButton(CAT_NICHE, R.drawable.inu_tabler_skull, LocaleController.getString(R.string.InuNicheSettings)))
        }
        items.add(UItem.asShadow(null))

        items.add(
            UItem.asButton(
                BUTTON_CHANNEL_LINK,
                R.drawable.inu_tabler_brand_telegram,
                LocaleController.getString(R.string.InuAboutChannel),
                "@entinyGram"
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_GITHUB,
                R.drawable.inu_tabler_brand_github,
                LocaleController.getString(R.string.InuAboutGitHub),
                "Entaytion/entinyGram"
            )
        )
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
            CAT_CHATS -> presentFragment(CategoryChatsSettingsActivity())
            CAT_MESSAGES -> presentFragment(MessagesSettingsActivity())
            CAT_AI -> presentFragment(AiSettingsActivity())
            CAT_TRANSLATOR -> presentFragment(TranslatorSettingsActivity())
            CAT_BEHAVIOR -> presentFragment(BehaviorSettingsActivity())
            CAT_PRIVACY -> presentFragment(PrivacySecurityActivity())
            CAT_ANNOYANCES -> presentFragment(AnnoyancesSettingsActivity())
            BUTTON_TOS -> presentFragment(TosSettingsActivity())
            // #if PLUGINS
            BUTTON_PLUGINS -> presentFragment(PluginsActivity())
            // #endif
            CAT_SYSTEM -> presentFragment(AdditionalSettingsActivity())
            CAT_NICHE -> presentFragment(NicheSettingsActivity())
            BUTTON_CHANNEL_LINK -> Browser.openUrl(ctx, "https://t.me/entinyGram")
            BUTTON_GITHUB -> Browser.openUrl(ctx, "https://github.com/Entaytion/EntinyGram")
        }
    }

    companion object {
        private val CAT_APPEARANCE = InuUtils.generateId()
        private val CAT_CHATS = InuUtils.generateId()
        private val CAT_MESSAGES = InuUtils.generateId()
        private val CAT_AI = InuUtils.generateId()
        private val CAT_TRANSLATOR = InuUtils.generateId()
        private val CAT_BEHAVIOR = InuUtils.generateId()
        private val CAT_PRIVACY = InuUtils.generateId()
        private val CAT_ANNOYANCES = InuUtils.generateId()
        private val BUTTON_TOS = InuUtils.generateId()
        private val BUTTON_PLUGINS = InuUtils.generateId()
        private val CAT_SYSTEM = InuUtils.generateId()
        private val CAT_NICHE = InuUtils.generateId()
        private const val HEADER_TAP_COUNT = 5
        private const val HEADER_TAP_WINDOW_MS = 2000L
        private val BUTTON_CHANNEL_LINK = InuUtils.generateId()
        private val BUTTON_GITHUB = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "root",
            titleRes = R.string.InuSettings,
            iconRes = R.drawable.icon_settings_inu,
            factory = ::InuSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("channel", R.string.InuAboutChannel, BUTTON_CHANNEL_LINK),
                SearchRegistry.Entry("github", R.string.InuAboutGitHub, BUTTON_GITHUB),
                SearchRegistry.Entry("open-translator", R.string.InuTranslator, CAT_TRANSLATOR),
            ),
        )
    }
}
