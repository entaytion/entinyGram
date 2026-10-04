package desu.inugram

import android.content.Intent
import desu.inugram.helpers.security.ParanoiaHelper
import desu.inugram.ui.settings.AdditionalSettingsActivity
import desu.inugram.ui.settings.AiEditorSettingsActivity
import desu.inugram.ui.settings.AiSettingsActivity
import desu.inugram.ui.settings.AiSummarySettingsActivity
import desu.inugram.ui.settings.AiVoiceSettingsActivity
import desu.inugram.ui.settings.AnnoyancesSettingsActivity
import desu.inugram.ui.settings.AntiCensorshipSettingsActivity
import desu.inugram.ui.settings.AntiDeletionSettingsActivity
import desu.inugram.ui.settings.AppearanceSettingsActivity
import desu.inugram.ui.settings.BackupSettingsActivity
import desu.inugram.ui.settings.BehaviorSettingsActivity
import desu.inugram.ui.settings.CacheManagementSettingsActivity
import desu.inugram.ui.settings.CategoryChatsSettingsActivity
import desu.inugram.ui.settings.ChatHeaderSettingsActivity
import desu.inugram.ui.settings.DatacenterStatusActivity
import desu.inugram.ui.settings.DialogsSettingsActivity
import desu.inugram.helpers.feed.FeedHelper
import desu.inugram.ui.settings.FeedExcludedChannelsSettingsActivity
import desu.inugram.ui.settings.GhostModeSettingsActivity
import desu.inugram.ui.settings.IconPacksSettingsActivity
import desu.inugram.ui.settings.InuSettingsActivity
import desu.inugram.ui.settings.IosStyleSettingsActivity
import desu.inugram.ui.settings.MessageDesignSettingsActivity
import desu.inugram.ui.settings.DrawerSettingsActivity
import desu.inugram.ui.settings.DrawerMenuOrderActivity
import desu.inugram.ui.settings.MenusSettingsActivity
import desu.inugram.ui.settings.MessagesSettingsActivity
import desu.inugram.ui.settings.PillStackSettingsActivity
import desu.inugram.ui.settings.RecentChatsSettingsActivity
import desu.inugram.ui.settings.ParanoiaActivity
import desu.inugram.ui.settings.PrivacySecurityActivity
import desu.inugram.ui.settings.RegexFilterSettingsActivity
import desu.inugram.ui.settings.SettingsPageActivity
import desu.inugram.ui.settings.StalkerPackSettingsActivity
import desu.inugram.ui.settings.TosSettingsActivity
import desu.inugram.ui.settings.TranslatorSettingsActivity
import desu.inugram.ui.settings.WeatherLocationActivity
import desu.inugram.ui.settings.fonts.FontStackActivity
import desu.inugram.ui.settings.fonts.FontsSettingsActivity
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.LaunchActivity
import org.telegram.ui.ProfileActivity

object SearchRegistry {
    // entiny: aliases are retired slugs that still resolve to this target, so old tg://entinySettings links keep working
    data class Entry(val slug: String, val titleRes: Int, val itemId: Int = -1, val aliases: List<String> = emptyList())

    data class Page(
        val slug: String,
        val titleRes: Int,
        val iconRes: Int,
        val factory: () -> SettingsPageActivity,
        val entries: List<Entry> = emptyList(),
        val aliases: List<String> = emptyList(),
    )

    private val pages: List<Page> by lazy {
        listOf(
            AdditionalSettingsActivity.PAGE,
            desu.inugram.ui.spoof.DeviceSpoofSettingsActivity.PAGE,
            CacheManagementSettingsActivity.PAGE,
            InuSettingsActivity.PAGE,
            AppearanceSettingsActivity.PAGE,
            ChatHeaderSettingsActivity.PAGE,
            IosStyleSettingsActivity.PAGE,
            MessageDesignSettingsActivity.PAGE,
            DrawerSettingsActivity.PAGE,
            DrawerMenuOrderActivity.PAGE,
            MenusSettingsActivity.PAGE,
            IconPacksSettingsActivity.PAGE,
            FontsSettingsActivity.PAGE,
            FontStackActivity.PAGE,
            CategoryChatsSettingsActivity.PAGE,
            MessagesSettingsActivity.PAGE,
            AiSettingsActivity.PAGE,
            AiVoiceSettingsActivity.PAGE,
            AiEditorSettingsActivity.PAGE,
            AiSummarySettingsActivity.PAGE,
            AnnoyancesSettingsActivity.PAGE,
            BehaviorSettingsActivity.PAGE,
            TosSettingsActivity.PAGE,
            GhostModeSettingsActivity.PAGE,
            PillStackSettingsActivity.PAGE,
            RecentChatsSettingsActivity.PAGE,
            WeatherLocationActivity.PAGE,
            AntiDeletionSettingsActivity.PAGE,
            StalkerPackSettingsActivity.PAGE,
            RegexFilterSettingsActivity.PAGE,
            TranslatorSettingsActivity.PAGE,
            PrivacySecurityActivity.PAGE,
            ParanoiaActivity.PAGE,
            AntiCensorshipSettingsActivity.PAGE,
            DatacenterStatusActivity.PAGE,
            BackupSettingsActivity.PAGE,
            FeedExcludedChannelsSettingsActivity.PAGE,
        )
    }

