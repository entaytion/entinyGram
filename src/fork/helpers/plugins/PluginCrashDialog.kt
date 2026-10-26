package desu.inugram.helpers.plugins

import desu.inugram.ui.settings.PluginsActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.LaunchActivity

object PluginCrashDialog {
    private const val TAIL_LINES = 12

    @JvmStatic
    fun maybeShow(activity: LaunchActivity) {
        val report = PluginCrashLog.takeReport() ?: return
        val culprit = report.culprit ?: "?"
        val header = "plugin: $culprit, reason: ${report.reason}"
        val message = LocaleController.formatString(R.string.InuPluginCrashMessage, culprit) + "\n\n" +
            PluginCrashLog.tail(TAIL_LINES).ifEmpty { header }
        AlertDialog.Builder(activity)
            .setTitle(LocaleController.getString(R.string.InuPluginCrashTitle))
            .setMessage(message)
            .setPositiveButton(LocaleController.getString(R.string.InuPluginCrashCopy)) { _, _ ->
                AndroidUtilities.addToClipboard("$header\n\n${PluginCrashLog.text()}")
                activity.actionBarLayout?.lastFragment?.let {
                    BulletinFactory.of(it).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show()
                }
            }
            .setNeutralButton(LocaleController.getString(R.string.InuPlugins)) { _, _ ->
                activity.presentFragment(PluginsActivity())
            }
            .setNegativeButton(LocaleController.getString(R.string.Close), null)
            .show()
    }
}
