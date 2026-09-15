package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class UnlimitedLimitsSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuUnlimitedLimits)

    private var unlimitedPinsSlider: SliderCell? = null

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuUnlimitedLimits)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_UNLIMITED_PINNED_CHATS,
                R.string.InuUnlimitedPinnedChats,
                R.string.InuUnlimitedPinnedChatsInfo,
                InuConfig.UNLIMITED_PINNED_CHATS.value,
            )
        )
        if (InuConfig.UNLIMITED_PINNED_CHATS.value) {
            if (unlimitedPinsSlider == null) {
                unlimitedPinsSlider = SliderCell(
                    context,
                    min = 5f,
                    max = 100f,
                    defaultValue = InuConfig.UNLIMITED_PINNED_CHATS_COUNT.default.toFloat(),
                    initialValue = InuConfig.UNLIMITED_PINNED_CHATS_COUNT.value.toFloat(),
                    step = 1f,
                    title = LocaleController.getString(R.string.InuUnlimitedPinnedChatsCount),
                    format = { it.toInt().toString() },
                    onChanged = { InuConfig.UNLIMITED_PINNED_CHATS_COUNT.value = it.toInt() },
                )
            } else {
                unlimitedPinsSlider?.updateColors()
            }
            items.add(UItem.asCustom(unlimitedPinsSlider))
        }
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_UNLIMITED_FAVORITE_STICKERS,
                R.string.InuUnlimitedFavoriteStickers,
                R.string.InuUnlimitedFavoriteStickersInfo,
                InuConfig.UNLIMITED_FAVORITE_STICKERS.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_UNLIMITED_FOLDER_CHATS,
                R.string.InuUnlimitedFolderChats,
                R.string.InuUnlimitedFolderChatsInfo,
                InuConfig.UNLIMITED_FOLDER_CHATS.value,
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuNetwork)))
        items.add(
            UItem.asCheck(
                TOGGLE_FASTER_DOWNLOADS,
                LocaleController.getString(R.string.InuFasterDownloads),
            ).setChecked(InuConfig.FASTER_DOWNLOADS.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_FASTER_UPLOADS,
                LocaleController.getString(R.string.InuFasterUploads),
            ).setChecked(InuConfig.FASTER_UPLOADS.value)
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuFasterTransfersInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_UNLIMITED_PINNED_CHATS -> {
                val new = InuConfig.UNLIMITED_PINNED_CHATS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                listView?.adapter?.update(true)
            }
            TOGGLE_UNLIMITED_FAVORITE_STICKERS -> {
                val new = InuConfig.UNLIMITED_FAVORITE_STICKERS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_UNLIMITED_FOLDER_CHATS -> {
                val new = InuConfig.UNLIMITED_FOLDER_CHATS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_FASTER_DOWNLOADS -> (view as? TextCheckCell)?.isChecked = InuConfig.FASTER_DOWNLOADS.toggle()
            TOGGLE_FASTER_UPLOADS -> (view as? TextCheckCell)?.isChecked = InuConfig.FASTER_UPLOADS.toggle()
        }
    }

    companion object {
        private val TOGGLE_UNLIMITED_PINNED_CHATS = InuUtils.generateId()
        private val TOGGLE_UNLIMITED_FAVORITE_STICKERS = InuUtils.generateId()
        private val TOGGLE_UNLIMITED_FOLDER_CHATS = InuUtils.generateId()
        private val TOGGLE_FASTER_DOWNLOADS = InuUtils.generateId()
        private val TOGGLE_FASTER_UPLOADS = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "unlimited-limits",
            titleRes = R.string.InuUnlimitedLimits,
            iconRes = R.drawable.inu_tabler_infinity,
            factory = ::UnlimitedLimitsSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("unlimited-pinned-chats", R.string.InuUnlimitedPinnedChats, TOGGLE_UNLIMITED_PINNED_CHATS),
                SearchRegistry.Entry("unlimited-favorite-stickers", R.string.InuUnlimitedFavoriteStickers, TOGGLE_UNLIMITED_FAVORITE_STICKERS),
                SearchRegistry.Entry("unlimited-folder-chats", R.string.InuUnlimitedFolderChats, TOGGLE_UNLIMITED_FOLDER_CHATS),
                SearchRegistry.Entry("faster-downloads", R.string.InuFasterDownloads, TOGGLE_FASTER_DOWNLOADS),
                SearchRegistry.Entry("faster-uploads", R.string.InuFasterUploads, TOGGLE_FASTER_UPLOADS),
            ),
        )
    }
}
