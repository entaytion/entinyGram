package desu.inugram.helpers.plugins.market

import desu.inugram.InuConfig
import desu.inugram.helpers.plugins.Plugin
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.PluginRepos
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.Utilities
import java.text.Collator

/**
 * What the Market lists: the entinyGram catalog plus the latest plugin of every post of the repositories (channels).
 * The catalog wins over a channel's post of the same id. Ui thread only, listeners included
 */
object MarketStore {
    enum class State { IDLE, LOADING, READY, FAILED }

    var state = State.IDLE
        private set
    var entries: List<MarketEntry> = emptyList()
        private set

    private val listeners = ArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun find(slug: String): MarketEntry? = entries.firstOrNull { it.slug.equals(slug, ignoreCase = true) || it.id.equals(slug, ignoreCase = true) }

    /** loads once; [force] reads everything again */
    fun ensureLoaded(account: Int, force: Boolean = false) {
        if (state == State.LOADING || (state == State.READY && !force)) return
        state = State.LOADING
        notifyListeners()
        var catalog: List<MarketEntry>? = null
        var posts: List<MarketEntry>? = null
        var remaining = 2
        fun done() {
            if (--remaining > 0) return
            val official = catalog.orEmpty()
            val known = official.map { it.id }.toSet()
            val merged = official.filter { PluginManager.incompatibility(it.manifest) == null } +
                posts.orEmpty().filter { it.id !in known }
            entries = merged
            state = if (catalog == null && posts == null) State.FAILED else State.READY
            notifyListeners()
        }
        Utilities.globalQueue.postRunnable {
            val loaded = MarketCatalog.load()
            AndroidUtilities.runOnUIThread {
                catalog = loaded
                done()
            }
        }
        loadChannels(account) {
            posts = it
            done()
        }
    }

    private fun loadChannels(account: Int, callback: (List<MarketEntry>?) -> Unit) {
        val repos = PluginRepos.getRepos()
        if (repos.isEmpty()) return callback(emptyList())
        val found = ArrayList<MarketEntry>()
        var remaining = repos.size
        var anyOk = false
        for (repo in repos) {
            PluginRepos.loadPage(account, repo, 0) { page ->
                if (page == null) {
                    if (--remaining == 0) callback(if (anyOk) found else null)
                    return@loadPage
                }
                PluginRepos.loadManifests(account, page.posts) { loaded ->
                    if (loaded != null) {
                        anyOk = true
                        for ((post, manifest) in PluginRepos.pickLatest(loaded)) {
                            val id = manifest.id ?: continue
                            val tags = (MarketTags.fromHeader(manifest.raw["entiny-tags"]) + MarketTags.fromText(post.text))
                                .distinct().take(MarketTags.MAX_PER_PLUGIN)
                            found.add(MarketEntry(id = id, manifest = manifest, tags = tags, source = MarketSource.CHANNEL, post = post))
                        }
                    }
                    if (--remaining == 0) callback(if (anyOk) found else null)
                }
            }
        }
    }

    private fun notifyListeners() {
        for (listener in ArrayList(listeners)) listener()
    }

    fun installed(entry: MarketEntry): Plugin? = PluginManager.findUpdateTarget(entry.manifest)

    fun sorted(entries: List<MarketEntry>): List<MarketEntry> {
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
        val byName = compareBy(collator) { entry: MarketEntry -> entry.name }
        return when (InuConfig.MARKET_SORT.value) {
            1 -> entries.sortedWith(compareBy<MarketEntry> { installed(it) == null }.then(byName))
            2 -> entries.sortedWith(byName)
            3 -> entries.sortedWith(
                compareBy<MarketEntry> { it.manifest.author.isNullOrBlank() }
                    .thenBy(collator) { it.manifest.author.orEmpty().trim().removePrefix("@").trim() }
                    .then(byName)
            )
            else -> entries
        }
    }

    fun hasUpdate(entry: MarketEntry): Boolean {
        val plugin = installed(entry) ?: return false
        return when (entry.source) {
            MarketSource.OFFICIAL -> compareVersions(entry.manifest.version, plugin.manifest.version) > 0
            MarketSource.CHANNEL -> entry.post?.let { PluginRepos.isUpdate(plugin, it, entry.manifest) } == true
            MarketSource.LOCAL -> false
        }
    }

    /** > 0 when [a] is newer; `1.10` is above `1.9`, anything after a dash counts as lower */
    fun compareVersions(a: String?, b: String?): Int {
        fun parts(v: String?) = (v ?: "").substringBefore('-').split('.', '+').map { it.toIntOrNull() ?: 0 }
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        val prereleaseA = a?.contains('-') == true
        val prereleaseB = b?.contains('-') == true
        return if (prereleaseA == prereleaseB) 0 else if (prereleaseA) -1 else 1
    }
}
