package desu.inugram.helpers.plugins

import desu.inugram.ui.settings.Row
import desu.inugram.ui.settings.Schema
import desu.inugram.ui.settings.SchemaPageActivity
import org.telegram.messenger.ApplicationLoader
import org.telegram.ui.Components.UItem
import java.util.concurrent.ConcurrentHashMap

// entiny: tells the SDK plugin which page it is on and which slots each row of it has, so it never has to read texts back
object EmbedSlots {
    private const val ITEM_SEP = '\u0002'
    private const val FIELD_SEP = '\u0001'

    private val entryNames = ConcurrentHashMap<Int, String>()
    private val schemaSlots = ConcurrentHashMap<Schema, Map<Int, String>>()

    /** the page id of a screen: the schema slug, or the class name in kebab-case for a screen that is not a schema */
    @JvmStatic
    fun pages(fragment: Any?): String = pageIds(fragment).joinToString(",")

    /** the slot names of the row with this id on the screen, comma-separated, empty when it has none */
    @JvmStatic
    fun slotsOfId(fragment: Any?, id: Int): String {
        val schema = (fragment as? SchemaPageActivity)?.schema ?: return ""
        return slotMap(schema)[id].orEmpty()
    }

    /** the pages, then one line per item: view type, id and slot names; the plugin reads the screen in one call */
    @JvmStatic
    fun describe(fragment: Any?, items: List<UItem>): String {
        val slots = (fragment as? SchemaPageActivity)?.schema?.let(::slotMap)
        val sb = StringBuilder(pages(fragment))
        for (item in items) {
            sb.append(ITEM_SEP).append(item.viewType).append(FIELD_SEP).append(item.id).append(FIELD_SEP)
            slots?.get(item.id)?.let { sb.append(it) }
        }
        return sb.toString()
    }

    private fun pageIds(fragment: Any?): List<String> {
        if (fragment is SchemaPageActivity) return listOf(fragment.schema.slug)
        return listOf(legacyId(fragment?.javaClass?.simpleName.orEmpty()))
    }

    private fun legacyId(simpleName: String): String {
        val name = simpleName.removeSuffix("Activity").removeSuffix("Settings")
        return if (name == "Inu") "root" else kebab(name)
    }

    private fun slotMap(schema: Schema): Map<Int, String> = schemaSlots.getOrPut(schema) {
        val out = HashMap<Int, String>()
        for (section in schema.sections) {
            if (section.headerRes != 0) entryName(section.headerRes)?.let { out[section.id] = it }
            for (row in section.rows) {
                val names = when (row) {
                    is Row.Toggle -> listOf(row.titleRes)
                    is Row.Choice -> listOf(row.titleRes)
                    is Row.Link -> listOf(row.titleRes)
                    is Row.Slider -> listOf(row.titleRes)
                    is Row.Group -> listOf(row.titleRes)
                    is Row.Custom -> listOf(row.titleRes)
                }.mapNotNull(::entryName)
                val extra = when (row) {
                    is Row.Group -> row.slug
                    is Row.Custom -> row.slug
                    else -> null
                }
                val all = if (extra != null) names + extra else names
                if (all.isNotEmpty()) out[row.id] = all.distinct().joinToString(",")
            }
        }
        out
    }

    private fun entryName(res: Int): String? {
        if (res == 0) return null
        return entryNames.getOrPut(res) {
            val name = runCatching { ApplicationLoader.applicationContext.resources.getResourceEntryName(res) }.getOrNull() ?: return null
            kebab(name.removePrefix("Inu"))
        }
    }

    private fun kebab(name: String): String {
        val out = StringBuilder(name.length + 4)
        for (i in name.indices) {
            val c = name[i]
            val upper = c in 'A'..'Z'
            val prevUpper = i > 0 && name[i - 1] in 'A'..'Z'
            if (upper && i > 0 && !prevUpper) out.append('-')
            out.append(c.lowercaseChar())
        }
        return out.toString()
    }
}
