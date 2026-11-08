// #if PLUGINS
package desu.inugram.ui.settings

import android.content.Context
import android.view.View
import android.widget.EditText
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.market.MarketEntry
import desu.inugram.helpers.plugins.market.MarketInstaller
import desu.inugram.helpers.plugins.market.MarketSource
import desu.inugram.helpers.plugins.market.MarketStore
import desu.inugram.helpers.plugins.market.MarketTags
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import java.util.Locale

/** the plugin Market: the developer's plugins and the ones posted to the repository channels, filed under categories */
class MarketActivity(private val openSlug: String? = null, private val officialOnly: Boolean = false) : SettingsPageActivity() {
    private enum class Lens { ALL, UPDATES, INSTALLED }

    private var lens = Lens.ALL
    private var tag: String? = null
    private var query = ""
    private var pendingSlug = openSlug
    private var fullyVisible = false

    private val installer = MarketInstaller(this)
    private val rows = HashMap<String, MarketRow>()
    private val adapterIds = HashMap<String, Int>()
    private var pills: MarketPillsCell? = null
    private var banner: MarketBannerCell? = null
    private var featured: MarketFeaturedCell? = null

    override fun getTitle(): CharSequence = LocaleController.getString(if (officialOnly) R.string.InuMarketFeaturedTitle else R.string.InuMarket)

    private val onStoreChanged: () -> Unit = {
        openPendingSlug()
        listView?.adapter?.update(true)
    }

    private val onPluginsChanged: () -> Unit = {
        installer.onPluginsChanged()
        listView?.adapter?.update(true)
    }

