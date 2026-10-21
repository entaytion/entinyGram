package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.InuConfig
import desu.inugram.core.plugins.PluginManifest
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.plugins.PluginDependencies
import desu.inugram.helpers.plugins.PluginImportHelper
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.PluginRepo
import desu.inugram.helpers.plugins.PluginRepoPost
import desu.inugram.helpers.plugins.PluginRepos
import desu.inugram.helpers.plugins.ui.PluginManifestIcons
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.LinkSpanDrawable
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class PluginRepoActivity(private val repo: PluginRepo) : SettingsPageActivity() {
    private val posts = ArrayList<Pair<PluginRepoPost, PluginManifest>>()
    private val rows = HashMap<Int, PluginRepoRow>()
    private var nextOffsetId = 0
    private var exhausted = false
    private var loading = false
    private var failed = false

    override fun getTitle(): CharSequence = repo.title

    private val installer = RepoInstaller(this)

    private val onPluginsChanged: () -> Unit = {
        installer.onPluginsChanged()
        listView?.adapter?.update(true)
    }

    override fun onResume() {
        super.onResume()
        PluginManager.addOnChangedListener(onPluginsChanged)
        onPluginsChanged()
    }

    override fun onPause() {
        super.onPause()
        PluginManager.removeOnChangedListener(onPluginsChanged)
    }

    override fun createView(context: Context): View {
        PluginManager.ensureLoaded()
        if (posts.isEmpty() && !exhausted) loadMore()
        return super.createView(context)
    }

    private fun loadMore() {
        if (loading || exhausted) return
        loading = true
        failed = false
        PluginRepos.loadPage(currentAccount, repo, nextOffsetId) { page ->
            if (page == null) return@loadPage onLoaded(null, 0)
            PluginRepos.loadManifests(currentAccount, page.posts) { loaded -> onLoaded(loaded, page.nextOffsetId) }
        }
    }

    private fun onLoaded(loaded: List<Pair<PluginRepoPost, PluginManifest>>?, next: Int) {
        loading = false
        if (loaded == null) {
            failed = true
        } else {
            posts.addAll(loaded)
            nextOffsetId = next
            exhausted = next == 0
            // a page of other documents shows nothing new, which would leave a button that seems to do nothing
            if (loaded.isEmpty() && !exhausted) return loadMore()
        }
        listView?.adapter?.update(true)
    }

    private fun sorted(list: List<Pair<PluginRepoPost, PluginManifest>>): List<Pair<PluginRepoPost, PluginManifest>> = when (InuConfig.PLUGIN_REPO_SORT.value) {
        1 -> list.sortedBy { it.second.name.lowercase() }
        2 -> list.sortedWith(compareBy({ it.second.author.orEmpty().lowercase() }, { it.second.name.lowercase() }))
        else -> list
    }

    private fun fromDeveloper(manifest: PluginManifest): Boolean =
        manifest.id?.startsWith("entinygram.") == true || manifest.author?.contains("entinygram", ignoreCase = true) == true

    private fun sortLabel(): String = LocaleController.getString(
        when (InuConfig.PLUGIN_REPO_SORT.value) {
            1 -> R.string.InuPluginRepoSortName
            2 -> R.string.InuPluginRepoSortAuthor
            else -> R.string.InuPluginRepoSortNewest
        }
    )

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val latest = sorted(PluginRepos.pickLatest(posts))
        if (latest.isNotEmpty()) {
            items.add(UItem.asButton(BUTTON_SORT, R.drawable.msg_recent, LocaleController.getString(R.string.InuPluginRepoSort), sortLabel()))
            items.add(UItem.asShadow(null))
            val sections = if (PluginRepos.isOfficial(repo)) {
                val (mine, others) = latest.partition { fromDeveloper(it.second) }
                listOf(R.string.InuPluginRepoFromDeveloper to mine, R.string.InuPluginRepoFromOthers to others)
            } else {
                listOf(R.string.InuPlugins to latest)
            }
            sections.filter { it.second.isNotEmpty() }.forEachIndexed { index, (title, list) ->
                if (index > 0) items.add(UItem.asShadow(null))
                items.add(UItem.asHeader(LocaleController.getString(title)))
                for ((post, manifest) in list) items.add(buildRow(post, manifest))
            }
        } else if (exhausted) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuPluginRepoEmpty)))
        }
        when {
            loading -> items.add(UItem.asShadow(LocaleController.getString(R.string.Loading)))
            failed -> {
                items.add(UItem.asButton(BUTTON_MORE, R.drawable.msg_retry, LocaleController.getString(R.string.InuPluginRepoRetry)))
                items.add(UItem.asShadow(LocaleController.getString(R.string.InuPluginRepoErrorLoad)))
            }
            !exhausted -> {
                items.add(UItem.asButton(BUTTON_MORE, R.drawable.arrow_more, LocaleController.getString(R.string.ShowMore)))
                items.add(UItem.asShadow(null))
            }
            latest.isNotEmpty() -> items.add(UItem.asShadow(null))
        }
    }

    private fun buildRow(post: PluginRepoPost, manifest: PluginManifest): UItem {
        val row = rows.getOrPut(post.messageId) { PluginRepoRow(context) }
        val installed = PluginManager.findUpdateTarget(manifest)
        val update = installed != null && PluginRepos.isUpdate(installed, post, manifest)
        val status = when {
            installed == null -> null
            update -> R.string.InuPluginRepoUpdate
            else -> R.string.InuPluginsInstalled
        }
        val author = manifest.author?.let { LocaleController.formatString(R.string.InuPluginsByAuthor, it) }.orEmpty()
        row.bind(
            manifest,
            author,
            status?.let { LocaleController.getString(it) },
            active = update,
            onUsernameClick = { messagesController.openByUserName(it, this, 0) },
        ) {
            presentFragment(PluginRepoPluginActivity(post, manifest, posts, installer))
        }
        return UItem.asCustom(row).apply { id = ITEM_BASE + (post.messageId and 0xffff) }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_MORE -> loadMore()
            BUTTON_SORT -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuPluginRepoSortNewest),
                    LocaleController.getString(R.string.InuPluginRepoSortName),
                    LocaleController.getString(R.string.InuPluginRepoSortAuthor),
                ),
                InuConfig.PLUGIN_REPO_SORT.value,
            ) { which ->
                InuConfig.PLUGIN_REPO_SORT.value = which
                listView?.adapter?.update(true)
            }
        }
    }

    companion object {
        private val BUTTON_MORE = InuUtils.generateId()
        private val BUTTON_SORT = InuUtils.generateId()
        private const val ITEM_BASE = 20000
    }
}

