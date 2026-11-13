package desu.inugram.helpers.plugins.market

import desu.inugram.helpers.plugins.PluginImportHelper
import desu.inugram.ui.settings.installRepoPost
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory

/** installs a plugin of the Market. One per screen, like the repository's installer */
class MarketInstaller(private val fragment: BaseFragment) {
    fun install(entry: MarketEntry) = installOne(entry)

    private fun installOne(entry: MarketEntry) {
        when (entry.source) {
            MarketSource.CHANNEL -> entry.post?.let { installRepoPost(fragment, it) }
            MarketSource.OFFICIAL -> entry.file?.let { downloadOfficial(entry, it) }
            MarketSource.LOCAL -> Unit
        }
    }

    private fun downloadOfficial(entry: MarketEntry, file: MarketFile) {
        val context = fragment.parentActivity ?: return
        var cancelled = false
        val progress = AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER).apply {
            setOnCancelListener { cancelled = true }
            showDelayed(300)
        }
        Utilities.globalQueue.postRunnable {
            val result = runCatching {
                val bytes = MarketCatalog.download(file.url, MAX_BYTES)
                // a bundle is only handed to the installer when the catalog states its sha256 and it matches
                check(file.sha256.isNotEmpty() && MarketCatalog.sha256(bytes).equals(file.sha256, ignoreCase = true)) { "sha256 missing or differs" }
                bytes.toString(Charsets.UTF_8)
            }
            AndroidUtilities.runOnUIThread {
                progress.dismiss()
                if (cancelled) return@runOnUIThread
                val source = result.getOrNull()
                if (source == null) {
                    BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.InuPluginRepoErrorDownload)).show()
                } else {
                    PluginImportHelper.startImport(fragment, source)
                }
            }
        }
    }

    private companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}
