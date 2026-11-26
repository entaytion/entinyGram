// #if PLUGINS
package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.plugins.PluginManager
import desu.inugram.helpers.plugins.market.MarketEntry
import desu.inugram.helpers.plugins.market.installMarketEntry
import desu.inugram.helpers.plugins.market.MarketStore
import desu.inugram.helpers.plugins.market.MarketTags
import desu.inugram.helpers.plugins.ui.PluginManifestIcons
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ShareAlert
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.messenger.browser.Browser

internal class MarketPluginActivity(
    private val entry: MarketEntry,
) : SettingsPageActivity() {
    private var hero: MarketHeroCell? = null
    private val grantRows = HashMap<Int, GrantRowView>()

    override fun getTitle(): CharSequence = entry.name

    private val onPluginsChanged: () -> Unit = {
        listView?.adapter?.update(true)
    }

    override fun createView(context: Context): View {
        hero = null
        grantRows.clear()
        return super.createView(context).also {
            val menu = simpleActionBar.createMenu()
            menu.addItem(MENU_SHARE, R.drawable.msg_share)
            simpleActionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    when (id) {
                        -1 -> finishFragment()
                        MENU_SHARE -> {
                            val ctx = parentActivity ?: return
                            val link = link()
                            showDialog(ShareAlert(ctx, null, link, false, link, false, resourceProvider))
                        }
                    }
                }
            })
        }
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

    private fun link(): String = "tg://entinyPlugins/${Uri.encode(entry.slug)}"

    private fun str(res: Int): String = LocaleController.getString(res)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val manifest = entry.manifest
        val installed = MarketStore.installed(entry)
        val action = MarketStore.actionOf(entry)
        val lang = LocaleController.getInstance().currentLocaleInfo?.langCode

        val heroCell = hero ?: MarketHeroCell(context).also { hero = it }
        heroCell.bind(entry, action) {
            if (action == MarketAction.INSTALLED) installed?.let { presentFragment(PluginInfoActivity(it)) }
            else installMarketEntry(this, entry)
        }
        items.add(UItem.asCustom(HERO, heroCell))
        items.add(UItem.asShadow(null))

        manifest.description(lang)?.takeIf { it.isNotEmpty() }?.let {
            items.add(UItem.asHeader(str(R.string.InuPluginRepoAbout)))
            items.add(mkTwoLineEntry(DESCRIPTION, R.drawable.msg_info, str(R.string.InuPluginRepoDescription), it))
            items.add(UItem.asShadow(null))
        }

        entry.changelog?.let { notes ->
            items.add(UItem.asHeader(LocaleController.formatString(R.string.InuMarketWhatsNew, manifest.version.orEmpty())))
            items.add(mkTwoLineEntry(CHANGELOG, R.drawable.msg_calendar, str(R.string.InuMarketWhatsNewTitle), notes.lineSequence().joinToString("\n") { it.trim().removePrefix("- ").let { l -> if (l.isEmpty()) l else "• $l" } }))
            items.add(UItem.asShadow(null))
        }

        items.add(UItem.asHeader(str(R.string.InuMarketDetails)))
        manifest.version?.let {
            val size = entry.file?.size?.takeIf { s -> s > 0 } ?: entry.post?.document?.size?.takeIf { s -> s > 0 }
            val value = listOfNotNull("v$it", size?.let { s -> AndroidUtilities.formatFileSize(s) }).joinToString(" · ")
            items.add(mkTwoLineEntry(VERSION, R.drawable.msg_calendar, str(R.string.InuMarketVersion), value))
        }
        manifest.author?.takeIf { it.isNotEmpty() }?.let {
            items.add(mkTwoLineEntry(AUTHOR, R.drawable.msg_contacts, str(R.string.InuPluginRepoAuthor), it))
        }
        val tags = entry.tags.mapNotNull { MarketTags.get(it) }
        if (tags.isNotEmpty()) {
            items.add(mkTwoLineEntry(TAGS, R.drawable.entiny_layers, str(R.string.InuMarketTags), tags.joinToString(" · ") { str(it.nameRes) }))
        }
        val source = if (entry.official) str(R.string.InuMarketSourceOfficial) else entry.post?.repo?.let { "${it.title} (@${it.username})" }.orEmpty()
        if (source.isNotEmpty()) items.add(mkTwoLineEntry(SOURCE, R.drawable.msg_channel, str(R.string.InuPluginRepoSource), source))
        items.add(mkTwoLineEntry(PLUGIN_ID, R.drawable.msg_link, str(R.string.InuPluginRepoId), entry.id))
        addPluginDependencies(items, manifest)
        items.add(UItem.asShadow(null))

        addPluginPermissions(items, context, manifest, grantRows)

        entry.post?.let {
            items.add(mkTwoLineEntry(DISCUSSION, R.drawable.msg_discussion, str(R.string.InuMarketDiscussion), str(R.string.InuMarketDiscussionInfo)))
            items.add(UItem.asShadow(null))
        }
        if (installed != null) {
            items.add(mkTwoLineEntry(OPEN, R.drawable.msg_customize, str(R.string.InuMarketOpenSettings), str(R.string.InuMarketOpenSettingsInfo)))
            items.add(UItem.asShadow(null))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            OPEN -> MarketStore.installed(entry)?.let { presentFragment(PluginInfoActivity(it)) }
            DISCUSSION -> entry.post?.let { post ->
                parentActivity?.let { Browser.openUrl(it, "https://t.me/${post.repo.username}/${post.messageId}") }
            }
            SOURCE -> entry.post?.repo?.let { messagesController.openByUserName(it.username, this, 0) }
            PLUGIN_ID -> {
                AndroidUtilities.addToClipboard(entry.id)
                BulletinFactory.of(this).createCopyBulletin(str(R.string.TextCopied)).show()
            }
        }
    }

    private companion object {
        const val MENU_SHARE = 1
        val HERO = InuUtils.generateId()
        val DESCRIPTION = InuUtils.generateId()
        val CHANGELOG = InuUtils.generateId()
        val VERSION = InuUtils.generateId()
        val AUTHOR = InuUtils.generateId()
        val TAGS = InuUtils.generateId()
        val SOURCE = InuUtils.generateId()
        val PLUGIN_ID = InuUtils.generateId()
        val DISCUSSION = InuUtils.generateId()
        val OPEN = InuUtils.generateId()
    }
}

