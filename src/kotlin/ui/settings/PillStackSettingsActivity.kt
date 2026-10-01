package desu.inugram.ui.settings

import android.content.Context
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.pillstack.PillStackLayout
import desu.inugram.helpers.pillstack.PillType
import desu.inugram.helpers.pillstack.RateInstances
import desu.inugram.helpers.pillstack.WeatherLocationHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.IconBackgroundColors
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

// entiny: top-level Pill Stack settings -- master toggle, infinite scroll, visible-at-once, active/hidden pills, weather location.
class PillStackSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuPillStack)

    override fun createView(context: Context): View {
        val view = super.createView(context)
        listView.setReorderLongPressEnabled(true)
        listView.listenReorder { _, items -> applyReorder(items) }
        listView.allowReorder(true)
        return view
    }

    private fun applyReorder(items: List<UItem>) {
        val newOrder = items.mapNotNull { (it.`object` as? PillMeta)?.id }
        val currentActive = PillStackLayout.getActivePills()
        if (newOrder.size == currentActive.size && newOrder.containsAll(currentActive)) {
            PillStackLayout.saveLayout(newOrder, PillStackLayout.getHiddenPills())
        }
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_MASTER,
                R.string.InuPillStack,
                R.string.InuPillStackInfo,
                InuConfig.PILL_STACK_ENABLED.value,
                experimental = true
            )
        )
        if (!InuConfig.PILL_STACK_ENABLED.value) {
            items.add(UItem.asShadow(null))
            return
        }

        items.add(
            UItem.asCheck(
                TOGGLE_INFINITE_SCROLL,
                LocaleController.getString(R.string.InuPillStackInfiniteScrolling)
            ).setChecked(InuConfig.PILL_STACK_INFINITE_SCROLL.value)
        )
        items.add(mkTwoLineCheckItem(TOGGLE_PROXY_COUNTRY, R.string.InuPillStackProxyCountry, R.string.InuPillStackProxyCountryInfo, InuConfig.PILL_STACK_PROXY_COUNTRY.value))

        items.add(
            mkTwoLineCheckItem(
                TOGGLE_IN_HEADER,
                R.string.InuPillStackInHeader,
                R.string.InuPillStackInHeaderInfo,
                InuConfig.PILL_STACK_IN_HEADER.value,
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuPillStackVisibleCount)))
        items.add(
            UItem.asSlideView(
                arrayOf("1", "2", "3"),
                (InuConfig.PILL_STACK_VISIBLE_COUNT.value - 1).coerceIn(0, 2)
            ) { which ->
                InuConfig.PILL_STACK_VISIBLE_COUNT.value = which + 1
            }
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuPillStackActivePills)))
        val active = PillStackLayout.getActivePills()
        if (active.isEmpty()) {
            items.add(createEmptyItem(ID_ACTIVE_EMPTY))
        } else {
            adapter.reorderSectionStart()
            for (i in active.indices) {
                items.add(buildPillRow(active[i], isActive = true, divider = i != active.size - 1))
            }
            adapter.reorderSectionEnd()
        }
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuPillStackHiddenPills)))
        val hidden = PillStackLayout.getHiddenPills()
        val canAddRate = RateInstances.canAddMore()
        if (hidden.isEmpty() && !canAddRate) {
            items.add(createEmptyItem(ID_HIDDEN_EMPTY))
        } else {
            for (i in hidden.indices) {
                items.add(buildPillRow(hidden[i], isActive = false, divider = i != hidden.size - 1 || canAddRate))
            }
            if (canAddRate) {
                val addRateItem = UItem.asButton(
                    BUTTON_ADD_RATE,
                    R.drawable.msg_add,
                    LocaleController.getString(R.string.InuPillStackAddRate)
                )
                addRateItem.accent = true
                items.add(addRateItem)
            }
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuPillStackPillsSettingsInfo)))

        if (active.contains(PillType.WEATHER.id)) {
            val weatherItem = UItem.asButton(
                BUTTON_WEATHER_LOCATION,
                R.drawable.inu_tabler_cloud,
                LocaleController.getString(R.string.InuPillStackWeather),
                WeatherLocationHelper.label()
            )
            weatherItem.bind = Utilities.Callback { view ->
                val cell = view as? TextCell ?: return@Callback
                cell.imageLeft = 16
                cell.offsetFromImage = 65
                cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlackText)
            }
            items.add(weatherItem)
            items.add(UItem.asShadow(null))
        }

        val resetItem = UItem.asButton(
            BUTTON_RESET,
            R.drawable.msg_reset,
            LocaleController.getString(R.string.InuPillStackReset)
        )
        resetItem.red = true
        items.add(resetItem)
        items.add(UItem.asShadow(SHADOW_END, null))
    }

    private fun createEmptyItem(id: Int): UItem {
        val item = UItem.asButton(id, LocaleController.getString(R.string.InuPillStackListEmpty))
        item.enabled = false
        item.bind = Utilities.Callback { view ->
            val cell = view as? TextCell ?: return@Callback
            cell.setText(LocaleController.getString(R.string.InuPillStackListEmpty), false)
            cell.setColors(Theme.key_windowBackgroundWhiteGrayText, Theme.key_windowBackgroundWhiteGrayText)
        }
        return item
    }

    private fun buildPillRow(pillId: Int, isActive: Boolean, divider: Boolean): UItem {
        val meta = getPillMeta(pillId) ?: return UItem.asButton(0, "")
        val rowId = if (isActive) ACTIVE_ITEM_BASE + pillId else HIDDEN_ITEM_BASE + pillId
        val item = UItem.asButton(rowId, meta.name)
        item.`object` = meta
        item.bind = Utilities.Callback { view ->
            val cell = view as? TextCell ?: return@Callback
            cell.setTextAndValueAndColorfulIcon(
                meta.name,
                "",
                false,
                meta.iconRes,
                meta.colorTop,
                meta.colorBottom,
                divider,
            )
            cell.imageLeft = 16
            cell.offsetFromImage = 65
        }
        return item
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when {
            item.id == TOGGLE_MASTER -> {
                val new = InuConfig.PILL_STACK_ENABLED.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                listView?.adapter?.update(true)
            }

            item.id == TOGGLE_IN_HEADER -> {
                val new = InuConfig.PILL_STACK_IN_HEADER.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            item.id == TOGGLE_INFINITE_SCROLL -> {
                val new = InuConfig.PILL_STACK_INFINITE_SCROLL.toggle()
                setCellChecked(view, new)
            }

            item.id == TOGGLE_PROXY_COUNTRY -> {
                val new = InuConfig.PILL_STACK_PROXY_COUNTRY.toggle()
                setCellChecked(view, new)
            }

            item.id in ACTIVE_ITEM_BASE until HIDDEN_ITEM_BASE -> {
                val pillId = item.id - ACTIVE_ITEM_BASE
                PillStackLayout.setPillActive(pillId, false)
                listView?.adapter?.update(true)
            }

            item.id >= HIDDEN_ITEM_BASE -> {
                val pillId = item.id - HIDDEN_ITEM_BASE
                PillStackLayout.setPillActive(pillId, true)
                listView?.adapter?.update(true)
            }

            item.id == BUTTON_ADD_RATE -> presentFragment(RatePairEditActivity())

            item.id == BUTTON_WEATHER_LOCATION -> presentFragment(WeatherLocationActivity())

            item.id == BUTTON_RESET -> {
                PillStackLayout.resetLayout()
                listView?.adapter?.update(true)
                BulletinFactory.of(this).createSimpleBulletin(
                    R.raw.done,
                    LocaleController.getString(R.string.InuPillStackResetDone)
                ).show()
            }
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        val meta = item.`object` as? PillMeta
        if (meta != null && meta.isRate) {
            val opts = ItemOptions.makeOptions(this, view)
            opts.add(R.drawable.msg_edit, LocaleController.getString(R.string.Edit)) {
                presentFragment(RatePairEditActivity(meta.id))
            }
            opts.add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true) {
                val act = parentActivity ?: return@add
                org.telegram.ui.ActionBar.AlertDialog.Builder(act, resourceProvider)
                    .setTitle(LocaleController.getString(R.string.InuPillStackRemoveRate))
                    .setPositiveButton(LocaleController.getString(R.string.Delete)) { _, _ ->
                        RateInstances.remove(meta.id)
                        listView?.adapter?.update(true)
                    }
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .show()
            }
            opts.show()
            return true
        }
        return super.onLongClick(item, view, position, x, y)
    }

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    data class PillMeta(
        val id: Int,
        val name: String,
        val iconRes: Int,
        val colorTop: Int,
        val colorBottom: Int,
        val isRate: Boolean,
    )

    private fun getPillMeta(id: Int): PillMeta? {
        if (id < RateInstances.FIRST_ID) {
            val type = PillType.entries.find { it.id == id } ?: return null
            val colors = when (type) {
                PillType.CACHE -> IconBackgroundColors.BLUE_DEEP
                PillType.PROXY -> IconBackgroundColors.GREEN
                PillType.NET_SPEED -> IconBackgroundColors.BLUE
                PillType.RAM -> IconBackgroundColors.CYAN
                PillType.DC_PING -> IconBackgroundColors.GREEN
                PillType.GHOST -> IconBackgroundColors.PURPLE
                PillType.WEATHER -> IconBackgroundColors.BLUE_ALT
                PillType.CLOCK -> IconBackgroundColors.ORANGE
                PillType.BATTERY -> IconBackgroundColors.GREEN
                PillType.STORAGE -> IconBackgroundColors.BLUE_DEEP
                PillType.LAST_SEEN -> IconBackgroundColors.PURPLE
            }
            return PillMeta(
                id = id,
                name = LocaleController.getString(type.labelRes),
                iconRes = type.iconRes,
                colorTop = colors.top,
                colorBottom = colors.bottom,
                isRate = false,
            )
        } else {
            val rate = RateInstances.get(id) ?: return null
            return PillMeta(
                id = id,
                name = RateInstances.getLabel(rate).toString(),
                iconRes = RateInstances.getBaseIcon(rate.from),
                colorTop = RateInstances.getBaseColorTop(rate.from),
                colorBottom = RateInstances.getBaseColorBottom(rate.from),
                isRate = true,
            )
        }
    }

    companion object {
        private val TOGGLE_MASTER = InuUtils.generateId()
        private val TOGGLE_INFINITE_SCROLL = InuUtils.generateId()
        private val TOGGLE_PROXY_COUNTRY = InuUtils.generateId()
        private val TOGGLE_IN_HEADER = InuUtils.generateId()
        private val ID_ACTIVE_EMPTY = InuUtils.generateId()
        private val ID_HIDDEN_EMPTY = InuUtils.generateId()
        private val BUTTON_WEATHER_LOCATION = InuUtils.generateId()
        private val BUTTON_ADD_RATE = InuUtils.generateId()
        private val BUTTON_RESET = InuUtils.generateId()
        private val SHADOW_END = InuUtils.generateId()

        private const val ACTIVE_ITEM_BASE = 10_000
        private const val HIDDEN_ITEM_BASE = 50_000

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "pill-stack",
            titleRes = R.string.InuPillStack,
            iconRes = R.drawable.outline_search_1_24,
            factory = ::PillStackSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("pill-stack-master", R.string.InuPillStack, TOGGLE_MASTER),
                SearchRegistry.Entry("pill-stack-infinite-scroll", R.string.InuPillStackInfiniteScrolling, TOGGLE_INFINITE_SCROLL),
                SearchRegistry.Entry("pill-stack-proxy-country", R.string.InuPillStackProxyCountry, TOGGLE_PROXY_COUNTRY),
                SearchRegistry.Entry("pill-stack-in-header", R.string.InuPillStackInHeader, TOGGLE_IN_HEADER),
            ),
        )
    }
}
