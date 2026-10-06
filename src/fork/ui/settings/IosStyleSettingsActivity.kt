package desu.inugram.ui.settings

import android.os.Build
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.chat.ActionButtonStyle
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawableRenderNode

class IosStyleSettingsActivity : SettingsPageActivity() {
    private var angleSlider: SliderCell? = null
    private var intensitySlider: SliderCell? = null
    private var tintSlider: SliderCell? = null

    private var inputBarPreviewCell: InputBarPreviewCell? = null

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuIosSettings)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val context = context ?: return
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuIosStyle)))
        items.add(
            mkSubPageButton(
                BUTTON_CHAT_HEADER,
                R.drawable.inu_tabler_app_window,
                LocaleController.getString(R.string.InuChatHeaderSettings),
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_IOS_BOTTOM_BAR,
                R.string.InuIosBottomBar,
                R.string.InuIosBottomBarInfo,
                InuConfig.IOS_BOTTOM_NAVIGATION_BAR.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_IOS_CHATS_TAB_FIRST_FOLDER,
                R.string.InuIosChatsTabFirstFolder,
                R.string.InuIosChatsTabFirstFolderInfo,
                InuConfig.IOS_CHATS_TAB_RETURNS_TO_FIRST_FOLDER.value,
            )
        )
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
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_IOS_BUTTON_PLACEMENT,
                R.string.InuIosButtonPlacement,
                R.string.InuIosButtonPlacementInfo,
                InuConfig.IOS_INPUT_BUTTON_PLACEMENT.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_IOS_INPUT_APPEARANCE,
                R.string.InuIosInputAppearance,
                R.string.InuIosInputAppearanceInfo,
                InuConfig.IOS_INPUT_APPEARANCE.value,
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
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuActionButtonStyleInfo)))


        items.add(UItem.asHeader(LocaleController.getString(R.string.InuLiquidGlass)))
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_GLASS_GLARE,
                LocaleController.getString(R.string.InuDisableGlassGlare),
            ).setChecked(InuConfig.DISABLE_GLASS_GLARE.value)
        )
        if (angleSlider == null) {
            angleSlider = SliderCell(
                context, min = 0f, max = 360f,
                defaultValue = InuConfig.LIQUID_GLASS_ANGLE.default.toFloat(),
                initialValue = InuConfig.LIQUID_GLASS_ANGLE.value.toFloat(),
                step = 1f,
                title = LocaleController.getString(R.string.InuLiquidGlassAngle),
                format = { "${it.toInt()}°" },
                onChanged = {
                    InuConfig.LIQUID_GLASS_ANGLE.value = it.toInt()
                    refreshLiquidGlassEffects()
                },
            )
        } else {
            angleSlider?.updateColors()
        }
        items.add(UItem.asCustom(angleSlider))
        if (intensitySlider == null) {
            intensitySlider = SliderCell(
                context, min = 0f, max = 150f,
                defaultValue = InuConfig.LIQUID_GLASS_INTENSITY.default.toFloat(),
                initialValue = InuConfig.LIQUID_GLASS_INTENSITY.value.toFloat(),
                step = 1f,
                title = LocaleController.getString(R.string.InuLiquidGlassIntensity),
                format = { "${it.toInt()}%" },
                onChanged = {
                    InuConfig.LIQUID_GLASS_INTENSITY.value = it.toInt()
                    refreshLiquidGlassEffects()
                },
            )
        } else {
            intensitySlider?.updateColors()
        }
        items.add(UItem.asCustom(intensitySlider))
        if (tintSlider == null) {
            tintSlider = SliderCell(
                context, min = 0f, max = 100f,
                defaultValue = InuConfig.LIQUID_GLASS_TINT.default.toFloat(),
                initialValue = InuConfig.LIQUID_GLASS_TINT.value.toFloat(),
                step = 1f,
                title = LocaleController.getString(R.string.InuLiquidGlassTint),
                format = { "${it.toInt()}%" },
                onChanged = {
                    InuConfig.LIQUID_GLASS_TINT.value = it.toInt()
                    refreshLiquidGlassEffects()
                },
            )
        } else {
            tintSlider?.updateColors()
        }
        items.add(UItem.asCustom(tintSlider))
        items.add(UItem.asShadow(null))    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_IOS_BOTTOM_BAR -> {
                val new = InuConfig.IOS_BOTTOM_NAVIGATION_BAR.toggle()
                if (new && InuConfig.M3_BOTTOM_TABS.value) {
                    InuConfig.M3_BOTTOM_TABS.value = false
                }
                (view as? NotificationsCheckCell)?.isChecked = new
                softRebuild()
                listView.adapter.update(true)
                showRestartBulletin()
            }

            TOGGLE_IOS_CHATS_TAB_FIRST_FOLDER -> {
                val new = InuConfig.IOS_CHATS_TAB_RETURNS_TO_FIRST_FOLDER.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_IOS_BUTTON_PLACEMENT -> {
                val new = InuConfig.IOS_INPUT_BUTTON_PLACEMENT.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                inputBarPreviewCell?.updateInputBarState()
            }

            TOGGLE_IOS_INPUT_APPEARANCE -> {
                InuConfig.IOS_INPUT_APPEARANCE.toggle()
                inputBarPreviewCell?.updateInputBarState()
                listView.adapter.update(true)
            }

            TOGGLE_COMPACT_INPUT_SIZE -> {
                val new = InuConfig.COMPACT_INPUT_SIZE.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                inputBarPreviewCell?.updateInputBarState()
            }

            BUTTON_CHAT_HEADER -> presentFragment(ChatHeaderSettingsActivity())

            TOGGLE_DISABLE_GLASS_GLARE -> (view as? TextCheckCell)?.isChecked = InuConfig.DISABLE_GLASS_GLARE.toggle()

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            BlurredBackgroundDrawableRenderNode.inu_invalidateLiquidGlassDrawables()
        }
    }

    companion object {
        private val BUTTON_CHAT_HEADER = InuUtils.generateId()
        private val TOGGLE_DISABLE_GLASS_GLARE = InuUtils.generateId()
        private val TOGGLE_IOS_BOTTOM_BAR = InuUtils.generateId()
        private val TOGGLE_IOS_CHATS_TAB_FIRST_FOLDER = InuUtils.generateId()
        private val TOGGLE_IOS_BUTTON_PLACEMENT = InuUtils.generateId()
        private val TOGGLE_IOS_INPUT_APPEARANCE = InuUtils.generateId()
        private val TOGGLE_COMPACT_INPUT_SIZE = InuUtils.generateId()
        private val BUTTON_ACTION_BUTTON_STYLE = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "ios-style",
            titleRes = R.string.InuIosSettings,
            iconRes = R.drawable.msg_newphone,
            factory = ::IosStyleSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("disable-glass-glare", R.string.InuDisableGlassGlare, TOGGLE_DISABLE_GLASS_GLARE),
                // entiny: slugs kept verbatim from AppearanceSettingsActivity so old deeplinks keep resolving
                SearchRegistry.Entry("ios-bottom-bar", R.string.InuIosBottomBar, TOGGLE_IOS_BOTTOM_BAR),
                SearchRegistry.Entry("ios-chats-tab-first-folder", R.string.InuIosChatsTabFirstFolder, TOGGLE_IOS_CHATS_TAB_FIRST_FOLDER),
                SearchRegistry.Entry("ios-button-placement", R.string.InuIosButtonPlacement, TOGGLE_IOS_BUTTON_PLACEMENT),
                SearchRegistry.Entry("ios-input-appearance", R.string.InuIosInputAppearance, TOGGLE_IOS_INPUT_APPEARANCE),
                SearchRegistry.Entry("compact-input-size", R.string.InuCompactInputSize, TOGGLE_COMPACT_INPUT_SIZE),
                SearchRegistry.Entry("action-button-style", R.string.InuActionButtonStyle, BUTTON_ACTION_BUTTON_STYLE),
                SearchRegistry.Entry("chat-header-settings", R.string.InuChatHeaderSettings, BUTTON_CHAT_HEADER),
            ),
        )
    }
}
