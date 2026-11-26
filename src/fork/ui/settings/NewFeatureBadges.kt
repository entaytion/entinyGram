package desu.inugram.ui.settings

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
}
