package desu.inugram.helpers

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory
import org.telegram.utils.proxy.ProxySettings

object ProxyImportHelper {
    @JvmStatic
    fun importFromClipboard(fragment: BaseFragment) {
        val context = fragment.context ?: return
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()

        SharedConfig.loadProxyList()
        val known = SharedConfig.proxyList.mapTo(HashSet()) { it.settings }
        val imported = text.lineSequence()
            .mapNotNull { ProxySettings.fromUri(Uri.parse(it.trim())) }
            .filter { it.isValid && known.add(it) }
            .map { SharedConfig.ProxyInfo(it) }
            .toList()

        if (imported.isEmpty()) {
            BulletinFactory.of(fragment).createErrorBulletin(
                LocaleController.getString(R.string.InuProxyImportNothing)
            ).show()
            return
        }
        SharedConfig.proxyList.addAll(0, imported)
        SharedConfig.saveProxyList()
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged)
        BulletinFactory.of(fragment).createSimpleBulletin(
            R.raw.contact_check,
            LocaleController.formatPluralString("InuProxyImported", imported.size)
        ).show()
    }
}
