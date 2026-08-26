package desu.inugram.ui.settings

import android.os.Build
import android.view.View
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
                ExpandableBoolGroup.Option(R.string.InuMaterial3Fabs, InuConfig.MATERIAL3_FABS, TOGGLE_MATERIAL3_FABS),
                ExpandableBoolGroup.Option(R.string.InuMaterial3Sections, InuConfig.M3_SECTIONS_STYLE, TOGGLE_M3_SECTIONS_STYLE),
                ExpandableBoolGroup.Option(R.string.InuMaterial3Avatars, InuConfig.MATERIAL3_AVATARS, TOGGLE_MATERIAL3_AVATARS),
                ExpandableBoolGroup.Option(R.string.InuMaterial3BottomTabs, InuConfig.M3_BOTTOM_TABS, TOGGLE_M3_BOTTOM_TABS),
                ExpandableBoolGroup.Option(R.string.InuMaterialProfileActions, InuConfig.MATERIAL_PROFILE_ACTIONS, TOGGLE_MATERIAL_PROFILE_ACTIONS),
                ExpandableBoolGroup.Option(R.string.InuMaterial3NavigationAnimation, InuConfig.M3_NAVIGATION_ANIMATION, TOGGLE_M3_NAVIGATION_ANIMATION),
            ),
            sectionId = SECTION_MATERIAL3,
        )
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        // Design Header
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuTypographyAndIcons)))
        items.add(mkSubPageButton(BUTTON_FONTS, LocaleController.getString(R.string.InuFonts)))
        items.add(
            UItem.asButton(
                BUTTON_ICON_REPLACEMENT,
                LocaleController.getString(R.string.InuIconReplacement),
                when (InuConfig.ICON_REPLACEMENT.value) {
                    InuConfig.IconReplacementItem.SOLAR -> LocaleController.getString(R.string.InuIconReplacementSolar)
                    InuConfig.IconReplacementItem.VKUI -> LocaleController.getString(R.string.InuIconReplacementVkui)
                    else -> LocaleController.getString(R.string.InuIconReplacementOff)
                }
            )
        )

        items.add(
            UItem.asButton(
                BUTTON_NOTIFICATION_ICON,
                LocaleController.getString(R.string.InuNotificationIcon),
                when (InuConfig.NOTIFICATION_ICON.value) {
                    InuConfig.NotificationIconItem.INUGRAM -> LocaleController.getString(R.string.InuNotificationIconInugram)
                    InuConfig.NotificationIconItem.OLD_ENTINYGRAM -> LocaleController.getString(R.string.InuNotificationIconOldEntinygram)
                    else -> LocaleController.getString(R.string.InuNotificationIconTelegram)
                }
            )
        )
        items.add(UItem.asShadow(null))

        // Material 3 (Collapsible Group)
        m3Group.addTo(items) { changed ->
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            items.add(
                UItem.asButton(
                    BUTTON_PREDICTIVE_BACK_MODE,
                    LocaleController.getString(R.string.InuPredictiveBack),
                    predictiveBackModeLabel(InuConfig.PREDICTIVE_BACK_MODE.value),
                )
            )
        }
        items.add(UItem.asShadow(null))

        // Avatar corners
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

        // Classic UI / Non-Island UI (Flat section, NOT expandable!)
        items.add(
            UItem.asHeader(addExperimentalSpan(LocaleController.getString(R.string.InuNonIslandUI)))
        )
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
        }
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
                TOGGLE_DISABLE_GLASS_GLARE,
                LocaleController.getString(R.string.InuDisableGlassGlare),
            ).setChecked(InuConfig.DISABLE_GLASS_GLARE.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_SCRIM_BLUR,
                R.string.InuDisableScrimBlur,
                R.string.InuDisableScrimBlurInfo,
                InuConfig.DISABLE_SCRIM_BLUR.value
            )
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
                TOGGLE_REDUCE_MENU_MOTION,
                R.string.InuReduceMenuMotion,
                R.string.InuReduceMenuMotionInfo,
                InuConfig.REDUCE_MENU_MOTION.value
            )
        )
        items.add(UItem.asCustom(animationSpeedSlider))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAnimationSpeedInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (m3Group.handleClick(item, view) { changed ->
            when (changed?.id) {
                TOGGLE_MATERIAL3_SWITCHES -> invalidateVisibleRows()
                TOGGLE_M3_SECTIONS_STYLE -> inu_rebuildSelf()
            }
            if (changed?.id in setOf(TOGGLE_MATERIAL3_SWITCHES, TOGGLE_MATERIAL3_FABS, TOGGLE_M3_SECTIONS_STYLE, TOGGLE_M3_BOTTOM_TABS)) {
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

            BUTTON_ICON_REPLACEMENT -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuIconReplacementOff),
                    LocaleController.getString(R.string.InuIconReplacementSolar),
                    LocaleController.getString(R.string.InuIconReplacementVkui),
                ),
                InuConfig.ICON_REPLACEMENT.value,
            ) { which ->
                InuConfig.ICON_REPLACEMENT.value = which
                showRestartBulletin()
            }

            BUTTON_NOTIFICATION_ICON -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuNotificationIconTelegram),
                    LocaleController.getString(R.string.InuNotificationIconInugram),
                    LocaleController.getString(R.string.InuNotificationIconOldEntinygram),
                ),
                InuConfig.NOTIFICATION_ICON.value,
            ) { which ->
                InuConfig.NOTIFICATION_ICON.value = which
            }

            BUTTON_FONTS -> presentFragment(FontsSettingsActivity())

            TOGGLE_DISABLE_SCRIM_BLUR -> {
                val new = InuConfig.DISABLE_SCRIM_BLUR.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_GLASS_GLARE -> {
                val new = InuConfig.DISABLE_GLASS_GLARE.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_REDUCE_MENU_MOTION -> {
                val new = InuConfig.REDUCE_MENU_MOTION.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

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
        }
    }

    companion object {
        private val SECTION_MATERIAL3 = InuUtils.generateId()
        private val TOGGLE_HIDE_FADE_VIEW = InuUtils.generateId()
        private val TOGGLE_NAVIGATION_DRAWER = InuUtils.generateId()
        private val TOGGLE_DRAWER_BACK_GESTURE = InuUtils.generateId()
        private val TOGGLE_DRAWER_M3_SECTIONS = InuUtils.generateId()
        private val TOGGLE_SHOW_DRAWER_ACCOUNTS = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_FOLDERS_BAR = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_SHARED_MEDIA_TABS = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_GLOBAL_SEARCH = InuUtils.generateId()
        private val TOGGLE_NON_ISLAND_CHAT_ELEMENTS = InuUtils.generateId()
        private val BUTTON_FONTS = InuUtils.generateId()
        private val TOGGLE_DISABLE_SCRIM_BLUR = InuUtils.generateId()
        private val TOGGLE_DISABLE_GLASS_GLARE = InuUtils.generateId()
        private val TOGGLE_REDUCE_MENU_MOTION = InuUtils.generateId()
        private val TOGGLE_MATERIAL3_SWITCHES = InuUtils.generateId()
        private val TOGGLE_MATERIAL3_FABS = InuUtils.generateId()
        private val TOGGLE_M3_SECTIONS_STYLE = InuUtils.generateId()
        private val TOGGLE_MATERIAL3_AVATARS = InuUtils.generateId()
        private val TOGGLE_M3_BOTTOM_TABS = InuUtils.generateId()
        private val TOGGLE_MATERIAL_PROFILE_ACTIONS = InuUtils.generateId()
        private val TOGGLE_M3_NAVIGATION_ANIMATION = InuUtils.generateId()
        private val TOGGLE_UNIFIED_CORNER_RADIUS = InuUtils.generateId()
        private val BUTTON_ICON_REPLACEMENT = InuUtils.generateId()
        private val BUTTON_NOTIFICATION_ICON = InuUtils.generateId()
        private val BUTTON_PREDICTIVE_BACK_MODE = InuUtils.generateId()
        private val BUTTON_MONET_THEME = InuUtils.generateId()

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
                SearchRegistry.Entry("disable-glass-glare", R.string.InuDisableGlassGlare, TOGGLE_DISABLE_GLASS_GLARE),
                SearchRegistry.Entry("reduce-menu-motion", R.string.InuReduceMenuMotion, TOGGLE_REDUCE_MENU_MOTION),
                SearchRegistry.Entry("material3-switches", R.string.InuMaterial3Switches, TOGGLE_MATERIAL3_SWITCHES),
                SearchRegistry.Entry("material3-fabs", R.string.InuMaterial3Fabs, TOGGLE_MATERIAL3_FABS),
                SearchRegistry.Entry("material3-sections", R.string.InuMaterial3Sections, TOGGLE_M3_SECTIONS_STYLE),
                SearchRegistry.Entry("material3-avatars", R.string.InuMaterial3Avatars, TOGGLE_MATERIAL3_AVATARS),
                SearchRegistry.Entry("m3-bottom-tabs", R.string.InuMaterial3BottomTabs, TOGGLE_M3_BOTTOM_TABS),
                SearchRegistry.Entry("material-profile-actions", R.string.InuMaterialProfileActions, TOGGLE_MATERIAL_PROFILE_ACTIONS),
                SearchRegistry.Entry("material3-navigation-animation", R.string.InuMaterial3NavigationAnimation, TOGGLE_M3_NAVIGATION_ANIMATION),
                SearchRegistry.Entry("unified-corner-radius", R.string.InuUnifiedCornerRadius, TOGGLE_UNIFIED_CORNER_RADIUS),
                SearchRegistry.Entry("monet-theme", R.string.InuMonetTheme, BUTTON_MONET_THEME),
                SearchRegistry.Entry("icon-replacement", R.string.InuIconReplacement, BUTTON_ICON_REPLACEMENT),
                SearchRegistry.Entry("notification-icon", R.string.InuNotificationIcon, BUTTON_NOTIFICATION_ICON),
                SearchRegistry.Entry("font", R.string.InuFonts, BUTTON_FONTS),
                SearchRegistry.Entry("predictive-back-mode", R.string.InuPredictiveBack, BUTTON_PREDICTIVE_BACK_MODE),
                SearchRegistry.Entry("navigation-drawer", R.string.InuNavigationDrawer, TOGGLE_NAVIGATION_DRAWER),
                SearchRegistry.Entry("drawer-back-gesture", R.string.InuDrawerBackGesture, TOGGLE_DRAWER_BACK_GESTURE),
                SearchRegistry.Entry("drawer-m3-sections", R.string.InuDrawerM3Sections, TOGGLE_DRAWER_M3_SECTIONS),
                SearchRegistry.Entry("show-drawer-accounts", R.string.InuShowDrawerAccounts, TOGGLE_SHOW_DRAWER_ACCOUNTS),
                SearchRegistry.Entry("non-island-folders-bar", R.string.InuNonIslandFoldersBar, TOGGLE_NON_ISLAND_FOLDERS_BAR),
                SearchRegistry.Entry("non-island-shared-media-tabs", R.string.InuNonIslandSharedMediaTabs, TOGGLE_NON_ISLAND_SHARED_MEDIA_TABS),
                SearchRegistry.Entry("non-island-global-search", R.string.InuNonIslandGlobalSearch, TOGGLE_NON_ISLAND_GLOBAL_SEARCH),
                SearchRegistry.Entry("non-island-chat-elements", R.string.InuNonIslandChatElements, TOGGLE_NON_ISLAND_CHAT_ELEMENTS),
                SearchRegistry.Entry("hide-fade-view", R.string.InuHideFadeView, TOGGLE_HIDE_FADE_VIEW),
            ),
        )
    }
}
