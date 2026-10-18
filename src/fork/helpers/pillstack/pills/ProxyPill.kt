package desu.inugram.helpers.pillstack.pills

import android.annotation.SuppressLint
import android.content.Context
import desu.inugram.helpers.pillstack.PillType
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.LaunchActivity
import org.telegram.ui.ProxyListActivity

// entiny: proxy state -- off / connecting / ping, ported from exteraGram/exteraless's ProxyPill.
@SuppressLint("ViewConstructor")
class ProxyPill(context: Context, resourcesProvider: Theme.ResourcesProvider?) :
    BasePill(context, resourcesProvider), NotificationCenter.NotificationCenterDelegate {

    private var lastAccount = 0

    init {
        setupPill(startPaddingDp = 8f, endPaddingDp = 10f, iconGapDp = 2f)
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

    override fun onPillLongClicked(): Boolean = showPillMenu {
        add(R.drawable.msg_retry, LocaleController.getString(R.string.Refresh)) { onUpdateData(true) }
        addSettingsEntry()
    }

    override fun updateColors(): Unit = applyPillColors(if (isProxyConnected()) activeAccent() else defaultAccent())

    private fun isProxyConnected(): Boolean {
        if (!SharedConfig.isProxyEnabled() || SharedConfig.currentProxy == null) return false
        val state = ConnectionsManager.getInstance(UserConfig.selectedAccount).connectionState
        return state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating
    }
}
