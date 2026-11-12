package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

sealed class Row {
    val id: Int = InuUtils.generateId()
    var visible: (() -> Boolean)? = null

    class Toggle(
        val item: InuConfig.BoolItem,
        val titleRes: Int,
        val infoRes: Int,
        val experimental: Boolean,
        val onChange: ((SchemaPageActivity, Boolean) -> Unit)?,
    ) : Row()

    class Choice(
        val item: InuConfig.Item<Int>,
        val titleRes: Int,
        val optionRes: List<Int>,
        val values: IntArray?,
        val onChange: ((SchemaPageActivity) -> Unit)?,
    ) : Row()

    class Link(
        val slug: String,
        val titleRes: Int,
        val icon: Int,
        val value: (() -> CharSequence?)?,
        val badge: (() -> CharSequence?)?,
        val create: () -> SettingsPageActivity,
    ) : Row()

    class Group(
        val slug: String,
        val titleRes: Int,
        val create: (Int) -> ExpandableBoolGroup,
    ) : Row()

    class Custom(
        val slug: String,
        val titleRes: Int,
        val build: (SchemaPageActivity, Int) -> UItem,
        val click: (SchemaPageActivity, View) -> Unit,
        val clickAt: ((SchemaPageActivity, View, Float) -> Unit)? = null,
    ) : Row()

    class Slider(
        val item: InuConfig.Item<Int>,
        val titleRes: Int,
        val min: Float,
        val max: Float,
        val step: Float,
        val format: (Float) -> String,
        val onReleased: ((SchemaPageActivity) -> Unit)?,
        val onChanged: ((SchemaPageActivity) -> Unit)? = null,
    ) : Row()
}

class Section(val headerRes: Int, val footerRes: Int, val rows: List<Row>, val visible: (() -> Boolean)? = null) {
    val id: Int = InuUtils.generateId()
}

class Schema(val slug: String, val titleRes: Int, val iconRes: Int, val sections: List<Section>) {
    val rows: List<Row> get() = sections.flatMap { it.rows }

    private fun entry(slug: String, titleRes: Int, id: Int) =
        SearchRegistry.Entry(slug, titleRes, id, Schemas.LEGACY_ENTRY_SLUGS[slug].orEmpty())

    val page: SearchRegistry.Page by lazy {
        SearchRegistry.Page(
            slug = slug,
            titleRes = titleRes,
            iconRes = iconRes,
            factory = { SchemaPageActivity(this) },
            aliases = Schemas.LEGACY_PAGE_SLUGS[slug].orEmpty(),
            entries = rows.mapNotNull { r ->
                when (r) {
                    is Row.Toggle -> entry(r.item.key.replace('_', '-'), r.titleRes, r.id)
                    is Row.Choice -> entry(r.item.key.replace('_', '-'), r.titleRes, r.id)
                    is Row.Group -> entry(r.slug, r.titleRes, r.id)
                    is Row.Custom -> entry(r.slug, r.titleRes, r.id)
                    is Row.Slider -> entry(r.item.key.replace('_', '-'), r.titleRes, r.id)
                    is Row.Link -> if (r.slug.isEmpty()) null else entry(r.slug, r.titleRes, r.id)
                }
            },
        )
    }
}

class SectionBuilder {
    val rows = ArrayList<Row>()

    fun toggle(
        item: InuConfig.BoolItem,
        titleRes: Int,
        infoRes: Int = 0,
        experimental: Boolean = false,
        visible: (() -> Boolean)? = null,
        onChange: ((SchemaPageActivity, Boolean) -> Unit)? = null,
    ) {
        rows.add(Row.Toggle(item, titleRes, infoRes, experimental, onChange).also { it.visible = visible })
    }

    fun choice(
        item: InuConfig.Item<Int>,
        titleRes: Int,
        options: List<Int>,
        values: IntArray? = null,
        visible: (() -> Boolean)? = null,
        onChange: ((SchemaPageActivity) -> Unit)? = null,
    ) {
        rows.add(Row.Choice(item, titleRes, options, values, onChange).also { it.visible = visible })
    }

    fun link(titleRes: Int, icon: Int = 0, value: (() -> CharSequence?)? = null, badge: (() -> CharSequence?)? = null, visible: (() -> Boolean)? = null, slug: String = "", create: () -> SettingsPageActivity) {
        rows.add(Row.Link(slug, titleRes, icon, value, badge, create).also { it.visible = visible })
    }

    fun group(slug: String, titleRes: Int, create: (Int) -> ExpandableBoolGroup) {
        rows.add(Row.Group(slug, titleRes, create))
    }

    fun custom(
        slug: String,
        titleRes: Int,
        visible: (() -> Boolean)? = null,
        build: (SchemaPageActivity, Int) -> UItem,
        click: (SchemaPageActivity, View) -> Unit = { _, _ -> },
        clickAt: ((SchemaPageActivity, View, Float) -> Unit)? = null,
    ) {
        rows.add(Row.Custom(slug, titleRes, build, click, clickAt).also { it.visible = visible })
    }

    fun slider(
        item: InuConfig.Item<Int>,
        titleRes: Int,
        min: Float,
        max: Float,
        step: Float = 1f,
        format: (Float) -> String = { it.toInt().toString() },
        visible: (() -> Boolean)? = null,
        onReleased: ((SchemaPageActivity) -> Unit)? = null,
        onChanged: ((SchemaPageActivity) -> Unit)? = null,
    ) {
        rows.add(Row.Slider(item, titleRes, min, max, step, format, onReleased, onChanged).also { it.visible = visible })
    }
}