/** downloads the post, then hands it to the regular install flow with its origin */
internal fun installRepoPost(fragment: BaseFragment, post: PluginRepoPost) {
    val ctx = fragment.parentActivity ?: return
    var cancelled = false
    val progress = AlertDialog(ctx, AlertDialog.ALERT_TYPE_SPINNER).apply {
        setOnCancelListener { cancelled = true }
        showDelayed(300)
    }
    PluginRepos.downloadSource(fragment.currentAccount, post) { source ->
        progress.dismiss()
        if (cancelled) return@downloadSource
        if (source == null) {
            BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.InuPluginRepoErrorDownload)).show()
        } else {
            PluginImportHelper.startImport(fragment, source, post.origin)
        }
    }
}

/** installs a plugin and, when it needs an SDK that is missing, the SDK from the same repository first */
internal class RepoInstaller(private val fragment: BaseFragment) {
    private var afterSdk: PluginRepoPost? = null

    fun onPluginsChanged() {
        afterSdk?.takeIf { PluginDependencies.sdkEnabled() }?.let {
            afterSdk = null
            installRepoPost(fragment, it)
        }
    }

    fun install(post: PluginRepoPost, manifest: PluginManifest, repoPosts: List<Pair<PluginRepoPost, PluginManifest>>) {
        val missing = PluginDependencies.unmetFor(manifest.raw[PluginDependencies.KEY].orEmpty(), manifest.id)
        val sdk = if (missing?.startsWith(PluginDependencies.SDK_ID) == true) {
            PluginRepos.pickLatest(repoPosts).firstOrNull { it.second.id == PluginDependencies.SDK_ID }?.first
        } else null
        if (sdk == null) return installRepoPost(fragment, post)
        afterSdk = post
        installRepoPost(fragment, sdk)
    }
}

