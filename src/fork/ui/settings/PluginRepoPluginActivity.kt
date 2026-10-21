package desu.inugram.ui.settings

import android.content.Context
import android.view.View
import desu.inugram.core.plugins.PluginManifest
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.plugins.PluginDependencies
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.PluginRepoPost
import desu.inugram.helpers.plugins.PluginRepos
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

/** a plugin of a repository: what it is and asks for, installing it, and every version posted in the channel */
internal class PluginRepoPluginActivity(
    private val post: PluginRepoPost,
    private val manifest: PluginManifest,
    private val repoPosts: List<Pair<PluginRepoPost, PluginManifest>>,
    private val installer: RepoInstaller,
) : SettingsPageActivity() {
    private var hero: PluginRepoRow? = null
    private val grantRows = HashMap<Int, GrantRowView>()

    private val versions: List<Pair<PluginRepoPost, PluginManifest>> by lazy {
        val id = manifest.id ?: return@lazy listOf(post to manifest)
        repoPosts.filter { it.second.id == id && PluginManager.incompatibility(it.second) == null }.sortedByDescending { it.first.messageId }
    }

    override fun getTitle(): CharSequence = manifest.name

    private val onPluginsChanged: () -> Unit = {
        installer.onPluginsChanged()
        listView?.adapter?.update(true)
    }

    override fun onResume() {
        super.onResume()
        PluginManager.ensureLoaded()
        PluginManager.addOnChangedListener(onPluginsChanged)
        onPluginsChanged()
    }

    override fun onPause() {
        super.onPause()
        PluginManager.removeOnChangedListener(onPluginsChanged)
    }

    override fun createView(context: Context): View {
        hero = null
        grantRows.clear()
        return super.createView(context)
    }

    private fun published(of: PluginRepoPost): String = LocaleController.formatDate(of.document.date.toLong())

    private fun str(res: Int): String = LocaleController.getString(res)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val repo = post.repo
        val installed = PluginManager.findUpdateTarget(manifest)
        val version = manifest.version?.let { " v$it" }.orEmpty()

        items.add(UItem.asHeader(str(R.string.InuPluginRepoAbout)))
        val heroRow = hero ?: PluginRepoRow(context).also { hero = it }
        val meta = listOfNotNull(manifest.author, repo.title.takeIf { it.isNotEmpty() }).joinToString(" · ")
        heroRow.bind(manifest, meta, null, active = false, onUsernameClick = { }, showDescription = false) { }
        items.add(UItem.asCustom(HERO, heroRow))
        manifest.description(LocaleController.getInstance().currentLocaleInfo?.langCode)?.takeIf { it.isNotEmpty() }?.let {
            items.add(mkTwoLineEntry(DESCRIPTION, R.drawable.msg_info, str(R.string.InuPluginRepoDescription), it))
        }
        manifest.author?.takeIf { it.isNotEmpty() }?.let {
            items.add(mkTwoLineEntry(AUTHOR, R.drawable.msg_contacts, str(R.string.InuPluginRepoAuthor), it))
        }
        items.add(mkTwoLineEntry(SOURCE, R.drawable.msg_channel, str(R.string.InuPluginRepoSource), "${repo.title} (@${repo.username})"))
        manifest.id?.let {
            items.add(mkTwoLineEntry(PLUGIN_ID, R.drawable.msg_link, str(R.string.InuPluginRepoId), it))
        }
        val needs = PluginDependencies.needs(manifest)
        if (needs.isNotEmpty()) {
            val value = needs.joinToString(", ") { need -> need.minVersion?.let { "${need.id} >=$it" } ?: need.id }
            items.add(mkTwoLineEntry(DEPENDENCIES, R.drawable.msg_secret, str(R.string.InuPluginRepoDependencies), value))
        }
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(str(R.string.InuPluginRepoInstallation)))
        val date = LocaleController.formatString(R.string.InuPluginRepoPublished, published(post))
        items.add(
            when {
                installed == null -> mkTwoLineEntry(BUTTON_INSTALL, R.drawable.msg_download, str(R.string.InuPluginRepoInstall) + version, date)
                PluginRepos.isUpdate(installed, post, manifest) -> mkTwoLineEntry(BUTTON_INSTALL, R.drawable.msg_download, str(R.string.InuPluginRepoUpdate) + version, date)
                else -> mkTwoLineEntry(BUTTON_OPEN, R.drawable.msg_info, str(R.string.InuPluginsInstalled) + installed.manifest.version?.let { " v$it" }.orEmpty(), date)
            }
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(str(R.string.InuPluginsPermissions)))
        val grants = sortedGrants(manifest.grants)
        if (grants.isEmpty()) {
            items.add(UItem.asShadow(str(R.string.InuPluginsPermissionsNone)))
        } else {
            grants.forEachIndexed { i, (name, scopes) ->
                val row = grantRows.getOrPut(i) { GrantRowView(context) }
                row.bind(name, scopes, divider = i != grants.lastIndex)
                items.add(UItem.asCustom(GRANT_BASE + i, row))
            }
            items.add(UItem.asShadow(null))
        }

        items.add(UItem.asHeader(str(R.string.InuPluginRepoVersions)))
        versions.forEachIndexed { i, (versionPost, versionManifest) ->
            val state = when {
                installed != null && installed.manifest.version == versionManifest.version -> str(R.string.InuPluginsInstalled)
                i == 0 -> str(R.string.InuPluginRepoLatest)
                else -> null
            }
            val subtitle = listOfNotNull(published(versionPost), state).joinToString(" · ")
            items.add(mkTwoLineEntry(VERSION_BASE + i, R.drawable.msg_calendar, versionManifest.version?.let { "v$it" } ?: "?", subtitle))
        }
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_INSTALL -> installer.install(post, manifest, repoPosts)
            BUTTON_OPEN -> PluginManager.findUpdateTarget(manifest)?.let { presentFragment(PluginInfoActivity(it)) }
            SOURCE -> messagesController.openByUserName(post.repo.username, this, 0)
            PLUGIN_ID -> manifest.id?.let {
                AndroidUtilities.addToClipboard(it)
                BulletinFactory.of(this).createCopyBulletin(str(R.string.TextCopied)).show()
            }
            in VERSION_BASE until VERSION_BASE + versions.size -> {
                val (versionPost, versionManifest) = versions[item.id - VERSION_BASE]
                val installed = PluginManager.findUpdateTarget(versionManifest)
                if (installed != null && installed.manifest.version == versionManifest.version) presentFragment(PluginInfoActivity(installed))
                else installer.install(versionPost, versionManifest, repoPosts)
            }
        }
    }

    companion object {
        private val HERO = InuUtils.generateId()
        private val DESCRIPTION = InuUtils.generateId()
        private val AUTHOR = InuUtils.generateId()
        private val SOURCE = InuUtils.generateId()
        private val PLUGIN_ID = InuUtils.generateId()
        private val DEPENDENCIES = InuUtils.generateId()
        private val BUTTON_INSTALL = InuUtils.generateId()
        private val BUTTON_OPEN = InuUtils.generateId()
        private const val GRANT_BASE = 20000
        private const val VERSION_BASE = 30000
    }
}