    private data class Target(val page: Page, val entry: Entry?)

    private val targetBySlug: Map<String, Target> by lazy {
        buildMap {
            for (page in pages) {
                fun add(slug: String, target: Target) {
                    require(put(slug, target) == null) { "SearchRegistry: duplicate slug '$slug'" }
                }
                add(page.slug, Target(page, null))
                for (alias in page.aliases) add(alias, Target(page, null))
                for (entry in page.entries) {
                    add(entry.slug, Target(page, entry))
                    for (alias in entry.aliases) add(alias, Target(page, entry))
                }
            }
        }
    }

    private val slugByItemId: Map<Int, String> by lazy {
        buildMap {
            for (page in pages) for (entry in page.entries) {
                if (entry.itemId != -1) putIfAbsent(entry.itemId, entry.slug)
            }
        }
    }

    fun deepLinkForItemId(itemId: Int): String? =
        slugByItemId[itemId]?.let { "tg://entinySettings/$it" }

    @JvmStatic
    fun extendSearchArray(
        stock: Array<ProfileActivity.SearchAdapter.SearchResult>,
        f: BaseFragment,
    ): Array<ProfileActivity.SearchAdapter.SearchResult> {
        if (ParanoiaHelper.shouldHideSettings()) return stock
        val extra = ArrayList<ProfileActivity.SearchAdapter.SearchResult>()
        for (page in pages) {
            if (page === FeedExcludedChannelsSettingsActivity.PAGE && !FeedHelper.isEnabled()) continue
            val pageTitle = LocaleController.getString(page.titleRes)
            val parent = "${LocaleController.getString(R.string.InuSettings)} → $pageTitle"
            extra.add(
                ProfileActivity.SearchAdapter.SearchResult(
                    guidFor(page.slug),
                    pageTitle,
                    LocaleController.getString(R.string.InuSettings),
                    page.iconRes,
                ) { f.presentFragment(page.factory()) }.withLink("tg://entinySettings/${page.slug}")
            )
            for (entry in page.entries) {
                val title = LocaleController.getString(entry.titleRes)
                extra.add(
                    ProfileActivity.SearchAdapter.SearchResult(
                        guidFor(entry.slug),
                        title,
                        parent,
                        page.iconRes,
                    ) {
                        f.presentFragment(page.factory().withHighlight(entry.itemId))
                    }.withLink("tg://entinySettings/${entry.slug}")
                )
            }
        }
        return stock + extra.toTypedArray()
    }

    @JvmStatic
    fun tryHandleDeepLink(activity: LaunchActivity, intent: Intent?): Boolean {
        if (ParanoiaHelper.shouldHideSettings()) return false
        val uri = intent?.data ?: return false
        if (uri.scheme != "tg") return false
        // entiny: match host and legacy {inu,entiny} segments case-insensitively while preserving trailing slug
        val segs = when {
            uri.host.equals("entinySettings", ignoreCase = true) || uri.host.equals("settings", ignoreCase = true) -> uri.pathSegments
            uri.host == null -> uri.schemeSpecificPart?.removePrefix("//")?.let { ssp ->
                when {
                    ssp.startsWith("entinySettings/", ignoreCase = true) -> ssp.substring("entinySettings/".length).split('/')
                    ssp.startsWith("settings/", ignoreCase = true) -> ssp.substring("settings/".length).split('/')
                    else -> null
                }
            } ?: return false

            else -> return false
        }
        when (segs.size) {
            1 -> {}
            2 -> if (!segs[0].equals("inu", ignoreCase = true) && !segs[0].equals("entiny", ignoreCase = true)) return false
            else -> return false
        }
        val target = targetBySlug[segs.last()] ?: return false
        val fragment = target.page.factory()
        target.entry?.let { fragment.withHighlight(it.itemId) }
        activity.actionBarLayout.presentFragment(fragment)
        return true
    }

    // entiny: set high bit to avoid collision with stock guid range (<1000)
    private fun guidFor(slug: String): Int = 0x10000000 or (slug.hashCode() and 0x00FFFFFF)
}