class SchemaBuilder {
    val sections = ArrayList<Section>()

    fun section(headerRes: Int = 0, footerRes: Int = 0, visible: (() -> Boolean)? = null, build: SectionBuilder.() -> Unit) {
        sections.add(Section(headerRes, footerRes, SectionBuilder().apply(build).rows, visible))
    }
}

fun schema(slug: String, titleRes: Int, iconRes: Int, build: SchemaBuilder.() -> Unit): Schema =
    Schema(slug, titleRes, iconRes, SchemaBuilder().apply(build).sections)

class SchemaPageActivity(val schema: Schema = Schemas.CHATS) : SettingsPageActivity() {
    private val groups = HashMap<Int, ExpandableBoolGroup>().also { map ->
        schema.rows.filterIsInstance<Row.Group>().forEach { map[it.id] = it.create(it.id) }
    }

    fun notifyAllAccounts(id: Int) = postNotificationForAllAccounts(id)

    override val expandableGroups: List<ExpandableBoolGroup> get() = groups.values.toList()

    override fun getTitle(): CharSequence = LocaleController.getString(schema.titleRes)

    override fun onResume() {
        super.onResume()
        refresh()
    }

    fun refresh() {
        listView?.adapter?.update(true)
    }

    fun showRestart() = showRestartBulletin()

    fun twoLineCheck(id: Int, titleRes: Int, infoRes: Int, checked: Boolean, experimental: Boolean): UItem =
        mkTwoLineCheckItem(id, titleRes, infoRes, checked, experimental)

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> peek(key: Int): T? = remembered[key] as T?

    fun splitCheck(id: Int, titleRes: Int, infoRes: Int, checked: Boolean): UItem = mkSplitCheckItem(id, titleRes, infoRes, checked)

    private val sliders = HashMap<Int, SliderCell>()
    private val remembered = HashMap<Int, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> remember(key: Int, create: () -> T): T = remembered.getOrPut(key, create) as T

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        for (section in schema.sections) {
            if (section.visible?.invoke() == false) continue
            val rows = section.rows.filter { it.visible?.invoke() != false }
            if (rows.isEmpty()) continue
            if (section.headerRes != 0) {
                items.add(UItem.asHeader(section.id, LocaleController.getString(section.headerRes)))
            }
            for (row in rows) addRow(items, row)
            items.add(UItem.asShadow(if (section.footerRes != 0) LocaleController.getString(section.footerRes) else null))
        }
    }

    private fun addRow(items: ArrayList<UItem>, row: Row) {
        when (row) {
            is Row.Toggle -> items.add(
                if (row.infoRes != 0 || row.experimental) {
                    mkTwoLineCheckItem(row.id, row.titleRes, row.infoRes, row.item.value, row.experimental)
                } else {
                    UItem.asCheck(row.id, LocaleController.getString(row.titleRes)).setChecked(row.item.value)
                }
            )

            is Row.Choice -> items.add(
                UItem.asButton(
                    row.id,
                    LocaleController.getString(row.titleRes),
                    LocaleController.getString(row.optionRes[choiceIndex(row)]),
                )
            )

            is Row.Link -> items.add(
                when {
                    row.icon != 0 -> mkSubPageButton(row.id, row.icon, LocaleController.getString(row.titleRes))
                    else -> {
                        val title = LocaleController.getString(row.titleRes)
                        val badge = row.badge?.invoke()
                        mkSubPageButton(
                            row.id,
                            if (badge.isNullOrEmpty()) title else withBadge(title, badge),
                            row.value?.invoke(),
                        )
                    }
                }
            )

            is Row.Group -> groups[row.id]?.addTo(items) { refresh() }
            is Row.Custom -> items.add(row.build(this, row.id))
            is Row.Slider -> {
                val cell = sliders.getOrPut(row.id) {
                    SliderCell(
                        context,
                        min = row.min,
                        max = row.max,
                        defaultValue = row.item.default.toFloat(),
                        initialValue = row.item.value.toFloat(),
                        step = row.step,
                        title = LocaleController.getString(row.titleRes),
                        format = row.format,
                        onChanged = {
                            row.item.value = it.toInt()
                            row.onChanged?.invoke(this)
                        },
                        onReleased = row.onReleased?.let { f -> { _: Float -> f(this) } },
                    )
                }
                cell.updateColors()
                items.add(UItem.asCustom(row.id, cell))
            }
        }
    }

    private fun choiceIndex(row: Row.Choice): Int {
        val raw = row.values?.indexOf(row.item.value) ?: row.item.value
        return raw.coerceIn(0, row.optionRes.size - 1)
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        for (group in groups.values) if (group.handleClick(item, view) { refresh() }) return
        val row = schema.rows.firstOrNull { it.id == item.id } ?: return
        when (row) {
            is Row.Toggle -> {
                val new = row.item.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                (view as? TextCheckCell)?.isChecked = new
                row.onChange?.invoke(this, new)
            }

            is Row.Choice -> RadioItemOptions.show(
                this, view,
                row.optionRes.map { LocaleController.getString(it) },
                choiceIndex(row),
            ) { which ->
                row.item.value = row.values?.get(which) ?: which
                row.onChange?.invoke(this)
            }

            is Row.Link -> presentFragment(row.create())
            is Row.Custom -> if (row.clickAt != null) row.clickAt.invoke(this, view, x) else row.click(this, view)
            is Row.Slider -> Unit
            is Row.Group -> Unit
        }
    }
}
