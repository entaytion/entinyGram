package desu.inugram.ui.settings

import android.content.Context
import desu.inugram.core.plugins.PluginManifest
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.plugins.PluginDependencies
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem

private val DEPENDENCIES = InuUtils.generateId()
private const val GRANT_BASE = 20000

internal fun addPluginDependencies(items: ArrayList<UItem>, manifest: PluginManifest) {
    val needs = PluginDependencies.needs(manifest)
    if (needs.isEmpty()) return
    val value = needs.joinToString(", ") { need -> need.minVersion?.let { "${need.id} >=$it" } ?: need.id }
    items.add(TwoLineEntryCell.Factory.of(DEPENDENCIES, R.drawable.msg_secret, LocaleController.getString(R.string.InuPluginRepoDependencies), value, false))
}

internal fun addPluginPermissions(items: ArrayList<UItem>, context: Context, manifest: PluginManifest, grantRows: HashMap<Int, GrantRowView>) {
    items.add(UItem.asHeader(LocaleController.getString(R.string.InuPluginsPermissions)))
    val grants = sortedGrants(manifest.grants)
    if (grants.isEmpty()) {
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuPluginsPermissionsNone)))
        return
    }
    grants.forEachIndexed { i, (name, scopes) ->
        val row = grantRows.getOrPut(i) { GrantRowView(context) }
        row.bind(name, scopes, divider = i != grants.lastIndex)
        items.add(UItem.asCustom(GRANT_BASE + i, row))
    }
    items.add(UItem.asShadow(null))
}
