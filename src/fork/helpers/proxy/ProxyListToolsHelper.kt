package desu.inugram.helpers.proxy

import android.content.Context
import android.content.DialogInterface
import android.view.View
import android.widget.TextView
import desu.inugram.InuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCheckCell

object ProxyListToolsHelper {
    @JvmStatic
    fun isFailed(info: SharedConfig.ProxyInfo): Boolean =
        !info.checking && info.availableCheckTime != 0L && !info.available

    @JvmStatic
    fun showPingAll(list: List<SharedConfig.ProxyInfo>): Boolean =
        InuConfig.PROXY_LIST_TOOLS.value && list.size >= 2

    @JvmStatic
    fun showDeleteFailed(list: List<SharedConfig.ProxyInfo>): Boolean =
        InuConfig.PROXY_LIST_TOOLS.value && list.any(::isFailed)

    @JvmStatic
    fun resetCheckTimes(list: List<SharedConfig.ProxyInfo>) {
        for (info in list) {
            if (!info.checking) info.availableCheckTime = 0
        }
    }

    @JvmStatic
    fun confirmDeleteFailed(fragment: BaseFragment, list: List<SharedConfig.ProxyInfo>, onDeleted: Utilities.Callback<Boolean>) {
        val activity = fragment.parentActivity ?: return
        val dialog = AlertDialog.Builder(activity)
            .setTitle(LocaleController.getString(R.string.InuDeleteFailedProxies))
            .setMessage(LocaleController.getString(R.string.InuDeleteFailedProxiesConfirm))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.Delete)) { _, _ ->
                var removedCurrent = false
                for (info in list.toList()) {
                    if (!isFailed(info)) continue
                    if (SharedConfig.currentProxy === info) removedCurrent = true
                    SharedConfig.deleteProxy(info)
                }
                onDeleted.run(removedCurrent)
            }
            .create()
        fragment.showDialog(dialog)
        (dialog.getButton(DialogInterface.BUTTON_POSITIVE) as? TextView)?.setTextColor(Theme.getColor(Theme.key_text_RedBold))
    }

    @JvmStatic
    fun createConnectImmediatelyCell(context: Context, addingNewProxy: Boolean, onChange: Utilities.Callback<Boolean>): View? {
        if (!InuConfig.PROXY_CONNECT_CHOICE.value || !addingNewProxy) return null
        return TextCheckCell(context).apply {
            setTextAndCheck(LocaleController.getString(R.string.InuConnectProxyImmediately), true, false)
            background = Theme.getSelectorDrawable(true)
            setOnClickListener {
                val checked = !isChecked
                isChecked = checked
                onChange.run(checked)
            }
        }
    }
}