@SuppressLint("ViewConstructor")
internal class MarketHeroCell(context: Context) : LinearLayout(context) {
    private val icon = BackupImageView(context).apply { setRoundRadius(AndroidUtilities.dp(20f)) }
    private val placeholder = PluginManifestIcons.createPlaceholder(context)
    private val name = TextView(context).apply {
        textSize = 22f
        typeface = AndroidUtilities.bold()
        gravity = Gravity.CENTER
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
    }
    private val meta = TextView(context).apply {
        textSize = 14f
        gravity = Gravity.CENTER
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
    }
    private val tags = MarketTagPills(context)
    private val button = TextView(context)

    init {
        orientation = VERTICAL
        isClickable = true
        gravity = Gravity.CENTER_HORIZONTAL
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        setPadding(AndroidUtilities.dp(24f), AndroidUtilities.dp(22f), AndroidUtilities.dp(24f), AndroidUtilities.dp(20f))
        addView(icon, LayoutHelper.createLinear(88, 88, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 0))
        addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 14f, 0f, 0f))
        addView(meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 4f, 0f, 0f))
        addView(tags, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 4f, 0f, 0f))
        addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 18f, 0f, 0f))
    }

    fun bind(entry: MarketEntry, action: MarketAction, onClick: () -> Unit) {
        PluginManifestIcons.bindIcon(icon, entry.manifest.icon, placeholder)
        name.text = entry.name
        meta.text = listOfNotNull(entry.manifest.author?.takeIf { it.isNotEmpty() }, entry.manifest.version?.let { "v$it" }).joinToString(" · ")
        tags.bind(entry.tags)
        styleMarketAction(button, action, compact = false)
        button.textSize = 15f
        button.setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(12f), AndroidUtilities.dp(16f), AndroidUtilities.dp(12f))
        button.setOnClickListener { onClick() }
    }
}
// #endif
