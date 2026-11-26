package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AttachMenuHideSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAttachHideMenu)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAttachHideMenu)))
        ITEMS.forEachIndexed { index, (key, textRes) ->
            items.add(
                UItem.asCheck(BASE_ID + index, LocaleController.getString(textRes))
                    .setChecked(key in InuConfig.ATTACH_HIDDEN.value)
            )
        }
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val index = item.id - BASE_ID
        if (index !in ITEMS.indices) return
        val key = ITEMS[index].first
        val hidden = InuConfig.ATTACH_HIDDEN.value.toMutableSet()
        val nowHidden = key !in hidden
        if (nowHidden) hidden.add(key) else hidden.remove(key)
        InuConfig.ATTACH_HIDDEN.value = hidden
        (view as? TextCheckCell)?.isChecked = nowHidden
    }

    companion object {
        private val BASE_ID = InuUtils.generateId()

        private val ITEMS = listOf(
            "preview" to R.string.InuAttachHidePreview,
            "record" to R.string.InuAttachHideRecord,
            "gallery" to R.string.ChatGallery,
            "camera" to R.string.InuAttachCamera,
            "document" to R.string.ChatDocument,
            "money" to R.string.WalletAttachMoney,
            "music" to R.string.AttachMusic,
            "poll" to R.string.Poll,
            "contact" to R.string.AttachContact,
            "quick_replies" to R.string.AttachQuickReplies,
            "todo" to R.string.Todo,
            "sticker" to R.string.ChatSticker,
            "links" to R.string.ChatLink,
            "emoji" to R.string.ChatEmoji,
            "rich" to R.string.AttachArticle,
            "location" to R.string.ChatLocation,
        )
    }
}
