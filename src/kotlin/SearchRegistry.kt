package desu.inugram

import android.content.Intent
import desu.inugram.helpers.security.ParanoiaHelper
import desu.inugram.ui.settings.AdditionalSettingsActivity
import desu.inugram.ui.settings.AiSettingsActivity
import desu.inugram.ui.settings.AnnoyancesSettingsActivity
import desu.inugram.ui.settings.AppearanceSettingsActivity
import desu.inugram.ui.settings.BehaviorSettingsActivity
import desu.inugram.ui.settings.CategoryChatsSettingsActivity
import desu.inugram.ui.settings.DialogsSettingsActivity
import desu.inugram.ui.settings.InuSettingsActivity
import desu.inugram.ui.settings.MessagesSettingsActivity
import desu.inugram.ui.settings.PrivacySecurityActivity
import desu.inugram.ui.settings.RegexFilterSettingsActivity
import desu.inugram.ui.settings.SettingsPageActivity
import desu.inugram.ui.settings.TosSettingsActivity
import desu.inugram.ui.settings.TranslatorSettingsActivity
import desu.inugram.ui.settings.UserProfileSettingsActivity
import desu.inugram.ui.settings.fonts.FontStackActivity
import desu.inugram.ui.settings.fonts.FontsSettingsActivity
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.LaunchActivity
import org.telegram.ui.ProfileActivity

object SearchRegistry {
    /**
     * @param itemId optional — runtime [org.telegram.ui.Components.UItem.id] to highlight on open.
     */
    data class Entry(val slug: String, val titleRes: Int, val itemId: Int = -1)

    data class Page(
        val slug: String,
        val titleRes: Int,
        val iconRes: Int,
        val factory: () -> SettingsPageActivity,
        val entries: List<Entry> = emptyList(),
    )

    private val pages: List<Page> by lazy {
        listOf(
            AdditionalSettingsActivity.PAGE,
            InuSettingsActivity.PAGE,
            AppearanceSettingsActivity.PAGE,
            FontsSettingsActivity.PAGE,
            FontStackActivity.PAGE,
            CategoryChatsSettingsActivity.PAGE,
            MessagesSettingsActivity.PAGE,
            AiSettingsActivity.PAGE,
            DialogsSettingsActivity.PAGE,
            UserProfileSettingsActivity.PAGE,
            AnnoyancesSettingsActivity.PAGE,
            BehaviorSettingsActivity.PAGE,
            TosSettingsActivity.PAGE,
            RegexFilterSettingsActivity.PAGE,
            TranslatorSettingsActivity.PAGE,
            PrivacySecurityActivity.PAGE,
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
                for (entry in page.entries) add(entry.slug, Target(page, entry))
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
        // canonical: `tg://entinySettings/<slug>` (host=entinySettings) or `tg:entinySettings/<slug>` (opaque)
        // legacy: `tg://settings/{inu,entiny}/<slug>` (host=settings) or `tg:settings/{inu,entiny}/<slug>` (opaque)
        val segs = when (uri.host) {
            "entinySettings", "settings" -> uri.pathSegments
            null -> uri.schemeSpecificPart?.removePrefix("//")?.let { ssp ->
                when {
                    ssp.startsWith("entinySettings/") -> ssp.removePrefix("entinySettings/").split('/')
                    ssp.startsWith("settings/") -> ssp.removePrefix("settings/").split('/')
                    else -> null
                }
            } ?: return false

            else -> return false
        }
        val legacy = when (segs.size) {
            1 -> false
            2 -> if (segs[0] == "inu" || segs[0] == "entiny") true else return false
            else -> return false
        }
        val target = targetBySlug[segs.last()] ?: return false
        if (legacy) {
            val fragment = activity.actionBarLayout?.lastFragment ?: return false
            org.telegram.ui.Components.BulletinFactory.of(fragment)
                .createSimpleBulletin(
                    R.raw.chats_infotip,
                    LocaleController.getString(R.string.InuLegacySettingsLink)
                )
                .show()
            return true
        }
        val fragment = target.page.factory()
        target.entry?.let { fragment.withHighlight(it.itemId) }
        activity.actionBarLayout.presentFragment(fragment)
        return true
    }

    // stable guid from slug, high bit set to avoid stock guid range (<1000).
    private fun guidFor(slug: String): Int = 0x10000000 or (slug.hashCode() and 0x00FFFFFF)
}
