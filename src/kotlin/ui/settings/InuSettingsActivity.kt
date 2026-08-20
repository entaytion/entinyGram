package desu.inugram.ui.settings

import android.content.Context
import android.view.View
import android.widget.EditText
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.update.UpdateHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.browser.Browser
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.ProfileActivity

class InuSettingsActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuSettings)

    private var searchAdapter: ProfileActivity.SearchAdapter? = null

    override fun createView(context: Context): View {
        return super.createView(context).also {
            listView.overScrollMode = View.OVER_SCROLL_NEVER
            listView.isVerticalScrollBarEnabled = false

            val originalAdapter = listView.adapter
            val sAdapter = ProfileActivity.SearchAdapter(this, context)
            searchAdapter = sAdapter
            val menu = actionBar.createMenu()
            val searchItem = menu.addItem(0, R.drawable.outline_header_search)
                .setIsSearchField(true)
            searchItem.setSearchFieldHint(LocaleController.getString(R.string.Search))
            searchItem.setActionBarMenuItemSearchListener(object : ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                override fun onSearchExpand() {
                    (listView as org.telegram.ui.Components.RecyclerListView).setAdapter(sAdapter)
                }

                override fun onSearchCollapse() {
                    (listView as org.telegram.ui.Components.RecyclerListView).setAdapter(originalAdapter)
                    sAdapter.search(null)
                }

                override fun onTextChanged(searchField: EditText) {
                    sAdapter.search(searchField.text.toString())
                }
            })
        }
    }

    private fun createHeaderView(): View {
        val context = context ?: return View(org.telegram.messenger.ApplicationLoader.applicationContext)
        return InuSettingsHeader(context).apply {
            onHeaderClick = { checkForUpdates() }
        }
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asCustomShadow(createHeaderView()))
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuCategories)))
        items.add(mkSubPageButton(CAT_APPEARANCE, R.drawable.msg_palette, LocaleController.getString(R.string.InuCategoryAppearance)))
        items.add(mkSubPageButton(CAT_CHATS, R.drawable.msg_viewchats, LocaleController.getString(R.string.InuCategoryChats)))
        items.add(mkSubPageButton(CAT_MESSAGES, R.drawable.msg_discussion, LocaleController.getString(R.string.InuMessages)))
        items.add(mkSubPageButton(CAT_AI, R.drawable.inu_tabler_sparkles, LocaleController.getString(R.string.InuAiCompose)))
        items.add(mkSubPageButton(CAT_BEHAVIOR, R.drawable.inu_tabler_adjustments_horizontal, LocaleController.getString(R.string.InuCategoryBehavior)))
        items.add(mkSubPageButton(CAT_PRIVACY, R.drawable.inu_tabler_shield_check, LocaleController.getString(R.string.InuCategoryPrivacy)))
        items.add(mkSubPageButton(CAT_ANNOYANCES, R.drawable.inu_tabler_shield_cancel, LocaleController.getString(R.string.InuAnnoyances)))
        items.add(mkSubPageButton(BUTTON_TOS, R.drawable.inu_tabler_lock_open, LocaleController.getString(R.string.InuTOS)))
        items.add(mkSubPageButton(CAT_SYSTEM, R.drawable.inu_tabler_device_floppy, LocaleController.getString(R.string.InuCategoryBackup)))
        items.add(UItem.asShadow(null))

        // Channel & GitHub links
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
        val ctx = context ?: return
        when (item.id) {
            CAT_APPEARANCE -> presentFragment(AppearanceSettingsActivity())
            CAT_CHATS -> presentFragment(CategoryChatsSettingsActivity())
            CAT_MESSAGES -> presentFragment(MessagesSettingsActivity())
            CAT_AI -> presentFragment(AiSettingsActivity())
            CAT_BEHAVIOR -> presentFragment(BehaviorSettingsActivity())
            CAT_PRIVACY -> presentFragment(PrivacySecurityActivity())
            CAT_ANNOYANCES -> presentFragment(AnnoyancesSettingsActivity())
            BUTTON_TOS -> presentFragment(TosSettingsActivity())
            CAT_SYSTEM -> presentFragment(AdditionalSettingsActivity())
            BUTTON_CHANNEL_LINK -> Browser.openUrl(ctx, "https://t.me/entinyGram")
            BUTTON_GITHUB -> Browser.openUrl(ctx, "https://github.com/Entaytion/EntinyGram")
        }
    }

    private fun checkForUpdates() {
        BulletinFactory.of(this).createSimpleBulletin(
            R.raw.chats_infotip,
            LocaleController.getString(R.string.Checking)
        ).show()
        UpdateHelper.check { result ->
            AndroidUtilities.runOnUIThread {
                val msg: CharSequence = when (result) {
                    UpdateHelper.CheckResult.InFlight ->
                        LocaleController.getString(R.string.Checking)

                    UpdateHelper.CheckResult.UpToDate ->
                        LocaleController.getString(R.string.InuUpdateUpToDate)

                    is UpdateHelper.CheckResult.Updated -> {
                        val ctx = context ?: return@runOnUIThread
                        ApplicationLoader.applicationLoaderInstance?.showUpdateAppPopup(
                            ctx, result.update, UserConfig.selectedAccount,
                        )
                        return@runOnUIThread
                    }

                    is UpdateHelper.CheckResult.Error ->
                        LocaleController.formatString(R.string.InuUpdateError, result.message)
                }
                BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, msg).show()
            }
        }
    }

    companion object {
        private val CAT_APPEARANCE = InuUtils.generateId()
        private val CAT_CHATS = InuUtils.generateId()
        private val CAT_MESSAGES = InuUtils.generateId()
        private val CAT_AI = InuUtils.generateId()
        private val CAT_BEHAVIOR = InuUtils.generateId()
        private val CAT_PRIVACY = InuUtils.generateId()
        private val CAT_ANNOYANCES = InuUtils.generateId()
        private val BUTTON_TOS = InuUtils.generateId()
        private val CAT_SYSTEM = InuUtils.generateId()
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
            ),
        )
    }
}
