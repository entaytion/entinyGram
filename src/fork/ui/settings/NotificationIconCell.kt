package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

// entiny: notification icon picked inline, no dialog
@SuppressLint("ViewConstructor")
class NotificationIconCell(
    context: Context,
    private val onChanged: () -> Unit,
) : LinearLayout(context) {

    private val row = LinearLayout(context)

    init {
        orientation = VERTICAL
        setPadding(AndroidUtilities.dp(21f), AndroidUtilities.dp(12f), AndroidUtilities.dp(21f), AndroidUtilities.dp(16f))
        addView(TextView(context).apply {
            text = LocaleController.getString(R.string.InuNotificationIcon)
            textSize = 16f
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        })
        row.gravity = Gravity.CENTER
        row.setPadding(0, AndroidUtilities.dp(12f), 0, 0)
        addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        rebuild()
    }

    private fun rebuild() {
        val current = InuConfig.NOTIFICATION_ICON.value
        row.removeAllViews()
        for ((value, icon, label) in OPTIONS) {
            row.addView(Chip(context, icon, LocaleController.getString(label), current == value) {
                if (InuConfig.NOTIFICATION_ICON.value == value) return@Chip
                InuConfig.NOTIFICATION_ICON.value = value
                rebuild()
                onChanged()
            }, LinearLayout.LayoutParams(0, AndroidUtilities.dp(64f), 1f))
        }
    }

    private class Chip(context: Context, iconRes: Int, label: String, selected: Boolean, onClick: () -> Unit) : FrameLayout(context) {
        init {
            val tint = Theme.getColor(if (selected) Theme.key_windowBackgroundWhiteBlueIcon else Theme.key_windowBackgroundWhiteGrayIcon)
            addView(ImageView(context).apply {
                setImageResource(iconRes)
                colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
            }, LayoutHelper.createFrame(24, 24f, Gravity.CENTER_HORIZONTAL or Gravity.TOP, 0f, 10f, 0f, 0f))
            addView(TextView(context).apply {
                text = label
                textSize = 11f
                setTextColor(tint)
            }, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM, 0f, 0f, 0f, 8f))
            if (selected) {
                background = Theme.createRoundRectDrawable(AndroidUtilities.dp(12f), Theme.getColor(Theme.key_listSelector))
            }
            setOnClickListener { onClick() }
        }
    }

    companion object {
        private val OPTIONS = listOf(
            Triple(InuConfig.NotificationIconItem.TELEGRAM, R.drawable.notification, R.string.InuNotificationIconTelegram),
            Triple(InuConfig.NotificationIconItem.INUGRAM, R.drawable.icon_notification_inu, R.string.InuNotificationIconInugram),
            Triple(InuConfig.NotificationIconItem.OLD_ENTINYGRAM, R.drawable.icon_notification_old_inu, R.string.InuNotificationIconOldEntinygram),
        )
    }
}
