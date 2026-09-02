package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class IconPacksSettingsActivity : SettingsPageActivity() {

    private var previewCell: IconPackPreviewCell? = null

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuIconReplacement)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val ctx = context ?: return

        // Preview Section
        if (previewCell == null) {
            previewCell = IconPackPreviewCell(ctx)
        }
        previewCell?.setPack(InuConfig.ICON_REPLACEMENT.value)
        items.add(UItem.asCustom(PREVIEW_ID, previewCell))

        // Packs Header & Radios
        val current = InuConfig.ICON_REPLACEMENT.value
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuIconReplacement)))

        items.add(
            UItem.asRadio(
                PACK_BASE + InuConfig.IconReplacementItem.OFF,
                LocaleController.getString(R.string.InuIconReplacementOff)
            ).also { it.checked = current == InuConfig.IconReplacementItem.OFF }
        )

        items.add(
            UItem.asRadio(
                PACK_BASE + InuConfig.IconReplacementItem.SOLAR,
                LocaleController.getString(R.string.InuIconReplacementSolar)
            ).also { it.checked = current == InuConfig.IconReplacementItem.SOLAR }
        )

        items.add(
            UItem.asRadio(
                PACK_BASE + InuConfig.IconReplacementItem.VKUI,
                LocaleController.getString(R.string.InuIconReplacementVkui)
            ).also { it.checked = current == InuConfig.IconReplacementItem.VKUI }
        )

        items.add(
            UItem.asRadio(
                PACK_BASE + InuConfig.IconReplacementItem.PHOSPHOR,
                LocaleController.getString(R.string.InuIconReplacementPhosphor)
            ).also { it.checked = current == InuConfig.IconReplacementItem.PHOSPHOR }
        )

        items.add(UItem.asShadow(LocaleController.getString(R.string.InuIconReplacementRestartInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id in PACK_BASE..(PACK_BASE + 10)) {
            val newPack = item.id - PACK_BASE
            if (newPack != InuConfig.ICON_REPLACEMENT.value) {
                InuConfig.ICON_REPLACEMENT.value = newPack
                previewCell?.setPack(newPack)
                showRestartBulletin()
                listView.adapter.update(true)
            }
        }
    }

    companion object {
        private val PREVIEW_ID = InuUtils.generateId()
        private const val PACK_BASE = 28000

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "icon-packs",
            titleRes = R.string.InuIconReplacement,
            iconRes = R.drawable.msg_theme,
            factory = ::IconPacksSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("icon-pack-default", R.string.InuIconReplacementOff, PACK_BASE + InuConfig.IconReplacementItem.OFF),
                SearchRegistry.Entry("icon-pack-solar", R.string.InuIconReplacementSolar, PACK_BASE + InuConfig.IconReplacementItem.SOLAR),
                SearchRegistry.Entry("icon-pack-vkui", R.string.InuIconReplacementVkui, PACK_BASE + InuConfig.IconReplacementItem.VKUI),
                SearchRegistry.Entry("icon-pack-phosphor", R.string.InuIconReplacementPhosphor, PACK_BASE + InuConfig.IconReplacementItem.PHOSPHOR),
            ),
        )
    }
}

