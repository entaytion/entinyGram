package desu.inugram.helpers.plugins.market

import desu.inugram.helpers.plugins.PluginDependencies
import desu.inugram.helpers.plugins.PluginImportHelper
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.ui.settings.installRepoPost
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory

/** installs a plugin of the Market, the SDK it needs first when that is missing. One per screen, like the repository's installer */
class MarketInstaller(private val fragment: BaseFragment) {
    private var afterSdk: MarketEntry? = null

    /** call when the installed plugins change: the plugin that waited for the SDK goes in once the SDK is on */
    fun onPluginsChanged() {
        val waiting = afterSdk ?: return
        if (PluginDependencies.unmetFor(waiting.manifest.raw[PluginDependencies.KEY].orEmpty(), waiting.id) != null) return
        afterSdk = null
        installOne(waiting)
    }

    fun install(entry: MarketEntry) {
        val missing = PluginDependencies.unmetFor(entry.manifest.raw[PluginDependencies.KEY].orEmpty(), entry.manifest.id)
        if (missing?.startsWith(PluginDependencies.SDK_ID) == true) {
            val sdk = MarketStore.entries.firstOrNull { it.id == PluginDependencies.SDK_ID }
            if (sdk != null && sdk.id != entry.id) {
                afterSdk = entry
                val installed = MarketStore.installed(sdk)
                if (installed != null && !installed.enabled) {
                    PluginManager.setEnabled(installed, true)
                    onPluginsChanged()
                    if (afterSdk == null) return
                }
                installOne(sdk)
                return
            }
        }
        installOne(entry)
    }

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
