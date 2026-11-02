package desu.inugram.helpers.plugins.market

import desu.inugram.core.plugins.PluginManifest
import desu.inugram.helpers.plugins.PluginRepoPost
import org.telegram.messenger.R

enum class MarketSource { OFFICIAL, CHANNEL, LOCAL }

class MarketFile(val url: String, val size: Long, val sha256: String)

/** one plugin in the Market, whether it comes from the entinyGram catalog or from a post in a channel */
class MarketEntry(
    val id: String,
    val manifest: PluginManifest,
    val tags: List<String>,
    val source: MarketSource,
    val changelog: String? = null,
    val file: MarketFile? = null,
    val post: PluginRepoPost? = null,
) {
    val slug: String = id.removePrefix(OFFICIAL_PREFIX)
    val name: String get() = manifest.name
    /** the developer's own: the catalog, or a channel post of a plugin in the developer's namespace */
    val official: Boolean get() = source == MarketSource.OFFICIAL || id.startsWith(OFFICIAL_PREFIX)

    companion object {
        const val OFFICIAL_PREFIX = "entinygram."
    }
}

class MarketTag(val id: String, val iconRes: Int, val nameRes: Int, val aboutRes: Int)

object MarketTags {
    val ALL = listOf(
        MarketTag("privacy", R.drawable.inu_tabler_shield_check, R.string.InuMarketTagPrivacy, R.string.InuMarketTagPrivacyAbout),
        MarketTag("chat", R.drawable.inu_tabler_message_dots, R.string.InuMarketTagChat, R.string.InuMarketTagChatAbout),
        MarketTag("media", R.drawable.inu_tabler_camera_bolt, R.string.InuMarketTagMedia, R.string.InuMarketTagMediaAbout),
        MarketTag("ui", R.drawable.inu_tabler_app_window, R.string.InuMarketTagUi, R.string.InuMarketTagUiAbout),
        MarketTag("calls", R.drawable.inu_tabler_microphone, R.string.InuMarketTagCalls, R.string.InuMarketTagCallsAbout),
        MarketTag("ai", R.drawable.inu_tabler_sparkles, R.string.InuMarketTagAi, R.string.InuMarketTagAiAbout),
        MarketTag("tools", R.drawable.inu_tabler_stack_2, R.string.InuMarketTagTools, R.string.InuMarketTagToolsAbout),
        MarketTag("integrations", R.drawable.inu_tabler_world, R.string.InuMarketTagIntegrations, R.string.InuMarketTagIntegrationsAbout),
        MarketTag("fun", R.drawable.inu_tabler_mood_smile, R.string.InuMarketTagFun, R.string.InuMarketTagFunAbout),
        MarketTag("dev", R.drawable.inu_tabler_code, R.string.InuMarketTagDev, R.string.InuMarketTagDevAbout),
    )

    const val MAX_PER_PLUGIN = 3

    private val byId = ALL.associateBy { it.id }

    // other spellings of the tags as people write them in hashtags; mirrors ALIASES of sdk/entiny/src/tags.ts
    private val aliases: Map<String, String> = mapOf(
        "приватность" to "privacy", "приватність" to "privacy", "ghost" to "privacy",
        "чаты" to "chat", "чати" to "chat", "chats" to "chat", "messages" to "chat", "folders" to "chat", "папки" to "chat",
        "notifications" to "chat", "уведомления" to "chat", "сповіщення" to "chat",
        "медиа" to "media", "медіа" to "media", "music" to "media", "video" to "media", "audio" to "media",
        "внешний_вид" to "ui", "вигляд" to "ui", "оформление" to "ui", "оформлення" to "ui", "themes" to "ui", "theme" to "ui",
        "design" to "ui", "look" to "ui", "animations" to "ui", "анимации" to "ui", "анімації" to "ui",
        "звонки" to "calls", "дзвінки" to "calls", "call" to "calls",
        "translate" to "ai", "translation" to "ai", "translator" to "ai", "перевод" to "ai", "переклад" to "ai",
        "ии" to "ai", "ші" to "ai", "llm" to "ai", "gpt" to "ai",
        "инструменты" to "tools", "інструменти" to "tools", "utilities" to "tools", "utils" to "tools", "tool" to "tools",
        "search" to "tools", "поиск" to "tools", "пошук" to "tools", "automation" to "tools", "автоматизация" to "tools",
        "интеграции" to "integrations", "інтеграції" to "integrations", "integration" to "integrations", "maps" to "integrations",
        "развлечения" to "fun", "розваги" to "fun", "games" to "fun", "game" to "fun",
        "разработка" to "dev", "розробка" to "dev", "developer" to "dev", "developers" to "dev", "sdk" to "dev",
    )

    private val hashtag = Regex("#([\\p{L}\\p{N}_]+)")

    fun get(id: String): MarketTag? = byId[id]

    /** the tag a hashtag word stands for, or null */
    fun of(word: String): String? {
        val key = word.removePrefix("#").lowercase()
        return if (byId.containsKey(key)) key else aliases[key]
    }

    /** the tags of the hashtags in a post, in the order they appear, without repeats */
    fun fromText(text: String): List<String> =
        hashtag.findAll(text).mapNotNull { of(it.groupValues[1]) }.distinct().take(MAX_PER_PLUGIN).toList()

    /** the tags a plugin names in its header, `@entiny-tags media calls` */
    fun fromHeader(raw: List<String>?): List<String> =
        raw.orEmpty().flatMap { it.split(Regex("\\s+")) }.mapNotNull { of(it) }.distinct().take(MAX_PER_PLUGIN)
}
