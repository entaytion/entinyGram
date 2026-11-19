package desu.inugram.ui.settings

import android.view.View
import androidx.core.content.edit
import desu.inugram.InuConfig
import desu.inugram.InuHooks
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.chat.ActionButtonStyle
import desu.inugram.helpers.theme.GlassGlareHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class IosStyleSettingsActivity : SettingsPageActivity() {
    private val glassSliders = mutableMapOf<InuConfig.IntItem, SliderCell>()
    private var glassPreview: GlassPreviewCell? = null

    private var inputBarPreviewCell: InputBarPreviewCell? = null

    private val iosGroup = ExpandableBoolGroup(
        LocaleController.getString(R.string.InuIosStyle),
        listOf(
            ExpandableBoolGroup.Option(R.string.InuIosChatHeader, InuConfig.IOS_CHAT_HEADER, TOGGLE_IOS_CHAT_HEADER),
            ExpandableBoolGroup.Option(R.string.InuIosBottomBar, InuConfig.IOS_BOTTOM_NAVIGATION_BAR, TOGGLE_IOS_BOTTOM_BAR),
            ExpandableBoolGroup.Option(R.string.InuIosChatList, InuConfig.IOS_CHAT_LIST, TOGGLE_IOS_CHAT_LIST),
            ExpandableBoolGroup.Option(R.string.InuIosMessageMenu, InuConfig.IOS_MESSAGE_MENU, TOGGLE_IOS_MESSAGE_MENU),
            ExpandableBoolGroup.Option(R.string.InuIosButtonPlacement, InuConfig.IOS_INPUT_BUTTON_PLACEMENT, TOGGLE_IOS_BUTTON_PLACEMENT),
            ExpandableBoolGroup.Option(R.string.InuIosInputAppearance, InuConfig.IOS_INPUT_APPEARANCE, TOGGLE_IOS_INPUT_APPEARANCE),
        ),
    )

    override val expandableGroups get() = listOf(iosGroup)

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuIosSettings)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val context = context ?: return
        iosGroup.addTo(items) { onIosOptionsChanged(it) }
        if (InuConfig.IOS_BOTTOM_NAVIGATION_BAR.value) {
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_IOS_CHATS_TAB_FIRST_FOLDER,
                    R.string.InuIosChatsTabFirstFolder,
                    R.string.InuIosChatsTabFirstFolderInfo,
                    InuConfig.IOS_CHATS_TAB_RETURNS_TO_FIRST_FOLDER.value,
                )
            )
        }
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuMessageInput)))
        if (inputBarPreviewCell == null) inputBarPreviewCell = InputBarPreviewCell(context, getResourceProvider())
        items.add(UItem.asCustom(inputBarPreviewCell))
        items.add(
            UItem.asButton(
                BUTTON_ACTION_BUTTON_STYLE,
                LocaleController.getString(R.string.InuActionButtonStyle),
                when (InuConfig.ACTION_BUTTON_STYLE.value) {
                    ActionButtonStyle.NEUTRAL -> LocaleController.getString(R.string.InuActionButtonStyleNeutral)
                    ActionButtonStyle.WHITE -> LocaleController.getString(R.string.InuActionButtonStyleWhite)
                    else -> LocaleController.getString(R.string.InuActionButtonStyleAccent)
                }
            )
        )
        if (InuConfig.IOS_INPUT_APPEARANCE.value) {
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_COMPACT_INPUT_SIZE,
                    R.string.InuCompactInputSize,
                    R.string.InuCompactInputSizeInfo,
                    InuConfig.COMPACT_INPUT_SIZE.value,
                )
            )
            if (InuConfig.IOS_INPUT_BUTTON_PLACEMENT.value) {
                items.add(
                    mkTwoLineCheckItem(
                        TOGGLE_COMMANDS_NEAR_STICKERS,
                        R.string.InuCommandsNearStickers,
                        R.string.InuCommandsNearStickersInfo,
                        InuConfig.IOS_INPUT_COMMANDS_NEAR_STICKERS.value,
                    )
                )
            }
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuActionButtonStyleInfo)))


        items.add(UItem.asHeader(LocaleController.getString(R.string.InuLiquidGlass)))
        if (glassPreview?.context !== context) {
            glassPreview = GlassPreviewCell(context, getResourceProvider())
            glassSliders.clear()
        }
        items.add(UItem.asCustom(glassPreview))
        addGlassSlider(items, SLIDER_BOTTOM_BAR_TRANSPARENCY, R.string.InuIosBottomBarTransparency, InuConfig.IOS_BOTTOM_BAR_TRANSPARENCY, 0..InuConfig.IOS_BOTTOM_BAR_MAX_TRANSPARENCY)
        items.add(
            UItem.asButton(
                BUTTON_GLASS_GLARE,
                LocaleController.getString(R.string.InuGlassGlare),
                LocaleController.getString(GLASS_GLARE_MODES[GlassGlareHelper.mode]),
            )
        )
        addGlassSlider(items, SLIDER_GLASS_ANGLE, R.string.InuLiquidGlassAngle, InuConfig.LIQUID_GLASS_ANGLE, 0..360, "°")
        addGlassSlider(items, SLIDER_GLASS_INTENSITY, R.string.InuLiquidGlassIntensity, InuConfig.LIQUID_GLASS_INTENSITY, 0..150)
        addGlassSlider(items, SLIDER_GLASS_TINT, R.string.InuLiquidGlassTint, InuConfig.LIQUID_GLASS_TINT, 0..100)
        addGlassSlider(items, SLIDER_GLASS_BLUR, R.string.InuLiquidGlassBlur, InuConfig.LIQUID_GLASS_BLUR, 10..200)
        items.add(UItem.asButton(BUTTON_RESET_GLASS, LocaleController.getString(R.string.InuLiquidGlassReset)))
        items.add(UItem.asShadow(null))
    }

    private fun addGlassSlider(
        items: ArrayList<UItem>,
        id: Int,
        title: Int,
        setting: InuConfig.IntItem,
        range: IntRange,
        unit: String = "%",
    ) {
        val context = context ?: return
        val slider = glassSliders.getOrPut(setting) {
            var changed = false
            SliderCell(
                context, min = range.first.toFloat(), max = range.last.toFloat(),
                defaultValue = setting.default.toFloat(),
                initialValue = setting.value.coerceIn(range).toFloat(),
                step = 1f,
                title = LocaleController.getString(title),
                format = { "${it.toInt()}$unit" },
                onChanged = {
                    setting.value = it.toInt()
                    changed = true
                    glassPreview?.refreshGlass()
                },
                onReleased = {
                    if (changed) {
                        changed = false
                        refreshLiquidGlassEffects()
                        if (setting === InuConfig.LIQUID_GLASS_BLUR) showRestartBulletin()
                    }
                },
            )
        }
        slider.updateColors()
        items.add(UItem.asCustom(id, slider))
    }

    private fun onIosOptionsChanged(changed: List<ExpandableBoolGroup.Option>) {
        if (changed.any { it.id == TOGGLE_IOS_BOTTOM_BAR }) {
            if (InuConfig.IOS_BOTTOM_NAVIGATION_BAR.value && InuConfig.M3_BOTTOM_TABS.value) {
                InuConfig.M3_BOTTOM_TABS.value = false
            }
            softRebuild()
            showRestartBulletin()
        }
        if (changed.any { it.id == TOGGLE_IOS_CHAT_HEADER } && InuConfig.IOS_CHAT_HEADER.value) {
            InuConfig.CENTER_TITLE_CHATS.value = true
        }
        if (changed.any { it.id == TOGGLE_IOS_INPUT_APPEARANCE }) {
            if (InuConfig.IOS_INPUT_APPEARANCE.value) InuConfig.NON_ISLAND_CHAT_ELEMENTS.value = false
            InuHooks.syncChatInputRowHeight()
        }
        if (changed.any { it.id == TOGGLE_IOS_BUTTON_PLACEMENT || it.id == TOGGLE_IOS_INPUT_APPEARANCE }) {
            inputBarPreviewCell?.updateInputBarState()
        }
        listView.adapter.update(true)
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id == iosGroup.sectionId) {
            iosGroup.handleClick(item, view) { listView.adapter.update(true) }
            return
        }
        if (iosGroup.options.any { it.id == item.id }) {
            iosGroup.handleClick(item, view) { opt -> opt?.let { onIosOptionsChanged(listOf(it)) } }
            return
        }
        when (item.id) {

            TOGGLE_IOS_CHATS_TAB_FIRST_FOLDER -> {
                val new = InuConfig.IOS_CHATS_TAB_RETURNS_TO_FIRST_FOLDER.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_COMPACT_INPUT_SIZE -> {
                val new = InuConfig.COMPACT_INPUT_SIZE.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                InuHooks.syncChatInputRowHeight()
                inputBarPreviewCell?.updateInputBarState()
            }

            TOGGLE_COMMANDS_NEAR_STICKERS -> {
                val new = InuConfig.IOS_INPUT_COMMANDS_NEAR_STICKERS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                inputBarPreviewCell?.updateInputBarState()
            }

            BUTTON_GLASS_GLARE -> {
                val ctx = context ?: return
                showDialog(
                    RadioDialogBuilder(ctx, getResourceProvider())
                        .setTitle(LocaleController.getString(R.string.InuGlassGlare))
                        .setItems(
                            GLASS_GLARE_MODES.map { RadioDialogBuilder.Item(LocaleController.getString(it)) },
                            GlassGlareHelper.mode,
                        ) { _, which ->
                            if (GlassGlareHelper.mode == which) return@setItems
                            GlassGlareHelper.setMode(which)
                            inputBarPreviewCell?.invalidate()
                            glassPreview?.refreshGlass()
                            listView.adapter.update(true)
                        }.create()
                )
            }

            BUTTON_RESET_GLASS -> resetGlassSettings()

            BUTTON_ACTION_BUTTON_STYLE -> {
                val ctx = context ?: return
                val styleItems = listOf(
                    RadioDialogBuilder.Item(LocaleController.getString(R.string.InuActionButtonStyleAccent)),
                    RadioDialogBuilder.Item(LocaleController.getString(R.string.InuActionButtonStyleNeutral)),
                    RadioDialogBuilder.Item(LocaleController.getString(R.string.InuActionButtonStyleWhite)),
                )
                showDialog(
                    RadioDialogBuilder(ctx, getResourceProvider())
                        .setTitle(LocaleController.getString(R.string.InuActionButtonStyle))
                        .setItems(styleItems, InuConfig.ACTION_BUTTON_STYLE.value) { _, which ->
                            if (InuConfig.ACTION_BUTTON_STYLE.value == which) return@setItems
                            InuConfig.ACTION_BUTTON_STYLE.value = which
                            inputBarPreviewCell?.updateInputBarState()
                            listView.adapter.update(true)
                        }.create()
                )
            }
        }
    }

    private fun refreshLiquidGlassEffects() {
        GlassGlareHelper.invalidateLiquidGlass()
        inputBarPreviewCell?.invalidate()
        glassPreview?.refreshGlass()
    }

    private fun resetGlassSettings() {
        val blurChanged = InuConfig.LIQUID_GLASS_BLUR.value != InuConfig.LIQUID_GLASS_BLUR.default
        InuConfig.prefs.edit {
            for (setting in glassSliders.keys) setting.unsafeSet(setting.default, this)
            InuConfig.DISABLE_GLASS_GLARE.unsafeSet(InuConfig.DISABLE_GLASS_GLARE.default, this)
            InuConfig.GLASS_GLARE_SOLID.unsafeSet(InuConfig.GLASS_GLARE_SOLID.default, this)
        }
        for ((setting, slider) in glassSliders) {
            slider.updateValue(setting.default.toFloat())
        }
        refreshLiquidGlassEffects()
        if (blurChanged) showRestartBulletin()
    }

    companion object {
        private val BUTTON_GLASS_GLARE = InuUtils.generateId()
        private val BUTTON_RESET_GLASS = InuUtils.generateId()
        private val SLIDER_GLASS_ANGLE = InuUtils.generateId()
        private val SLIDER_GLASS_INTENSITY = InuUtils.generateId()
        private val SLIDER_GLASS_TINT = InuUtils.generateId()
        private val SLIDER_GLASS_BLUR = InuUtils.generateId()
        private val SLIDER_BOTTOM_BAR_TRANSPARENCY = InuUtils.generateId()
        private val GLASS_GLARE_MODES = intArrayOf(R.string.Disable, R.string.Default, R.string.InuGlassGlareSolid)
        private val TOGGLE_IOS_CHAT_HEADER = InuUtils.generateId()
        private val TOGGLE_IOS_BOTTOM_BAR = InuUtils.generateId()
        private val TOGGLE_IOS_CHAT_LIST = InuUtils.generateId()
        private val TOGGLE_IOS_MESSAGE_MENU = InuUtils.generateId()
        private val TOGGLE_IOS_CHATS_TAB_FIRST_FOLDER = InuUtils.generateId()
        private val TOGGLE_IOS_BUTTON_PLACEMENT = InuUtils.generateId()
        private val TOGGLE_IOS_INPUT_APPEARANCE = InuUtils.generateId()
        private val TOGGLE_COMPACT_INPUT_SIZE = InuUtils.generateId()
        private val TOGGLE_COMMANDS_NEAR_STICKERS = InuUtils.generateId()
        private val BUTTON_ACTION_BUTTON_STYLE = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "ios-style",
            titleRes = R.string.InuIosSettings,
            iconRes = R.drawable.msg_newphone,
            factory = ::IosStyleSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("disable-glass-glare", R.string.InuGlassGlare, BUTTON_GLASS_GLARE),
                SearchRegistry.Entry("liquid-glass-reset", R.string.InuLiquidGlassReset, BUTTON_RESET_GLASS),
                SearchRegistry.Entry("ios-bottom-bar-transparency", R.string.InuIosBottomBarTransparency, SLIDER_BOTTOM_BAR_TRANSPARENCY),
                // entiny: slugs kept verbatim from AppearanceSettingsActivity so old deeplinks keep resolving
                SearchRegistry.Entry("ios-bottom-bar", R.string.InuIosBottomBar, TOGGLE_IOS_BOTTOM_BAR),
                SearchRegistry.Entry("ios-chat-list", R.string.InuIosChatList, TOGGLE_IOS_CHAT_LIST),
                SearchRegistry.Entry("ios-message-menu", R.string.InuIosMessageMenu, TOGGLE_IOS_MESSAGE_MENU),
                SearchRegistry.Entry("ios-chats-tab-first-folder", R.string.InuIosChatsTabFirstFolder, TOGGLE_IOS_CHATS_TAB_FIRST_FOLDER),
                SearchRegistry.Entry("ios-button-placement", R.string.InuIosButtonPlacement, TOGGLE_IOS_BUTTON_PLACEMENT),
                SearchRegistry.Entry("ios-input-appearance", R.string.InuIosInputAppearance, TOGGLE_IOS_INPUT_APPEARANCE),
                SearchRegistry.Entry("compact-input-size", R.string.InuCompactInputSize, TOGGLE_COMPACT_INPUT_SIZE),
                SearchRegistry.Entry("commands-near-stickers", R.string.InuCommandsNearStickers, TOGGLE_COMMANDS_NEAR_STICKERS),
                SearchRegistry.Entry("action-button-style", R.string.InuActionButtonStyle, BUTTON_ACTION_BUTTON_STYLE),
            ),
        )
    }
}
