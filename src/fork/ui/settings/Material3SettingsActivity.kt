package desu.inugram.ui.settings

import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.dialogs.DialogsM3SectionsHelper
import desu.inugram.helpers.theme.MonetHelper
import desu.inugram.helpers.theme.MonetStyle
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class Material3SettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuMaterial3)

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
        ).also { it.expanded = true }
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            items.add(
                UItem.asButton(
                    BUTTON_MONET_THEME,
                    LocaleController.getString(R.string.InuMonetTheme),
                    monetThemeModeLabel(MonetHelper.getThemeMode()),
                )
            )
            items.add(
                UItem.asButton(
                    BUTTON_MONET_STYLE,
                    LocaleController.getString(R.string.InuMonetStyle),
                    monetStyleLabel(InuConfig.MONET_STYLE.value),
                )
            )
            items.add(UItem.asShadow(null))
        }

        m3Group.addTo(items) { changed ->
            if (changed.any { it.id == TOGGLE_M3_BOTTOM_TABS } && InuConfig.M3_BOTTOM_TABS.value && InuConfig.IOS_BOTTOM_NAVIGATION_BAR.value) {
                InuConfig.IOS_BOTTOM_NAVIGATION_BAR.value = false
                showRestartBulletin()
            }
            if (changed.any { it.id == TOGGLE_M3_SECTIONS_STYLE }) inu_rebuildSelf()
            invalidateVisibleRows()
            softRebuild()
            listView.adapter.update(true)
        }
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuDialogsSections)))
        items.add(
            UItem.asCheck(
                TOGGLE_DIALOGS_SECTIONS,
                LocaleController.getString(R.string.InuDialogsSectionsCards),
            ).setChecked(InuConfig.DIALOGS_M3_SECTIONS.value)
        )
        if (InuConfig.DIALOGS_M3_SECTIONS.value) {
            items.add(
                UItem.asButton(
                    BUTTON_DIALOGS_MARGIN,
                    LocaleController.getString(R.string.InuDialogsSectionsMargin),
                    LocaleController.getString(
                        when (InuConfig.DIALOGS_M3_MARGIN.value) {
                            0 -> R.string.InuDialogsSectionsMarginNone
                            2 -> R.string.InuDialogsSectionsMarginLarge
                            else -> R.string.InuDialogsSectionsMarginMedium
                        }
                    ),
                )
            )
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_DIALOGS_SEPARATE_PINNED,
                    R.string.InuDialogsSectionsSeparatePinned,
                    R.string.InuDialogsSectionsSeparatePinnedInfo,
                    InuConfig.DIALOGS_M3_SEPARATE_PINNED.value
                )
            )
        }
        items.add(
            UItem.asCheck(
                TOGGLE_FORUM_SECTIONS,
                LocaleController.getString(R.string.InuForumSectionsCards),
            ).setChecked(InuConfig.FORUM_M3_SECTIONS.value)
        )
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
            if (changed?.id in setOf(TOGGLE_MATERIAL3_SWITCHES, TOGGLE_MATERIAL3_FABS, TOGGLE_M3_SECTIONS_STYLE, TOGGLE_M3_ACCENT_ICONS, TOGGLE_M3_BOTTOM_TABS, TOGGLE_MATERIAL_PROFILE_ACTIONS)) {
                softRebuild()
            }
            listView.adapter.update(true)
        }) return

        when (item.id) {
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

            BUTTON_MONET_STYLE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RadioItemOptions.show(
                    this, view,
                    listOf(
                        LocaleController.getString(R.string.InuMonetStyleClassic),
                        LocaleController.getString(R.string.InuMonetStyleMaterial3),
                    ),
                    InuConfig.MONET_STYLE.value,
                ) { which ->
                    InuConfig.MONET_STYLE.value = which
                    MonetHelper.onStyleChanged()
                    listView.adapter.update(true)
                }
            }

            TOGGLE_DIALOGS_SECTIONS -> {
                val new = InuConfig.DIALOGS_M3_SECTIONS.toggle()
                (view as? TextCheckCell)?.isChecked = new
                DialogsM3SectionsHelper.refresh()
                softRebuild()
            }

            BUTTON_DIALOGS_MARGIN -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuDialogsSectionsMarginNone),
                    LocaleController.getString(R.string.InuDialogsSectionsMarginMedium),
                    LocaleController.getString(R.string.InuDialogsSectionsMarginLarge),
                ),
                InuConfig.DIALOGS_M3_MARGIN.value,
            ) { which ->
                InuConfig.DIALOGS_M3_MARGIN.value = which
                DialogsM3SectionsHelper.refresh()
                softRebuild()
            }

            TOGGLE_FORUM_SECTIONS -> (view as? TextCheckCell)?.isChecked = InuConfig.FORUM_M3_SECTIONS.toggle()

            TOGGLE_DIALOGS_SEPARATE_PINNED -> {
                val new = InuConfig.DIALOGS_M3_SEPARATE_PINNED.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                DialogsM3SectionsHelper.refresh()
            }
        }
    }

    companion object {
        private val BUTTON_MONET_THEME = InuUtils.generateId()
        private val BUTTON_MONET_STYLE = InuUtils.generateId()
        private val SECTION_MATERIAL3 = InuUtils.generateId()
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
        private val TOGGLE_DIALOGS_SECTIONS = InuUtils.generateId()
        private val BUTTON_DIALOGS_MARGIN = InuUtils.generateId()
        private val TOGGLE_DIALOGS_SEPARATE_PINNED = InuUtils.generateId()
        private val TOGGLE_FORUM_SECTIONS = InuUtils.generateId()

        @RequiresApi(Build.VERSION_CODES.S)
        private fun monetThemeModeLabel(mode: MonetHelper.ThemeMode): String = when (mode) {
            MonetHelper.ThemeMode.LIGHT -> LocaleController.getString(R.string.InuMonetThemeLight)
            MonetHelper.ThemeMode.DARK -> LocaleController.getString(R.string.InuMonetThemeDark)
            MonetHelper.ThemeMode.AMOLED -> LocaleController.getString(R.string.InuMonetThemeAmoled)
            MonetHelper.ThemeMode.AUTO -> LocaleController.getString(R.string.InuMonetThemeAuto)
            MonetHelper.ThemeMode.AUTO_AMOLED -> LocaleController.getString(R.string.InuMonetThemeAutoAmoled)
            else -> LocaleController.getString(R.string.InuMonetThemeDisabled)
        }

        private fun monetStyleLabel(style: Int): String = LocaleController.getString(
            if (style == MonetStyle.MATERIAL3) R.string.InuMonetStyleMaterial3 else R.string.InuMonetStyleClassic
        )

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "material3",
            titleRes = R.string.InuMaterial3,
            iconRes = R.drawable.inu_tabler_layout_cards,
            factory = ::Material3SettingsActivity,
            entries = listOf(
                // entiny: slugs kept verbatim from AppearanceSettingsActivity so old deeplinks keep resolving
                SearchRegistry.Entry("monet-theme", R.string.InuMonetTheme, BUTTON_MONET_THEME),
                SearchRegistry.Entry("monet-style", R.string.InuMonetStyle, BUTTON_MONET_STYLE),
                SearchRegistry.Entry("material3-switches", R.string.InuMaterial3Switches, TOGGLE_MATERIAL3_SWITCHES),
                SearchRegistry.Entry("material3-sliders", R.string.InuMaterial3Sliders, TOGGLE_MATERIAL3_SLIDERS),
                SearchRegistry.Entry("material3-fabs", R.string.InuMaterial3Fabs, TOGGLE_MATERIAL3_FABS),
                SearchRegistry.Entry("material3-sections", R.string.InuMaterial3Sections, TOGGLE_M3_SECTIONS_STYLE),
                SearchRegistry.Entry("material3-avatars", R.string.InuMaterial3Avatars, TOGGLE_MATERIAL3_AVATARS),
                SearchRegistry.Entry("m3-bottom-tabs", R.string.InuMaterial3BottomTabs, TOGGLE_M3_BOTTOM_TABS),
                SearchRegistry.Entry("material-profile-actions", R.string.InuMaterialProfileActions, TOGGLE_MATERIAL_PROFILE_ACTIONS),
                SearchRegistry.Entry("material3-navigation-animation", R.string.InuMaterial3NavigationAnimation, TOGGLE_M3_NAVIGATION_ANIMATION),
                SearchRegistry.Entry("dialogs-m3-sections", R.string.InuDialogsSectionsCards, TOGGLE_DIALOGS_SECTIONS),
                SearchRegistry.Entry("forum-m3-sections", R.string.InuForumSectionsCards, TOGGLE_FORUM_SECTIONS),
            ),
        )
    }
}
