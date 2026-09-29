package desu.inugram.helpers.proxy

import android.app.Activity
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.Bulletin
import org.telegram.ui.Components.ButtonSpan
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.TableView
import org.telegram.ui.Components.UndoView
import org.telegram.ui.LaunchActivity
import org.telegram.ui.Stories.recorder.ButtonWithCounterView
import org.telegram.utils.proxy.ProxySettings

object ProxyLinkAlertHelper {
    @JvmStatic
    fun show(activity: Activity, settings: ProxySettings?): Boolean {
        if (settings == null || !settings.isValid()) return false

        val builder = BottomSheet.Builder(activity)
            .setApplyTopPadding(false)
            .setApplyBottomPadding(false)
        val dismiss = builder.dismissRunnable
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        builder.setCustomView(content)

        val header = TextView(activity).apply {
            text = LocaleController.getString(R.string.UseProxyTitle)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
        }
        content.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP or Gravity.FILL_HORIZONTAL, 22, 18, 22, 0))

        val table = TableView(activity, null)
        content.addView(table, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP or Gravity.FILL_HORIZONTAL, 14, 18, 14, 0))
        if (!TextUtils.isEmpty(settings.address)) table.addRow(LocaleController.getString(R.string.UseProxyAddress), settings.address)
        if (settings.port != 0) table.addRow(LocaleController.getString(R.string.UseProxyPort), settings.port.toString())
        if (!TextUtils.isEmpty(settings.secret)) table.addRow(LocaleController.getString(R.string.UseProxySecret), settings.secret)
        if (!TextUtils.isEmpty(settings.user)) table.addRow(LocaleController.getString(R.string.UseProxyUsername), settings.user)
        if (!TextUtils.isEmpty(settings.password)) table.addRow(LocaleController.getString(R.string.UseProxyPassword), settings.password)

        val statusViews = arrayOfNulls<ButtonSpan.TextViewButtons>(1)
        table.addRow(LocaleController.getString(R.string.ProxyStatus), "", statusViews)
        statusViews[0]!!.apply {
            setDisablePaddingsOffsetY(true)
            setPadding(AndroidUtilities.dp(12.66f), AndroidUtilities.dp(9.33f), AndroidUtilities.dp(12.66f), AndroidUtilities.dp(9.33f))
        }
        (statusViews[0]!!.parent as? View)?.setPadding(0, 0, 0, 0)
        val checking = booleanArrayOf(false)
        val checkProxy = {
            if (!checking[0]) {
                checking[0] = true
                statusViews[0]!!.clear()
                statusViews[0]!!.text = LocaleController.getString(R.string.ProxyBottomSheetChecking) + "..."
                try {
                    ConnectionsManager.getInstance(UserConfig.selectedAccount).checkProxy(settings) { time ->
                        AndroidUtilities.runOnUIThread {
                            checking[0] = false
                            if (time == -1L) {
                                statusViews[0]!!.text = LocaleController.getString(R.string.Unavailable)
                                statusViews[0]!!.setTextColor(Theme.getColor(Theme.key_text_RedRegular))
                            } else {
                                statusViews[0]!!.text = LocaleController.formatString(R.string.Ping2, time)
                                val colorKey = when {
                                    time < 300L -> Theme.key_windowBackgroundWhiteGreenText
                                    time < 1000L -> Theme.key_color_orange
                                    else -> Theme.key_text_RedRegular
                                }
                                statusViews[0]!!.setTextColor(Theme.getColor(colorKey))
                            }
                        }
                    }
                } catch (_: NumberFormatException) {
                    checking[0] = false
                    statusViews[0]!!.text = LocaleController.getString(R.string.Unavailable)
                    statusViews[0]!!.setTextColor(Theme.getColor(Theme.key_text_RedRegular))
                }
            }
        }
        statusViews[0]!!.text = LocaleController.getString(R.string.ProxyBottomSheetChecking) + "..."

        if (!TextUtils.isEmpty(settings.secret)) {
            table.addFullRow(LocaleController.getString(R.string.UseProxyTelegramInfo2)).apply {
                setFilled(true)
                (getChildAt(0) as? TextView)?.apply {
                    textSize = 11f
                    gravity = Gravity.CENTER
                }
            }
        }

        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val copyButton = ButtonWithCounterView(activity, null).setRound().apply {
            setText(LocaleController.getString(R.string.Copy))
            setOnClickListener { AndroidUtilities.addToClipboard(settings.link) }
        }
        actions.addView(copyButton, LinearLayout.LayoutParams(0, AndroidUtilities.dp(48f), 1f))

        val saveButton = ButtonWithCounterView(activity, null).setRound().apply {
            setText(LocaleController.getString(R.string.Save))
            setOnClickListener {
                SharedConfig.addProxy(SharedConfig.ProxyInfo(settings))
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged)
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.showBulletin, Bulletin.TYPE_SUCCESS, LocaleController.getString(R.string.ProxyAddedSuccess))
                dismiss.run()
            }
        }
        actions.addView(saveButton, LinearLayout.LayoutParams(0, AndroidUtilities.dp(48f), 1f).apply { leftMargin = AndroidUtilities.dp(10f) })
        content.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP or Gravity.FILL_HORIZONTAL, 14, 18, 14, 0))

        val connectButton = ButtonWithCounterView(activity, null).setRound().apply {
            setText(LocaleController.getString(R.string.ConnectingConnectProxy))
            setOnClickListener {
                val editor = MessagesController.getGlobalMainSettings().edit()
                editor.putBoolean("proxy_enabled", true)
                settings.toSharedPreferences(editor)
                editor.commit()
                val info = SharedConfig.addProxy(SharedConfig.ProxyInfo(settings))
                SharedConfig.currentProxy = info
                ConnectionsManager.setProxySettings(true, settings)
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged)
                val fragment = (activity as? LaunchActivity)?.actionBarLayout?.lastFragment
                val undoView = (fragment as? ChatActivity)?.undoView
                if (undoView != null) {
                    undoView.showWithAction(0, UndoView.ACTION_PROXY_ADDED, null)
                } else {
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.showBulletin, Bulletin.TYPE_SUCCESS, LocaleController.getString(R.string.ProxyAddedSuccess))
                }
                dismiss.run()
            }
        }
        content.addView(connectButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP or Gravity.FILL_HORIZONTAL, 14, 8, 14, 14))

        builder.show()
        checkProxy()
        return true
    }
}