@SuppressLint("ViewConstructor")
class PluginRepoRow(context: Context) : LinearLayout(context) {
    private val icon = BackupImageView(context).apply { setRoundRadius(AndroidUtilities.dp(8f)) }
    private val placeholder = PluginManifestIcons.createPlaceholder(context)
    private val title = RowTitle(context)
    private val textColumn = LinearLayout(context).apply { orientation = VERTICAL }
    private val pill = PluginPill(context)
    private val titleLine = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val subtitle = TextView(context).apply {
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        textSize = 13f
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
    }
    private val description = LinkSpanDrawable.LinksTextView(context).apply {
        setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText))
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        textSize = 14f
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val status = TextView(context).apply {
        textSize = 14f
        typeface = AndroidUtilities.bold()
    }

    init {
        orientation = HORIZONTAL
        background = Theme.createSelectorWithBackgroundDrawable(
            Theme.getColor(Theme.key_windowBackgroundWhite),
            Theme.getColor(Theme.key_listSelector),
        )
        val rtl = LocaleController.isRTL
        val text = textColumn
        val pillParams = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, if (rtl) 6f else 0f, 0f, if (rtl) 0f else 6f, 0f)
        val titleViews = listOf<Pair<View, LayoutParams>>(
            pill to pillParams,
            title to LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT),
        )
        for ((view, params) in if (rtl) titleViews.asReversed() else titleViews) titleLine.addView(view, params)
        text.addView(titleLine, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        text.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))
        text.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 4f, 0f, 0f))
        val views = listOf<Pair<View, LayoutParams>>(
            icon to LayoutHelper.createLinear(32, 32, Gravity.TOP, if (rtl) 12 else 20, 12, if (rtl) 20 else 12, 0),
            text to LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 10, 0, 10),
            status to LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, if (rtl) 20 else 12, 0, if (rtl) 12 else 20, 0),
        )
        for ((view, params) in if (rtl) views.asReversed() else views) addView(view, params)
    }

    fun bind(
        manifest: PluginManifest,
        meta: String,
        statusText: String?,
        active: Boolean,
        onUsernameClick: (String) -> Unit,
        showDescription: Boolean = true,
        onClick: () -> Unit,
    ) {
        PluginManifestIcons.bindIcon(icon, manifest.icon, placeholder)
        title.text = manifest.name
        pill.text = manifest.version?.let { "v$it" }
        pill.visibility = if (manifest.version == null) GONE else VISIBLE
        subtitle.text = meta
        subtitle.visibility = if (meta.isEmpty()) GONE else VISIBLE
        val text = manifest.description(LocaleController.getInstance().currentLocaleInfo?.langCode)
        description.text = text?.let { formatPluginDescription(it, onUsernameClick) }
        description.visibility = if (!showDescription || text.isNullOrEmpty()) GONE else VISIBLE
        (textColumn.layoutParams as? MarginLayoutParams)?.let {
            val end = if (statusText == null) AndroidUtilities.dp(20f) else 0
            if (it.marginEnd != end) {
                it.marginEnd = end
                textColumn.layoutParams = it
            }
        }
        status.text = statusText
        status.visibility = if (statusText == null) GONE else VISIBLE
        status.setTextColor(Theme.getColor(if (active) Theme.key_windowBackgroundWhiteBlueText2 else Theme.key_windowBackgroundWhiteGrayText))
        setOnClickListener { onClick() }
    }
}
