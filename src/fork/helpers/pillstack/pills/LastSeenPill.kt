package desu.inugram.helpers.pillstack.pills

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import desu.inugram.helpers.pillstack.PillType
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedTextView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScaleStateListAnimator
import java.util.Calendar
import java.util.Date

// entiny: shows when the current account was last seen online, i.e. what others see while Ghost Mode hides you
@SuppressLint("ViewConstructor")
class LastSeenPill(context: Context, resourcesProvider: Theme.ResourcesProvider?) : BasePill(context, resourcesProvider) {

    private val layout = LinearLayout(context)
    private val iconView = ImageView(context)
    private val textView = AnimatedTextView(context, true, true, true)

    init {
        layout.orientation = LinearLayout.HORIZONTAL
        layout.gravity = Gravity.CENTER
        layout.minimumWidth = AndroidUtilities.dp(48f)
        layout.setPadding(AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(8f), 0)
        addView(
            layout, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, 28,
                (if (LocaleController.isRTL) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL
            )
        )

        iconView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        iconView.setImageResource(R.drawable.phosphor_eye)
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0f, 0f, 4f, 0f))

        textView.setTextSize(AndroidUtilities.dp(13f).toFloat())
        textView.setIncludeFontPadding(false)
        textView.setTypeface(AndroidUtilities.bold())
        textView.adaptWidth = true
        layout.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL))

        setLoadingTargetView(layout)
        updateColors()
        ScaleStateListAnimator.apply(layout)
        onUpdateData(false)
    }

    override fun getPillId(): Int = PillType.LAST_SEEN.id

    override fun getRefreshInterval(): Long = 30_000L

    override fun onUpdateData(force: Boolean) {
        textView.setText(statusText(), force)
        markDataUpdated()
    }

    private fun statusText(): String {
        return when (val status = UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser()?.status) {
            is TLRPC.TL_userStatusOnline -> LocaleController.getString(R.string.Online)
            is TLRPC.TL_userStatusOffline -> formatSeen(status.expires.toLong() * 1000L)
            is TLRPC.TL_userStatusRecently -> LocaleController.getString(R.string.Lately)
            else -> "—"
        }
    }

    private fun formatSeen(millis: Long): String {
        val seen = Calendar.getInstance().apply { time = Date(millis) }
        val now = Calendar.getInstance()
        val sameDay = seen.get(Calendar.YEAR) == now.get(Calendar.YEAR) && seen.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        val formatter = if (sameDay) LocaleController.getInstance().getFormatterDay() else LocaleController.getInstance().getFormatterDayMonth()
        return formatter.format(Date(millis))
    }

    override fun onPillClicked() {
        onUpdateData(true)
    }

    override fun onPillLongClicked(): Boolean = showPillMenu {
        add(R.drawable.msg_settings, LocaleController.getString(R.string.Settings)) { openPillSettings() }
    }

    override fun drawableHotspotChanged(x: Float, y: Float) {
        if (loading) return
        super.drawableHotspotChanged(x, y)
        layout.drawableHotspotChanged(x - layout.left, y - layout.top)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(if (loading) false else pressed)
        layout.isPressed = if (loading) false else pressed
    }

    override fun updateColors() {
        val color = getThemedColor(Theme.key_windowBackgroundWhiteBlackText, 0.75f)
        layout.background = Theme.createSimpleSelectorRoundRectDrawable(
            AndroidUtilities.dp(14f),
            if (Theme.isCurrentThemeDark()) getThemedColor(Theme.key_windowBackgroundWhite) else Theme.multAlpha(color, 0.09f),
            Theme.multAlpha(color, 0.1f)
        )
        textView.setTextColor(color)
        iconView.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY)
        updateLoadingColors()
    }
}
