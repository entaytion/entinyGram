package desu.inugram.ui.settings

import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import desu.inugram.InuHooks
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.ui.settings.fonts.FontsSettingsActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AppearanceSettingsActivity : SettingsPageActivity() {

    private var animationSpeedSlider: SliderCell? = null
    private var avatarCornerPreview: AvatarCornerPreviewCell? = null
    private var nonIslandInfo: TextView? = null

    private fun nonIslandInfoItem(context: Context): UItem {
        val view = nonIslandInfo ?: TextView(context).apply {
            text = LocaleController.getString(R.string.InuNonIslandHint)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            setPadding(AndroidUtilities.dp(22f), AndroidUtilities.dp(6f), AndroidUtilities.dp(22f), AndroidUtilities.dp(12f))
            layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
        }.also { nonIslandInfo = it }
        return UItem.asCustom(NON_ISLAND_INFO, view)
    }

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuCategoryAppearance)

    private val nonIslandGroup by lazy {
        ExpandableBoolGroup(
            addExperimentalSpan(LocaleController.getString(R.string.InuNonIslandUI)),
            listOf(
                ExpandableBoolGroup.Option(R.string.InuNonIslandFoldersBar, InuConfig.NON_ISLAND_FOLDERS_BAR, TOGGLE_NON_ISLAND_FOLDERS_BAR),
                ExpandableBoolGroup.Option(R.string.InuNonIslandSharedMediaTabs, InuConfig.NON_ISLAND_SHARED_MEDIA_TABS, TOGGLE_NON_ISLAND_SHARED_MEDIA_TABS),
                ExpandableBoolGroup.Option(R.string.InuNonIslandGlobalSearch, InuConfig.NON_ISLAND_GLOBAL_SEARCH, TOGGLE_NON_ISLAND_GLOBAL_SEARCH),
                ExpandableBoolGroup.Option(R.string.InuNonIslandChatElements, InuConfig.NON_ISLAND_CHAT_ELEMENTS, TOGGLE_NON_ISLAND_CHAT_ELEMENTS),
                ExpandableBoolGroup.Option(R.string.InuHideFadeView, InuConfig.HIDE_FADE_VIEW, TOGGLE_HIDE_FADE_VIEW),
                ExpandableBoolGroup.Option(R.string.InuDisableProfileAvatarBlur, InuConfig.DISABLE_PROFILE_AVATAR_BLUR, TOGGLE_DISABLE_PROFILE_AVATAR_BLUR),
            ),
            sectionId = SECTION_NON_ISLAND,
        )
    }

    override val expandableGroups get() = listOf(nonIslandGroup)

    private fun applyNonIslandChanges(ids: Collection<Int>) {
        if (TOGGLE_NON_ISLAND_FOLDERS_BAR in ids || TOGGLE_NON_ISLAND_GLOBAL_SEARCH in ids || TOGGLE_HIDE_FADE_VIEW in ids) softRebuild()
        if (TOGGLE_NON_ISLAND_CHAT_ELEMENTS in ids) InuHooks.syncChatInputRowHeight()
        listView?.adapter?.update(true)
    }

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(false)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val ctx = context ?: return
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuTypographyAndIcons)))
        items.add(mkSubPageButton(BUTTON_FONTS, LocaleController.getString(R.string.InuFonts)))
        items.add(mkTwoLineEntry(BUTTON_ICON_REPLACEMENT, R.drawable.phosphor_palette, LocaleController.getString(R.string.InuIconReplacement), IconPacksSettingsActivity.currentPackLabel()))
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuDesignStyle)))
        items.add(mkTwoLineEntry(BUTTON_IOS_STYLE, R.drawable.msg_newphone, LocaleController.getString(R.string.InuIosSettings), LocaleController.getString(R.string.InuIosSettingsInfo)))
        items.add(mkTwoLineEntry(BUTTON_MATERIAL3, R.drawable.inu_tabler_layout_cards, LocaleController.getString(R.string.InuMaterial3), LocaleController.getString(R.string.InuMaterial3SettingsInfo)))
        nonIslandGroup.addTo(items, nonIslandInfoItem(ctx)) { changed -> applyNonIslandChanges(changed.map { it.id }) }
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuInterfaceElements)))
        items.add(mkTwoLineEntry(BUTTON_MESSAGE_DESIGN, R.drawable.msg_discussion, LocaleController.getString(R.string.InuMessageDesign), LocaleController.getString(R.string.InuMessageDesignInfo)))
        items.add(mkTwoLineEntry(BUTTON_SIDE_MENU, R.drawable.inu_tabler_menu_2, LocaleController.getString(R.string.InuSideMenu), LocaleController.getString(R.string.InuSideMenuInfo)))
        items.add(mkTwoLineEntry(BUTTON_MENUS, R.drawable.inu_tabler_list, LocaleController.getString(R.string.InuMenus), LocaleController.getString(R.string.InuMenusInfo)))
        items.add(UItem.asShadow(null))

        if (avatarCornerPreview == null) {
            avatarCornerPreview = AvatarCornerPreviewCell(this.context)
        } else {
            avatarCornerPreview?.updatePreview()
        }
        items.add(UItem.asCustom(avatarCornerPreview))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_UNIFIED_CORNER_RADIUS,
                R.string.InuUnifiedCornerRadius,
                R.string.InuUnifiedCornerRadiusInfo,
                InuConfig.UNIFIED_AVATAR_RADIUS.value
            )
        )
        items.add(UItem.asShadow(null))

        if (animationSpeedSlider == null) {
            animationSpeedSlider = SliderCell(
                this.context, min = 0.5f, max = 3f,
                defaultValue = InuConfig.ANIMATION_SPEED.default,
                initialValue = if (InuConfig.ANIMATION_SPEED.value >= 3f) 3f else InuConfig.ANIMATION_SPEED.value,
                step = 0.05f,
                title = LocaleController.getString(R.string.InuAnimationSpeed),
                format = {
                    if (it >= 3f) LocaleController.getString(R.string.InuAnimationSpeedInstant)
                    else String.format("%.2fx", it)
                },
                onChanged = {
                    InuConfig.ANIMATION_SPEED.value = if (it >= 3f) 9999f else it
                    InuHooks.syncAnimationSpeed()
                },
            )
        } else {
            animationSpeedSlider?.updateColors()
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuMotion)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_SCRIM_BLUR,
                R.string.InuDisableScrimBlur,
                R.string.InuDisableScrimBlurInfo,
                InuConfig.DISABLE_SCRIM_BLUR.value
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_REDUCE_MENU_MOTION,
                R.string.InuReduceMenuMotion,
                R.string.InuReduceMenuMotionInfo,
                InuConfig.REDUCE_MENU_MOTION.value
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_SIMPLE_ATTACH_POPUP_ANIMATION,
                LocaleController.getString(R.string.InuSimpleAttachPopupAnimation),
            ).setChecked(InuConfig.SIMPLE_ATTACH_POPUP_ANIMATION.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_OPTIMIZED_ATTACHMENT_MENU,
                R.string.InuOptimizedAttachmentMenu,
                R.string.InuOptimizedAttachmentMenuInfo,
                InuConfig.OPTIMIZED_ATTACHMENT_MENU.value,
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            items.add(
                UItem.asButton(
                    BUTTON_PREDICTIVE_BACK_MODE,
                    LocaleController.getString(R.string.InuPredictiveBack),
                    predictiveBackModeLabel(InuConfig.PREDICTIVE_BACK_MODE.value),
                )
            )
        }
        items.add(UItem.asCustom(animationSpeedSlider))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAnimationSpeedInfo)))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuChatBackgroundSection)))
        items.add(UItem.asCheck(TOGGLE_DISABLE_CHAT_BACKGROUNDS, LocaleController.getString(R.string.InuDisableChatBackgrounds)).setChecked(InuConfig.DISABLE_CHAT_BACKGROUNDS.value))
        items.add(UItem.asCheck(TOGGLE_DISABLE_CHAT_THEMES, LocaleController.getString(R.string.InuDisableChatThemes)).setChecked(InuConfig.DISABLE_CHAT_THEMES.value))
        items.add(UItem.asCheck(TOGGLE_DISABLE_BG_PARALLAX, LocaleController.getString(R.string.InuDisableBgParallax)).setChecked(InuConfig.DISABLE_BG_PARALLAX.value))
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (nonIslandGroup.handleClick(item, view) { changed -> applyNonIslandChanges(listOfNotNull(changed?.id)) }) return

        when (item.id) {

            TOGGLE_UNIFIED_CORNER_RADIUS -> {
                val new = InuConfig.UNIFIED_AVATAR_RADIUS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                avatarCornerPreview?.updatePreview()
            }

            BUTTON_ICON_REPLACEMENT -> presentFragment(IconPacksSettingsActivity())

            BUTTON_FONTS -> presentFragment(FontsSettingsActivity())

            TOGGLE_DISABLE_SCRIM_BLUR -> {
                val new = InuConfig.DISABLE_SCRIM_BLUR.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_REDUCE_MENU_MOTION -> {
                val new = InuConfig.REDUCE_MENU_MOTION.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_CHAT_BACKGROUNDS -> (view as? TextCheckCell)?.isChecked = InuConfig.DISABLE_CHAT_BACKGROUNDS.toggle()
            TOGGLE_DISABLE_CHAT_THEMES -> (view as? TextCheckCell)?.isChecked = InuConfig.DISABLE_CHAT_THEMES.toggle()
            TOGGLE_DISABLE_BG_PARALLAX -> (view as? TextCheckCell)?.isChecked = InuConfig.DISABLE_BG_PARALLAX.toggle()
            TOGGLE_SIMPLE_ATTACH_POPUP_ANIMATION -> (view as? TextCheckCell)?.isChecked = InuConfig.SIMPLE_ATTACH_POPUP_ANIMATION.toggle()
            TOGGLE_OPTIMIZED_ATTACHMENT_MENU -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.OPTIMIZED_ATTACHMENT_MENU.toggle()

            BUTTON_PREDICTIVE_BACK_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuPredictiveBackOff),
                    LocaleController.getString(R.string.InuPredictiveBackStock),
                    LocaleController.getString(R.string.InuPredictiveBackMaterial3),
                ),
                InuConfig.PREDICTIVE_BACK_MODE.value,
            ) { which ->
                if (InuConfig.PREDICTIVE_BACK_MODE.value == which) return@show
                InuConfig.PREDICTIVE_BACK_MODE.value = which
                showRestartBulletin()
            }

            BUTTON_IOS_STYLE -> presentFragment(IosStyleSettingsActivity())
            BUTTON_MATERIAL3 -> presentFragment(Material3SettingsActivity())
            BUTTON_MESSAGE_DESIGN -> presentFragment(MessageDesignSettingsActivity())
            BUTTON_SIDE_MENU -> presentFragment(DrawerSettingsActivity())
            BUTTON_MENUS -> presentFragment(MenusSettingsActivity())

        }
    }

    companion object {
        private val SECTION_NON_ISLAND = InuUtils.generateId()
        private val NON_ISLAND_INFO = InuUtils.generateId()
        private val TOGGLE_HIDE_FADE_VIEW = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_FOLDERS_BAR = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_SHARED_MEDIA_TABS = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_GLOBAL_SEARCH = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_CHAT_ELEMENTS = InuUtils.generateId()
        private val BUTTON_FONTS = InuUtils.generateId()
        private val TOGGLE_DISABLE_SCRIM_BLUR = InuUtils.generateId()
        private val TOGGLE_DISABLE_PROFILE_AVATAR_BLUR = InuUtils.generateId()
        private val TOGGLE_REDUCE_MENU_MOTION = InuUtils.generateId()
        private val TOGGLE_UNIFIED_CORNER_RADIUS = InuUtils.generateId()
        private val BUTTON_ICON_REPLACEMENT = InuUtils.generateId()
        private val BUTTON_PREDICTIVE_BACK_MODE = InuUtils.generateId()
        private val BUTTON_IOS_STYLE = InuUtils.generateId()
        private val BUTTON_MATERIAL3 = InuUtils.generateId()
        private val TOGGLE_SIMPLE_ATTACH_POPUP_ANIMATION = InuUtils.generateId()
        private val TOGGLE_OPTIMIZED_ATTACHMENT_MENU = InuUtils.generateId()
        private val TOGGLE_DISABLE_CHAT_BACKGROUNDS = InuUtils.generateId()
        private val TOGGLE_DISABLE_CHAT_THEMES = InuUtils.generateId()
        private val TOGGLE_DISABLE_BG_PARALLAX = InuUtils.generateId()
        private val BUTTON_MESSAGE_DESIGN = InuUtils.generateId()
        private val BUTTON_SIDE_MENU = InuUtils.generateId()
        private val BUTTON_MENUS = InuUtils.generateId()

        private fun predictiveBackModeLabel(value: Int): String = when (value) {
            InuConfig.PredictiveBackModeItem.OFF -> LocaleController.getString(R.string.InuPredictiveBackOff)
            InuConfig.PredictiveBackModeItem.STOCK -> LocaleController.getString(R.string.InuPredictiveBackStock)
            else -> LocaleController.getString(R.string.InuPredictiveBackMaterial3)
        }

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "appearance",
            titleRes = R.string.InuCategoryAppearance,
            iconRes = R.drawable.msg_settings_old,
            factory = ::AppearanceSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("disable-scrim-blur", R.string.InuDisableScrimBlur, TOGGLE_DISABLE_SCRIM_BLUR),
                SearchRegistry.Entry("disable-profile-avatar-blur", R.string.InuDisableProfileAvatarBlur, TOGGLE_DISABLE_PROFILE_AVATAR_BLUR),
                SearchRegistry.Entry("reduce-menu-motion", R.string.InuReduceMenuMotion, TOGGLE_REDUCE_MENU_MOTION),
                SearchRegistry.Entry("unified-corner-radius", R.string.InuUnifiedCornerRadius, TOGGLE_UNIFIED_CORNER_RADIUS),
                SearchRegistry.Entry("icon-replacement", R.string.InuIconReplacement, BUTTON_ICON_REPLACEMENT),
                SearchRegistry.Entry("font", R.string.InuFonts, BUTTON_FONTS),
                SearchRegistry.Entry("predictive-back-mode", R.string.InuPredictiveBack, BUTTON_PREDICTIVE_BACK_MODE),
                SearchRegistry.Entry("non-island-folders-bar", R.string.InuNonIslandFoldersBar, TOGGLE_NON_ISLAND_FOLDERS_BAR),
                SearchRegistry.Entry("non-island-shared-media-tabs", R.string.InuNonIslandSharedMediaTabs, TOGGLE_NON_ISLAND_SHARED_MEDIA_TABS),
                SearchRegistry.Entry("non-island-global-search", R.string.InuNonIslandGlobalSearch, TOGGLE_NON_ISLAND_GLOBAL_SEARCH),
                SearchRegistry.Entry("non-island-chat-elements", R.string.InuNonIslandChatElements, TOGGLE_NON_ISLAND_CHAT_ELEMENTS),
                // entiny: preserve legacy slug so existing tg://settings/inu deeplinks and search recents still resolve
                SearchRegistry.Entry("hide-fade-view", R.string.InuHideFadeView, TOGGLE_HIDE_FADE_VIEW),
                SearchRegistry.Entry("simple-attach-popup-animation", R.string.InuSimpleAttachPopupAnimation, TOGGLE_SIMPLE_ATTACH_POPUP_ANIMATION),
                SearchRegistry.Entry("lightweight-attachment-animation", R.string.InuOptimizedAttachmentMenu, TOGGLE_OPTIMIZED_ATTACHMENT_MENU),
                SearchRegistry.Entry("disable-chat-backgrounds", R.string.InuDisableChatBackgrounds, TOGGLE_DISABLE_CHAT_BACKGROUNDS),
                SearchRegistry.Entry("disable-chat-themes", R.string.InuDisableChatThemes, TOGGLE_DISABLE_CHAT_THEMES),
                SearchRegistry.Entry("disable-bg-parallax", R.string.InuDisableBgParallax, TOGGLE_DISABLE_BG_PARALLAX),
            ),
        )
    }
}
