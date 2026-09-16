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
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedTextView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScaleStateListAnimator
import org.telegram.ui.LaunchActivity
import org.telegram.ui.ProxyListActivity

// entiny: proxy state -- off / connecting / ping, ported from exteraGram/exteraless's ProxyPill.
@SuppressLint("ViewConstructor")
class ProxyPill(context: Context, resourcesProvider: Theme.ResourcesProvider?) :
    BasePill(context, resourcesProvider), NotificationCenter.NotificationCenterDelegate {

    private val layout = LinearLayout(context)
    private val iconView = ImageView(context)
    private val textView = AnimatedTextView(context, true, true, true)
    private var lastAccount = 0

    init {
        layout.orientation = LinearLayout.HORIZONTAL
        layout.gravity = Gravity.CENTER
        layout.minimumWidth = AndroidUtilities.dp(48f)
        layout.setPadding(AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(10f), 0)
        addView(
            layout, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, 28,
                (if (LocaleController.isRTL) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL
            )
        )

        iconView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0f, 0f, 2f, 0f))

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

    override fun getPillId(): Int = PillType.PROXY.id

    override fun getRefreshInterval(): Long = 0

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        onUpdateData(true)
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged)
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxyCheckDone)
        lastAccount = UserConfig.selectedAccount
        NotificationCenter.getInstance(lastAccount).addObserver(this, NotificationCenter.didUpdateConnectionState)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxySettingsChanged)
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxyCheckDone)
        NotificationCenter.getInstance(lastAccount).removeObserver(this, NotificationCenter.didUpdateConnectionState)
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.proxySettingsChanged || id == NotificationCenter.proxyCheckDone || id == NotificationCenter.didUpdateConnectionState) {
            onUpdateData(true)
        }
    }

    override fun onUpdateData(force: Boolean) {
        val enabled = SharedConfig.isProxyEnabled()
        val state = ConnectionsManager.getInstance(UserConfig.selectedAccount).connectionState
        val connected = state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating
        val previous = textView.text?.toString().orEmpty()

        val text: String
        if (!enabled || SharedConfig.currentProxy == null) {
            iconView.setImageResource(R.drawable.proxy_off_solar)
            text = LocaleController.getString(R.string.Proxy)
            stopLoading()
        } else if (connected) {
            val ping = Utilities.clamp(SharedConfig.currentProxy.ping, 9999L, 0L)
            iconView.setImageResource(R.drawable.proxy_on_solar)
            text = if (ping > 0) LocaleController.formatString(R.string.InuPillStackProxyPing, ping)
            else LocaleController.getString(R.string.MenuProxyConnected)
            stopLoading()
        } else {
            iconView.setImageResource(R.drawable.proxy_off_solar)
            text = LocaleController.getString(R.string.MenuProxyConnecting)
            startLoading()
        }

        if (force || previous != text) {
            if (force) animateSizeChange()
            textView.setText(text, force)
        }
        updateColors()
    }

    override fun onPillClicked() {
        val fragment = LaunchActivity.getSafeLastFragment()
        fragment?.presentFragment(ProxyListActivity())
    }

    override fun onPillLongClicked(): Boolean = false

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
        val enabled = SharedConfig.isProxyEnabled()
        val state = ConnectionsManager.getInstance(UserConfig.selectedAccount).connectionState
        val connected = enabled && SharedConfig.currentProxy != null &&
            (state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating)
        val color = if (connected) getThemedColor(Theme.key_windowBackgroundWhiteGreenText)
        else getThemedColor(Theme.key_windowBackgroundWhiteBlackText, 0.75f)
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
