package desu.inugram.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.BitmapDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.icons.PhosphorIconPack
import desu.inugram.helpers.icons.SolarIconPack
import desu.inugram.helpers.icons.VkIconPack
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class IconPacksSettingsActivity : SettingsPageActivity() {
    private val checkViews = HashMap<Int, TextView>()

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuIconReplacement)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val ctx = context ?: return
        val selectedPack = InuConfig.ICON_REPLACEMENT.value
        checkViews.clear()
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuIconReplacement)))

        val previews = listOf(R.drawable.msg_settings, R.drawable.msg_theme, R.drawable.msg2_secret, R.drawable.msg2_data)
        val packs = listOf(
            InuConfig.IconReplacementItem.OFF to R.string.InuIconReplacementOff,
            InuConfig.IconReplacementItem.SOLAR to R.string.InuIconReplacementSolar,
            InuConfig.IconReplacementItem.VKUI to R.string.InuIconReplacementVkui,
            InuConfig.IconReplacementItem.PHOSPHOR to R.string.InuIconReplacementPhosphor,
        )
        packs.forEach { (packId, titleRes) ->
            val rawTitle = LocaleController.getString(titleRes)
            val title = if (packId == InuConfig.IconReplacementItem.PHOSPHOR) addExperimentalSpan(rawTitle) else rawTitle
            val checked = selectedPack == packId
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(AndroidUtilities.dp(18f), AndroidUtilities.dp(12f), AndroidUtilities.dp(18f), AndroidUtilities.dp(12f))
                background = Theme.createRoundRectDrawable(AndroidUtilities.dp(12f), Theme.getColor(Theme.key_windowBackgroundWhite))
                isClickable = true
                foreground = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL)

                addView(createPackPreview(ctx, packId, previews), LayoutHelper.createLinear(64, 64, 0f, 0f, 16f, 0f))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(ctx).apply {
                        text = title
                        textSize = 16f
                        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(TextView(ctx).apply {
                        text = "✓"
                        textSize = 14f
                        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText))
                        visibility = if (checked) View.VISIBLE else View.GONE
                        checkViews[packId] = this
                    }, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 3f, 0f, 0f))
                }, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f))
                setOnClickListener { selectPack(packId) }
            }
            items.add(UItem.asCustom(PACK_BASE + packId, card).also { it.intValue = 88 })
        }

        items.add(UItem.asShadow(null))
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuNotificationIcon)))
        items.add(
            UItem.asButton(
                NOTIFICATION_ICON_ID,
                LocaleController.getString(R.string.InuNotificationIcon),
                notificationIconLabel(),
            )
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuNotificationIconInfo)))
    }

    private fun createPackPreview(context: android.content.Context, packId: Int, icons: List<Int>): ImageView =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(createPackPreviewDrawable(context, packId, icons))
        }

    private fun createPackPreviewDrawable(context: android.content.Context, packId: Int, icons: List<Int>): BitmapDrawable {
        val size = AndroidUtilities.dp(64f)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val filter = PorterDuffColorFilter(
            Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon),
            PorterDuff.Mode.MULTIPLY,
        )
        icons.forEachIndexed { index, original ->
            val mapped = when (packId) {
                InuConfig.IconReplacementItem.SOLAR -> SolarIconPack.map(original)
                InuConfig.IconReplacementItem.VKUI -> VkIconPack.map(original)
                InuConfig.IconReplacementItem.PHOSPHOR -> PhosphorIconPack.map(original)
                else -> original
            }
            val drawable = context.getDrawable(mapped) ?: return@forEachIndexed
            val cell = AndroidUtilities.dp(29f)
            val gap = AndroidUtilities.dp(4f)
            val left = (index % 2) * (cell + gap)
            val top = (index / 2) * (cell + gap)
            drawable.setBounds(left, top, left + cell, top + cell)
            drawable.colorFilter = filter
            drawable.draw(canvas)
        }
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun selectPack(packId: Int) {
        if (packId == InuConfig.ICON_REPLACEMENT.value) return
        InuConfig.ICON_REPLACEMENT.value = packId
        checkViews.forEach { (id, view) -> view.visibility = if (id == packId) View.VISIBLE else View.GONE }
        showRestartBulletin()
    }

    private fun notificationIconLabel(): String = when (InuConfig.NOTIFICATION_ICON.value) {
        InuConfig.NotificationIconItem.INUGRAM -> LocaleController.getString(R.string.InuNotificationIconInugram)
        InuConfig.NotificationIconItem.OLD_ENTINYGRAM -> LocaleController.getString(R.string.InuNotificationIconOldEntinygram)
        else -> LocaleController.getString(R.string.InuNotificationIconTelegram)
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id == NOTIFICATION_ICON_ID) {
            RadioItemOptions.show(
                this,
                view,
                listOf(
                    LocaleController.getString(R.string.InuNotificationIconTelegram),
                    LocaleController.getString(R.string.InuNotificationIconInugram),
                    LocaleController.getString(R.string.InuNotificationIconOldEntinygram),
                ),
                InuConfig.NOTIFICATION_ICON.value,
            ) { which ->
                InuConfig.NOTIFICATION_ICON.value = which
                showRestartBulletin()
                listView.adapter.update(false)
            }
            return
        }
        if (item.id in PACK_BASE..(PACK_BASE + 10)) selectPack(item.id - PACK_BASE)
    }

    companion object {
        fun currentPackLabel(): String = LocaleController.getString(
            when (InuConfig.ICON_REPLACEMENT.value) {
                InuConfig.IconReplacementItem.SOLAR -> R.string.InuIconReplacementSolar
                InuConfig.IconReplacementItem.VKUI -> R.string.InuIconReplacementVkui
                InuConfig.IconReplacementItem.PHOSPHOR -> R.string.InuIconReplacementPhosphor
                else -> R.string.InuIconReplacementOff
            }
        )

        private const val PACK_BASE = 28000
        private const val NOTIFICATION_ICON_ID = 28020

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
                SearchRegistry.Entry("notification-icon", R.string.InuNotificationIcon, NOTIFICATION_ICON_ID),
                SearchRegistry.Entry("icon-pack-phosphor", R.string.InuIconReplacementPhosphor, PACK_BASE + InuConfig.IconReplacementItem.PHOSPHOR),
            ),
        )
    }
}
