package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginManifest
import desu.inugram.helpers.plugins.market.MarketStore
import org.telegram.messenger.LocaleController.formatString
import org.telegram.messenger.R

object PluginDependencies {
    const val KEY = "requires"

    class Need(val id: String, val minVersion: String?)

    fun needs(manifest: PluginManifest): List<Need> = manifest.raw[KEY].orEmpty().mapNotNull { line ->
        val parts = line.trim().split(' ').filter { it.isNotEmpty() }
        val id = parts.firstOrNull() ?: return@mapNotNull null
        Need(id, parts.getOrNull(1)?.removePrefix(">="))
    }

    fun unmet(manifest: PluginManifest): String? {
        val problem = unmetFor(manifest) ?: return null
        return if (problem.contains(' ')) formatString(R.string.InuPluginsErrorRequiresVersion, problem.substringBefore(' '), problem.substringAfter(' '))
        else formatString(R.string.InuPluginsErrorRequires, problem)
    }

    private fun unmetFor(manifest: PluginManifest): String? {
        for (need in needs(manifest)) {
            if (need.id == manifest.id) continue
            val installed = PluginManager.plugins().firstOrNull { it.manifest.id == need.id && it.enabled } ?: return need.id
            val wanted = need.minVersion
            if (wanted != null && MarketStore.compareVersions(installed.manifest.version, wanted) < 0) return "${need.id} $wanted"
        }
        return null
    }
}
