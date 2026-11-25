package desu.inugram.ui.settings

import android.text.SpannableStringBuilder
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme

object NewFeatureBadges {
    // entiny: settings added in the current release cycle, keep in sync with the draft changelog (see changelog skill)
    private val SLUGS = setOf(
        "feed-channels",
        "hide-profile-stories-tab",
        "chat-list-avatar-percent",
        "voice-enhance",
        "bottom-tabs-open",
    )

    val active: Boolean get() = SLUGS.isNotEmpty()

    fun isNew(slug: String): Boolean = slug in SLUGS

    fun forSlug(slug: String, title: CharSequence): CharSequence = if (isNew(slug)) pill(title) else title

    fun pill(title: CharSequence): CharSequence =
        SpannableStringBuilder()
            .append(title)
            .append(" ")
            .append(SettingsBadge.pill(LocaleController.getString(R.string.InuNewBadge), Theme.getColor(Theme.key_featuredStickers_addButton)))
}
