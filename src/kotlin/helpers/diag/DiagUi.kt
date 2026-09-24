package desu.inugram.helpers.diag

import desu.inugram.helpers.LogsHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.LaunchActivity
import java.io.File
import java.text.DateFormat
import java.util.Date

object DiagUi {

    fun isProfileFile(name: String?): Boolean = name?.endsWith(DiagLog.FILE_SUFFIX, ignoreCase = true) == true

    // entiny: always ask first -- a profile only turns on local logging, but the user should know what gets recorded
    fun startImport(fragment: BaseFragment, file: File) {
        Utilities.globalQueue.postRunnable {
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            AndroidUtilities.runOnUIThread {
                val context = fragment.parentActivity ?: return@runOnUIThread
                val (profile, error) = if (text == null) null to "unreadable" else DiagLog.parseFile(text)
                if (profile == null) {
                    BulletinFactory.of(fragment).createErrorBulletin(
                        LocaleController.formatString(R.string.InuDiagInvalid, error ?: ""),
                    ).show()
                    return@runOnUIThread
                }
                AlertDialog.Builder(context, fragment.resourceProvider)
                    .setTitle(LocaleController.getString(R.string.InuDiagTitle))
                    .setMessage(LocaleController.formatString(R.string.InuDiagConfirm, profile.name, describe(profile)))
                    .setPositiveButton(LocaleController.getString(R.string.InuDiagEnable)) { _, _ ->
                        DiagLog.activate(profile)
                        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.InuDiagEnabled)).show()
                    }
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .create()
                    .let { fragment.showDialog(it) }
            }
        }
    }

    fun describe(profile: DiagLog.Profile): String {
        val zones = if ("*" in profile.categories) DiagLog.KNOWN_CATEGORIES else profile.categories.toList()
        val p = profile.probes
        val probes = listOfNotNull(
            p.events.takeIf { it.isNotEmpty() }?.let { "events " + it.joinToString("/") },
            p.requests.takeIf { it.isNotEmpty() }?.let { "requests " + it.joinToString("/") },
            p.updates.takeIf { it.isNotEmpty() }?.let { "updates " + it.joinToString("/") },
            p.dumpWhat.takeIf { it.isNotEmpty() }?.let { "dumps " + it.joinToString("/") },
        )
        val what = (zones + probes).joinToString(", ")
        val until = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(profile.expiresAt))
        return LocaleController.formatString(R.string.InuDiagDescribe, what, until)
    }

    fun share(fragment: BaseFragment) {
        val activity = fragment.parentActivity as? LaunchActivity ?: return
        LogsHelper.shareZip(activity) { ok ->
            if (!ok) BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.InuDiagNothing)).show()
        }
    }
}
