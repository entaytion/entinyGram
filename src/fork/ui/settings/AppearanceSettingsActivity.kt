package desu.inugram.ui.settings

import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import desu.inugram.InuConfig
import desu.inugram.InuHooks
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.theme.MonetHelper
import desu.inugram.ui.settings.fonts.FontsSettingsActivity
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AppearanceSettingsActivity : SettingsPageActivity() {

    private var animationSpeedSlider: SliderCell? = null
    private var avatarCornerPreview: AvatarCornerPreviewCell? = null

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuCategoryAppearance)

    private val m3Group by lazy {
        ExpandableBoolGroup(
            LocaleController.getString(R.string.InuMaterial3),
            listOf(
                ExpandableBoolGroup.Option(R.string.InuMaterial3Switches, InuConfig.MATERIAL3_SWITCHES, TOGGLE_MATERIAL3_SWITCHES),
                ExpandableBoolGroup.Option(R.string.InuMaterial3Sliders, InuConfig.MATERIAL3_SLIDERS, TOGGLE_MATERIAL3_SLIDERS),
                ExpandableBoolGroup.Option(R.string.InuMaterial3Fabs, InuConfig.MATERIAL3_FABS, TOGGLE_MATERIAL3_FABS),
                ExpandableBoolGroup.Option(R.string.InuMaterial3Sections, InuConfig.M3_SECTIONS_STYLE, TOGGLE_M3_SECTIONS_STYLE),
                ExpandableBoolGroup.Option(R.string.InuMaterial3AccentIcons, InuConfig.M3_ACCENT_ICONS, TOGGLE_M3_ACCENT_ICONS),
                ExpandableBoolGroup.Option(R.string.InuMaterial3Avatars, InuConfig.MATERIAL3_AVATARS, TOGGLE_MATERIAL3_AVATARS),
                ExpandableBoolGroup.Option(R.string.InuMaterial3BottomTabs, InuConfig.M3_BOTTOM_TABS, TOGGLE_M3_BOTTOM_TABS),
                ExpandableBoolGroup.Option(R.string.InuMaterialProfileActions, InuConfig.MATERIAL_PROFILE_ACTIONS, TOGGLE_MATERIAL_PROFILE_ACTIONS),
                ExpandableBoolGroup.Option(R.string.InuMaterial3NavigationAnimation, InuConfig.M3_NAVIGATION_ANIMATION, TOGGLE_M3_NAVIGATION_ANIMATION),
                ExpandableBoolGroup.Option(R.string.InuMaterial3Player, InuConfig.MD3_PLAYER, TOGGLE_MD3_PLAYER),
            ),
            sectionId = SECTION_MATERIAL3,
        )
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
        items.add(mkTwoLineEntry(BUTTON_IOS_STYLE, R.drawable.msg_newphone, LocaleController.getString(R.string.InuIosSettings), LocaleController.getString(R.string.InuIosSettingsInfo)))
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuInterfaceElements)))
        items.add(mkTwoLineEntry(BUTTON_MESSAGE_DESIGN, R.drawable.msg_discussion, LocaleController.getString(R.string.InuMessageDesign), LocaleController.getString(R.string.InuMessageDesignInfo)))
        items.add(mkTwoLineEntry(BUTTON_SIDE_MENU, R.drawable.inu_tabler_menu_2, LocaleController.getString(R.string.InuSideMenu), LocaleController.getString(R.string.InuSideMenuInfo)))
        items.add(mkTwoLineEntry(BUTTON_MENUS, R.drawable.inu_tabler_list, LocaleController.getString(R.string.InuMenus), LocaleController.getString(R.string.InuMenusInfo)))
        items.add(UItem.asShadow(null))

        m3Group.addTo(items) { changed ->
            if (changed.any { it.id == TOGGLE_M3_BOTTOM_TABS } && InuConfig.M3_BOTTOM_TABS.value && InuConfig.IOS_BOTTOM_NAVIGATION_BAR.value) {
                InuConfig.IOS_BOTTOM_NAVIGATION_BAR.value = false
                showRestartBulletin()
            }
            // entiny: the master switch flips the sections style too, so rebuild this page like the single toggle does
            if (changed.any { it.id == TOGGLE_M3_SECTIONS_STYLE }) inu_rebuildSelf()
            invalidateVisibleRows()
            softRebuild()
            listView.adapter.update(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            items.add(
                UItem.asButton(
                    BUTTON_MONET_THEME,
                    LocaleController.getString(R.string.InuMonetTheme),
                    monetThemeModeLabel(MonetHelper.getThemeMode()),
                )
            )
        }
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

        items.add(
            UItem.asHeader(addExperimentalSpan(LocaleController.getString(R.string.InuNonIslandUI)))
        )
        items.add(
            UItem.asCheck(
                TOGGLE_NON_ISLAND_FOLDERS_BAR,
                LocaleController.getString(R.string.InuNonIslandFoldersBar),
            ).setChecked(InuConfig.NON_ISLAND_FOLDERS_BAR.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_NON_ISLAND_SHARED_MEDIA_TABS,
                LocaleController.getString(R.string.InuNonIslandSharedMediaTabs),
            ).setChecked(InuConfig.NON_ISLAND_SHARED_MEDIA_TABS.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_NON_ISLAND_GLOBAL_SEARCH,
                LocaleController.getString(R.string.InuNonIslandGlobalSearch),
            ).setChecked(InuConfig.NON_ISLAND_GLOBAL_SEARCH.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_NON_ISLAND_CHAT_ELEMENTS,
                LocaleController.getString(R.string.InuNonIslandChatElements),
            ).setChecked(InuConfig.NON_ISLAND_CHAT_ELEMENTS.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_FADE_VIEW,
                LocaleController.getString(R.string.InuHideFadeView),
            ).setChecked(InuConfig.HIDE_FADE_VIEW.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_PROFILE_AVATAR_BLUR,
                LocaleController.getString(R.string.InuDisableProfileAvatarBlur),
            ).setChecked(InuConfig.DISABLE_PROFILE_AVATAR_BLUR.value)
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuNonIslandHint)))

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
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_NEW_APP_ICON_PICKER,
                R.string.InuNewAppIconPicker,
                R.string.InuNewAppIconPickerInfo,
                InuConfig.NEW_APP_ICON_PICKER.value,
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
        if (m3Group.handleClick(item, view) { changed ->
            when (changed?.id) {
                TOGGLE_MATERIAL3_SWITCHES -> invalidateVisibleRows()
                TOGGLE_M3_SECTIONS_STYLE, TOGGLE_M3_ACCENT_ICONS -> inu_rebuildSelf()
                TOGGLE_M3_BOTTOM_TABS -> {
                    if (InuConfig.M3_BOTTOM_TABS.value && InuConfig.IOS_BOTTOM_NAVIGATION_BAR.value) {
                        InuConfig.IOS_BOTTOM_NAVIGATION_BAR.value = false
                        invalidateVisibleRows()
                        showRestartBulletin()
                    }
                }
            }
            if (changed?.id in setOf(TOGGLE_MATERIAL3_SWITCHES, TOGGLE_MATERIAL3_FABS, TOGGLE_M3_SECTIONS_STYLE, TOGGLE_M3_ACCENT_ICONS, TOGGLE_M3_BOTTOM_TABS)) {
                softRebuild()
            }
            listView.adapter.update(true)
        }) return

        when (item.id) {

            TOGGLE_HIDE_FADE_VIEW -> {
                val new = InuConfig.HIDE_FADE_VIEW.toggle()
                (view as? TextCheckCell)?.isChecked = new
                softRebuild()
            }

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

            TOGGLE_DISABLE_PROFILE_AVATAR_BLUR -> {
                val new = InuConfig.DISABLE_PROFILE_AVATAR_BLUR.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_REDUCE_MENU_MOTION -> {
                val new = InuConfig.REDUCE_MENU_MOTION.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_NON_ISLAND_FOLDERS_BAR -> {
                val new = InuConfig.NON_ISLAND_FOLDERS_BAR.toggle()
                (view as? TextCheckCell)?.isChecked = new
                softRebuild()
            }

            TOGGLE_NON_ISLAND_SHARED_MEDIA_TABS -> {
                val new = InuConfig.NON_ISLAND_SHARED_MEDIA_TABS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_NON_ISLAND_GLOBAL_SEARCH -> {
                val new = InuConfig.NON_ISLAND_GLOBAL_SEARCH.toggle()
                (view as? TextCheckCell)?.isChecked = new
                softRebuild()
            }

            TOGGLE_NON_ISLAND_CHAT_ELEMENTS -> {
                val new = InuConfig.NON_ISLAND_CHAT_ELEMENTS.toggle()
                (view as? TextCheckCell)?.isChecked = new
                InuHooks.syncChatInputRowHeight()
            }

            BUTTON_MONET_THEME -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val context = context ?: return
                showDialog(
                    RadioDialogBuilder(context, getResourceProvider())
                        .setTitle(LocaleController.getString(R.string.InuMonetTheme))
                        .setItems(
                            arrayOf(
                                LocaleController.getString(R.string.InuMonetThemeDisabled),
                                LocaleController.getString(R.string.InuMonetThemeLight),
                                LocaleController.getString(R.string.InuMonetThemeDark),
                                LocaleController.getString(R.string.InuMonetThemeAmoled),
                                LocaleController.getString(R.string.InuMonetThemeAuto),
                                LocaleController.getString(R.string.InuMonetThemeAutoAmoled),
                            ),
                            MonetHelper.getThemeMode().ordinal,
                        ) { _, which ->
                            MonetHelper.setThemeMode(MonetHelper.ThemeMode.entries[which])
                            listView.adapter.update(true)
                        }.create()
                )
            }

            TOGGLE_DISABLE_CHAT_BACKGROUNDS -> (view as? TextCheckCell)?.isChecked = InuConfig.DISABLE_CHAT_BACKGROUNDS.toggle()
            TOGGLE_DISABLE_CHAT_THEMES -> (view as? TextCheckCell)?.isChecked = InuConfig.DISABLE_CHAT_THEMES.toggle()
            TOGGLE_DISABLE_BG_PARALLAX -> (view as? TextCheckCell)?.isChecked = InuConfig.DISABLE_BG_PARALLAX.toggle()
            TOGGLE_SIMPLE_ATTACH_POPUP_ANIMATION -> (view as? TextCheckCell)?.isChecked = InuConfig.SIMPLE_ATTACH_POPUP_ANIMATION.toggle()
            TOGGLE_OPTIMIZED_ATTACHMENT_MENU -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.OPTIMIZED_ATTACHMENT_MENU.toggle()
            TOGGLE_NEW_APP_ICON_PICKER -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.NEW_APP_ICON_PICKER.toggle()

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
            BUTTON_MESSAGE_DESIGN -> presentFragment(MessageDesignSettingsActivity())
            BUTTON_SIDE_MENU -> presentFragment(DrawerSettingsActivity())
            BUTTON_MENUS -> presentFragment(MenusSettingsActivity())

        }
    }

    companion object {
        private val SECTION_MATERIAL3 = InuUtils.generateId()
        private val TOGGLE_HIDE_FADE_VIEW = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_FOLDERS_BAR = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_SHARED_MEDIA_TABS = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_GLOBAL_SEARCH = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_CHAT_ELEMENTS = InuUtils.generateId()
        private val BUTTON_FONTS = InuUtils.generateId()
        private val TOGGLE_DISABLE_SCRIM_BLUR = InuUtils.generateId()
        private val TOGGLE_DISABLE_PROFILE_AVATAR_BLUR = InuUtils.generateId()
        private val TOGGLE_REDUCE_MENU_MOTION = InuUtils.generateId()
        private val TOGGLE_MATERIAL3_SWITCHES = InuUtils.generateId()
        private val TOGGLE_MATERIAL3_SLIDERS = InuUtils.generateId()
        private val TOGGLE_MATERIAL3_FABS = InuUtils.generateId()
        private val TOGGLE_M3_SECTIONS_STYLE = InuUtils.generateId()
        private val TOGGLE_M3_ACCENT_ICONS = InuUtils.generateId()
        private val TOGGLE_MATERIAL3_AVATARS = InuUtils.generateId()
        private val TOGGLE_M3_BOTTOM_TABS = InuUtils.generateId()
        private val TOGGLE_MATERIAL_PROFILE_ACTIONS = InuUtils.generateId()
        private val TOGGLE_M3_NAVIGATION_ANIMATION = InuUtils.generateId()
        private val TOGGLE_MD3_PLAYER = InuUtils.generateId()
        private val TOGGLE_UNIFIED_CORNER_RADIUS = InuUtils.generateId()
        private val BUTTON_ICON_REPLACEMENT = InuUtils.generateId()
        private val BUTTON_PREDICTIVE_BACK_MODE = InuUtils.generateId()
        private val BUTTON_MONET_THEME = InuUtils.generateId()
        private val BUTTON_IOS_STYLE = InuUtils.generateId()
        private val TOGGLE_SIMPLE_ATTACH_POPUP_ANIMATION = InuUtils.generateId()
        private val TOGGLE_OPTIMIZED_ATTACHMENT_MENU = InuUtils.generateId()
        private val TOGGLE_NEW_APP_ICON_PICKER = InuUtils.generateId()
        private val TOGGLE_DISABLE_CHAT_BACKGROUNDS = InuUtils.generateId()
        private val TOGGLE_DISABLE_CHAT_THEMES = InuUtils.generateId()
        private val TOGGLE_DISABLE_BG_PARALLAX = InuUtils.generateId()
        private val BUTTON_MESSAGE_DESIGN = InuUtils.generateId()
        private val BUTTON_SIDE_MENU = InuUtils.generateId()
        private val BUTTON_MENUS = InuUtils.generateId()

        @RequiresApi(Build.VERSION_CODES.S)
        private fun monetThemeModeLabel(mode: MonetHelper.ThemeMode): String = when (mode) {
            MonetHelper.ThemeMode.LIGHT -> LocaleController.getString(R.string.InuMonetThemeLight)
            MonetHelper.ThemeMode.DARK -> LocaleController.getString(R.string.InuMonetThemeDark)
            MonetHelper.ThemeMode.AMOLED -> LocaleController.getString(R.string.InuMonetThemeAmoled)
            MonetHelper.ThemeMode.AUTO -> LocaleController.getString(R.string.InuMonetThemeAuto)
            MonetHelper.ThemeMode.AUTO_AMOLED -> LocaleController.getString(R.string.InuMonetThemeAutoAmoled)
            else -> LocaleController.getString(R.string.InuMonetThemeDisabled)
        }

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
                SearchRegistry.Entry("material3-switches", R.string.InuMaterial3Switches, TOGGLE_MATERIAL3_SWITCHES),
                SearchRegistry.Entry("material3-sliders", R.string.InuMaterial3Sliders, TOGGLE_MATERIAL3_SLIDERS),
                SearchRegistry.Entry("material3-fabs", R.string.InuMaterial3Fabs, TOGGLE_MATERIAL3_FABS),
                SearchRegistry.Entry("material3-sections", R.string.InuMaterial3Sections, TOGGLE_M3_SECTIONS_STYLE),
                SearchRegistry.Entry("material3-avatars", R.string.InuMaterial3Avatars, TOGGLE_MATERIAL3_AVATARS),
                SearchRegistry.Entry("m3-bottom-tabs", R.string.InuMaterial3BottomTabs, TOGGLE_M3_BOTTOM_TABS),
                SearchRegistry.Entry("material-profile-actions", R.string.InuMaterialProfileActions, TOGGLE_MATERIAL_PROFILE_ACTIONS),
                SearchRegistry.Entry("material3-navigation-animation", R.string.InuMaterial3NavigationAnimation, TOGGLE_M3_NAVIGATION_ANIMATION),
                SearchRegistry.Entry("unified-corner-radius", R.string.InuUnifiedCornerRadius, TOGGLE_UNIFIED_CORNER_RADIUS),
                SearchRegistry.Entry("monet-theme", R.string.InuMonetTheme, BUTTON_MONET_THEME),
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
                SearchRegistry.Entry("new-app-icon-picker", R.string.InuNewAppIconPicker, TOGGLE_NEW_APP_ICON_PICKER),
                SearchRegistry.Entry("disable-chat-backgrounds", R.string.InuDisableChatBackgrounds, TOGGLE_DISABLE_CHAT_BACKGROUNDS),
                SearchRegistry.Entry("disable-chat-themes", R.string.InuDisableChatThemes, TOGGLE_DISABLE_CHAT_THEMES),
                SearchRegistry.Entry("disable-bg-parallax", R.string.InuDisableBgParallax, TOGGLE_DISABLE_BG_PARALLAX),
            ),
        )
    }
}
