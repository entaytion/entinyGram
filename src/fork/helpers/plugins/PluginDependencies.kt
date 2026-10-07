package desu.inugram.helpers.plugins

import desu.inugram.core.plugins.PluginManifest
import org.telegram.messenger.LocaleController.formatString
import org.telegram.messenger.R

/**
 * `@requires <plugin-id> [>=<version>]`, repeatable: the plugin installs and runs only while the named plugins
 * are installed and enabled. This is how a plugin depends on another, the entinyGram SDK being the first.
 */
object PluginDependencies {
    const val KEY = "requires"

    /** the plugin that carries the entinyGram SDK; plugins that use its directives require it */
    const val SDK_ID = "entinygram.sdk"

    class Need(val id: String, val minVersion: String?)

    fun needs(manifest: PluginManifest): List<Need> = manifest.raw[KEY].orEmpty().mapNotNull { line ->
        val parts = line.trim().split(' ').filter { it.isNotEmpty() }
        val id = parts.firstOrNull() ?: return@mapNotNull null
        Need(id, parts.getOrNull(1)?.removePrefix(">="))
    }

    /** why [manifest] cannot be installed or run right now, or null */
    fun unmet(manifest: PluginManifest): String? {
        val problem = unmetFor(manifest.raw[KEY].orEmpty(), manifest.id) ?: return null
        return if (problem.contains(' ')) formatString(R.string.InuPluginsErrorRequiresVersion, problem.substringBefore(' '), problem.substringAfter(' '))
        else formatString(R.string.InuPluginsErrorRequires, problem)
    }

    /**
     * What is missing for plugins that need [lines] (`@requires` values), as the id or `id version`, or null when
     * everything is there. A plugin never waits for itself.
     */
    fun unmetFor(lines: List<String>, selfId: String?): String? {
        for (line in lines) {
            val parts = line.trim().split(' ').filter { it.isNotEmpty() }
            val id = parts.firstOrNull() ?: continue
            if (id == selfId) continue
            val wanted = parts.getOrNull(1)?.removePrefix(">=")
            val installed = PluginManager.plugins().firstOrNull { it.manifest.id == id && it.enabled } ?: return id
            if (wanted != null && compareVersions(installed.manifest.version, wanted) < 0) return "$id $wanted"
        }
        return null
    }

    /** the SDK plugin is installed and on: what the marketplace and the SDK directives wait for */
    fun sdkEnabled(): Boolean = PluginManager.plugins().any { it.manifest.id == SDK_ID && it.enabled }

    /** does [plugin] build on the SDK, and is the SDK there */
    fun usesSdk(plugin: Plugin): Boolean =
        (plugin.manifest.id == SDK_ID || needs(plugin.manifest).any { it.id == SDK_ID }) && unmet(plugin.manifest) == null

    /** dotted numbers; a missing part counts as 0, anything else as 0 too */
    fun compareVersions(have: String?, want: String): Int {
        val a = (have ?: "").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val b = want.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
