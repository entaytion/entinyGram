package desu.inugram.helpers.entiny

// #if PLUGINS
import desu.inugram.InuConfig
import desu.inugram.helpers.plugins.PluginImportHelper
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.market.Market
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.LocaleController.formatString
import org.telegram.messenger.R
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.LaunchActivity

// entiny: the SDK plugin reaches this through inu.jvm (unsafe.jvm); the engine's own package is closed to it
object EntinyMarketBridge {
    @JvmStatic
    fun indexUrl(): String = InuConfig.MARKET_INDEX_URL.value

    @JvmStatic
    fun installed(): String {
        val out = JSONArray()
        for (plugin in PluginManager.plugins()) {
            out.put(JSONObject().put("id", plugin.manifest.id).put("version", plugin.manifest.version).put("enabled", plugin.enabled))
        }
        return out.toString()
    }

    @JvmStatic
    fun install(url: String, sha256: String?) {
        Market.download(url, sha256?.takeIf { it.isNotEmpty() }) { source, problem ->
            val fragment = LaunchActivity.getSafeLastFragment() ?: return@download
            if (source == null) {
                val reason = if (problem == "checksum") LocaleController.getString(R.string.InuMarketplaceChecksum) else problem.orEmpty()
                BulletinFactory.of(fragment).createErrorBulletin(formatString(R.string.InuMarketplaceDownloadFailed, reason)).show()
            } else {
                PluginImportHelper.startImport(fragment, source)
            }
        }
    }
}
// #endif