    override fun createView(context: Context): View {
        pills = null
        banner = null
        featured = null
        return super.createView(context).also {
            val menu = simpleActionBar.createMenu()
            val search = menu.addItem(MENU_SEARCH, R.drawable.outline_header_search).setIsSearchField(true)
            search.setSearchFieldHint(LocaleController.getString(R.string.Search))
            search.setActionBarMenuItemSearchListener(object : ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                override fun onTextChanged(searchField: EditText) {
                    query = searchField.text.toString().trim().lowercase(Locale.getDefault())
                    listView?.adapter?.update(false)
                }

                override fun onSearchCollapse() {
                    query = ""
                    listView?.adapter?.update(false)
                }
            })
            val more = menu.addItem(MENU_MORE, R.drawable.ic_ab_other)
            more.addSubItem(MENU_FILTERS, R.drawable.inu_tabler_filter, str(R.string.InuMarketFilters))
            more.addSubItem(MENU_SORT, R.drawable.inu_tabler_arrows_sort, LocaleController.getString(R.string.InuPluginRepoSort))
            more.addSubItem(MENU_REFRESH, R.drawable.msg_retry, LocaleController.getString(R.string.InuMarketRefresh))
            if (!officialOnly) more.addSubItem(MENU_CHANNELS, R.drawable.msg_channel, LocaleController.getString(R.string.InuMarketOwnChannels))
            simpleActionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    when (id) {
                        -1 -> finishFragment()
                        MENU_FILTERS -> showFilters(more)
                        MENU_SORT -> showSortOptions(more)
                        MENU_REFRESH -> MarketStore.ensureLoaded(currentAccount, force = true)
                        MENU_CHANNELS -> presentFragment(PluginReposActivity())
                    }
                }
            })
        }
    }

    private fun showFilters(anchor: View) {
        val options = listOf(str(R.string.InuMarketAll), str(R.string.InuMarketUpdates), str(R.string.InuMarketInstalled))
        RadioItemOptions.show(this, anchor, options, lens.ordinal) { which ->
            select(Lens.entries[which], tag)
        }
    }

    private fun showSortOptions(anchor: View) {
        val options = listOf(
            LocaleController.getString(R.string.InuMarketSortDefault),
            LocaleController.getString(R.string.InuMarketSortInstalled),
            LocaleController.getString(R.string.InuPluginRepoSortName),
            LocaleController.getString(R.string.InuPluginRepoSortAuthor),
        )
        RadioItemOptions.show(this, anchor, options, InuConfig.MARKET_SORT.value) { which ->
            InuConfig.MARKET_SORT.value = which
            listView?.adapter?.update(true)
        }
    }

    override fun onResume() {
        super.onResume()
        PluginManager.ensureLoaded()
        MarketStore.addListener(onStoreChanged)
        PluginManager.addOnChangedListener(onPluginsChanged)
        MarketStore.ensureLoaded(currentAccount)
        openPendingSlug()
        onPluginsChanged()
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        fullyVisible = true
        openPendingSlug()
    }

    override fun onPause() {
        super.onPause()
        MarketStore.removeListener(onStoreChanged)
        PluginManager.removeOnChangedListener(onPluginsChanged)
    }

    override fun onBecomeFullyHidden() {
        fullyVisible = false
        super.onBecomeFullyHidden()
    }

    private fun openPendingSlug() {
        if (!fullyVisible || isPaused || pendingSlug == null) return
        if (MarketStore.state == MarketStore.State.LOADING || MarketStore.state == MarketStore.State.IDLE) return
        listView?.post {
            if (!fullyVisible || isPaused) return@post
            val slug = pendingSlug ?: return@post
            val entry = MarketStore.find(slug) ?: return@post
            if (presentFragment(MarketPluginActivity(entry, installer))) pendingSlug = null
        }
    }

    private fun actionOf(entry: MarketEntry): MarketAction = when {
        MarketStore.installed(entry) == null -> MarketAction.INSTALL
        MarketStore.hasUpdate(entry) -> MarketAction.UPDATE
        else -> MarketAction.INSTALLED
    }

    private fun idOf(entry: MarketEntry): Int = adapterIds.getOrPut(entry.id) { InuUtils.generateId() }

    private fun matches(entry: MarketEntry): Boolean {
        if (query.isEmpty()) return true
        val lang = LocaleController.getInstance().currentLocaleInfo?.langCode
        return entry.name.lowercase(Locale.getDefault()).contains(query) ||
            entry.manifest.author.orEmpty().lowercase(Locale.getDefault()).contains(query) ||
            entry.id.contains(query) ||
            entry.manifest.description(lang).orEmpty().lowercase(Locale.getDefault()).contains(query)
    }

    private fun str(res: Int): String = LocaleController.getString(res)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val all = if (officialOnly) MarketStore.entries.filter { it.official } else MarketStore.entries
        val updates = all.filter { MarketStore.hasUpdate(it) }
        // plugins put on the phone by hand or from elsewhere are installed too, whether or not the Market lists them
        val installed = all.filter { MarketStore.installed(it) != null } + localEntries(all).filter { !officialOnly || it.official }

        val inLens = when (lens) {
            Lens.ALL -> all
            Lens.UPDATES -> updates
            Lens.INSTALLED -> installed
        }
        val inTag = when (val id = tag) {
            null -> inLens
            OFFICIAL_TAG -> inLens.filter { it.official }
            else -> inLens.filter { id in it.tags }
        }
        val visible = MarketStore.sorted(inTag.filter { matches(it) })

        val pillsCell = pills ?: MarketPillsCell(context).also { pills = it }
        pillsCell.bind(
            listOf(
                MarketChip("all", "${str(R.string.InuMarketAll)} · ${inLens.size}", R.drawable.inu_tabler_layout_cards, tag == null) { select(lens, null) },
                MarketChip(OFFICIAL_TAG, "${str(R.string.InuMarketGroupOfficial)} · ${inLens.count { it.official }}", R.drawable.inu_tabler_brand_telegram, tag == OFFICIAL_TAG) { select(lens, OFFICIAL_TAG) },
            ) + MarketTags.ALL.map { t ->
                val count = inLens.count { t.id in it.tags }
                marketTagChip(t, count, tag == t.id) { select(lens, t.id) }
            },
        )
        items.add(UItem.asCustom(PILLS, pillsCell).apply { intValue = LayoutHelper.WRAP_CONTENT })
        items.add(UItem.asShadow(null))

        when (MarketStore.state) {
            MarketStore.State.IDLE, MarketStore.State.LOADING -> if (all.isEmpty()) {
                items.add(UItem.asCenterShadow(str(R.string.InuMarketLoading)))
                return
            }
            MarketStore.State.FAILED -> if (all.isEmpty()) {
                items.add(UItem.asButton(BUTTON_RETRY, R.drawable.msg_retry, str(R.string.InuMarketRetry)))
                items.add(UItem.asShadow(str(R.string.InuMarketFailed)))
                return
            }
            MarketStore.State.READY -> Unit
        }

        val sort = InuConfig.MARKET_SORT.value
        val plain = !officialOnly && lens == Lens.ALL && tag == null && query.isEmpty()
        if (plain && sort == 0 && updates.isNotEmpty()) {
            val bannerCell = banner ?: MarketBannerCell(context).also { banner = it }
            bannerCell.bind(
                LocaleController.formatString(R.string.InuMarketUpdatesBanner, updates.size),
                updates.joinToString(", ") { it.name },
                str(R.string.InuMarketShow),
            ) { select(Lens.UPDATES, tag) }
            items.add(UItem.asCustom(BANNER, bannerCell))
            items.add(UItem.asShadow(null))
        }
        val shelf = if (plain) visible.filter { it.official } else emptyList()
        if (shelf.isNotEmpty()) {
            val shelfCell = featured ?: MarketFeaturedCell(context).also { featured = it }
            shelfCell.bind(
                str(R.string.InuMarketFeaturedTitle),
                LocaleController.formatString(R.string.InuMarketCount, shelf.size),
                shelf,
                ::actionOf,
                { open(it) },
                { act(it) },
                { presentFragment(MarketActivity(officialOnly = true)) },
            )
            items.add(UItem.asCustom(FEATURED, shelfCell))
            items.add(UItem.asShadow(null))
        }

        val selected = tag?.let { MarketTags.get(it) }
        val about = selected?.let { "${str(it.nameRes)} · ${str(it.aboutRes)}" }
            ?: str(R.string.InuMarketGroupOfficial).takeIf { tag == OFFICIAL_TAG || officialOnly }
        if (visible.isEmpty()) {
            val empty = when {
                query.isNotEmpty() -> R.string.InuMarketEmptySearch
                lens == Lens.UPDATES -> R.string.InuMarketNoUpdates
                lens == Lens.INSTALLED -> R.string.InuMarketNoInstalled
                else -> R.string.InuMarketEmpty
            }
            items.add(UItem.asShadow(str(empty)))
            return
        }
        val listed = if (plain) visible.filter { !it.official } else visible
        val allGroups = when (sort) {
            1 -> {
                val (inst, uninst) = listed.partition { MarketStore.installed(it) != null }
                listOf(
                    R.string.InuMarketInstalled to inst,
                    R.string.InuMarketAllPlugins to uninst,
                )
            }
            2, 3 -> listOf(R.string.InuMarketAllPlugins to listed)
            else -> if (plain) {
                listOf(R.string.InuMarketGroupCommunity to listed)
            } else {
                listOf(
                    R.string.InuMarketGroupOfficial to listed.filter { it.official },
                    R.string.InuMarketGroupCommunity to listed.filter { !it.official },
                )
            }
        }
        val groups = allGroups.filter { it.second.isNotEmpty() }
        groups.forEachIndexed { index, (title, list) ->
            if (index > 0) items.add(UItem.asShadow(null))
            val single = groups.size == 1 && !plain
            val headerText = if (single && title != R.string.InuMarketInstalled) about ?: str(R.string.InuMarketAllPlugins) else str(title)
            items.add(UItem.asHeader(headerText))
            for (entry in list) {
                val row = rows.getOrPut(entry.id) { MarketRow(context) }
                row.bind(entry, actionOf(entry), { open(entry) }, { act(entry) })
                items.add(UItem.asCustom(idOf(entry), row).apply { intValue = LayoutHelper.WRAP_CONTENT })
            }
        }
        items.add(UItem.asShadow(null))
    }

    private fun localEntries(known: List<MarketEntry>): List<MarketEntry> {
        val ids = known.map { it.id }.toSet()
        return PluginManager.plugins().mapNotNull { plugin ->
            val id = plugin.manifest.id ?: return@mapNotNull null
            if (id in ids) null else MarketEntry(
                id = id,
                manifest = plugin.manifest,
                tags = MarketTags.fromHeader(plugin.manifest.raw["entiny-tags"]),
                source = MarketSource.LOCAL,
            )
        }
    }

    private fun select(newLens: Lens, newTag: String?) {
        lens = newLens
        tag = newTag
        listView?.adapter?.update(true)
        listView?.scrollToPosition(0)
    }

    private fun open(entry: MarketEntry) {
        presentFragment(MarketPluginActivity(entry, installer))
    }

    private fun act(entry: MarketEntry) {
        when (actionOf(entry)) {
            MarketAction.INSTALL, MarketAction.UPDATE -> installer.install(entry)
            MarketAction.INSTALLED -> open(entry)
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id == BUTTON_RETRY) MarketStore.ensureLoaded(currentAccount, force = true)
    }

    private companion object {
        const val MENU_SEARCH = 1
        const val MENU_MORE = 2
        const val MENU_SORT = 3
        const val MENU_REFRESH = 4
        const val MENU_CHANNELS = 5
        const val MENU_FILTERS = 6
        const val OFFICIAL_TAG = "entinygram"
        val PILLS = InuUtils.generateId()
        val BANNER = InuUtils.generateId()
        val FEATURED = InuUtils.generateId()
        val BUTTON_RETRY = InuUtils.generateId()
    }
}
// #endif
