package desu.inugram.helpers.badges

import android.text.TextUtils
import desu.inugram.InuConfig
import desu.inugram.helpers.InuDatabaseHelper
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

object BadgeRegistry {

    private val LOCALES = listOf("en", "uk", "ru", "tr", "ja", "zh")

    data class Badge(
        val slug: String,
        val titles: Map<String, String>,
        val descriptions: Map<String, String>,
        val icon: String,
        val emojiId: Long,
    )

    private const val ENDPOINT = "https://entaytion.is-a.dev/api/entinygram/badges"
    private const val TTL_MS = 60L * 60 * 1000 // 1 hour, matches Vercel edge max-age=3600

    private const val KV_MANIFEST = "badges:manifest"
    private const val KV_ETAG = "badges:etag"
    private const val KV_FETCHED_AT = "badges:fetched_at"

    // entiny: retain all historical badge emoji ids so revoked badges can still be recognized and cleared
    private const val KV_OWNED_IDS = "badges:owned_ids"

    @Volatile
    private var holders: Map<Long, Badge> = bundledHolders()

    @Volatile
    private var ownedEmojiIds: Set<Long> = holders.values.mapNotNull { it.emojiId.takeIf { id -> id != 0L } }.toSet()

    @Volatile
    private var loaded = false

    // entiny: fold users, chats, and channel offsets to bare ids before manifest lookup
    private fun normalizeId(rawId: Long): Long {
        var id = kotlin.math.abs(rawId)
        if (id > 1_000_000_000_000L) {
            id -= 1_000_000_000_000L
        }
        return id
    }

    @JvmStatic
    fun badgeFor(rawId: Long): Badge? {
        if (rawId == 0L) return null
        return holders[normalizeId(rawId)]
    }

    @JvmStatic
    fun hasBadge(rawId: Long): Boolean = badgeFor(rawId) != null

    @JvmStatic
    fun localizedTitle(badge: Badge): String = pickLocalized(badge.titles)

    @JvmStatic
    fun localizedDescription(badge: Badge): String = pickLocalized(badge.descriptions)

    private fun pickLocalized(strings: Map<String, String>): String {
        val lang = LocaleController.getInstance().currentLocale?.language
        return strings[lang] ?: strings["en"] ?: strings.values.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    @JvmStatic
    fun showInfoBulletin(fragment: org.telegram.ui.ActionBar.BaseFragment?, rawId: Long) {
        if (fragment == null) return
        val badge = badgeFor(rawId) ?: return
        val title = localizedTitle(badge).ifBlank { LocaleController.getString(org.telegram.messenger.R.string.InuDevBadge) }
        val description = localizedDescription(badge).ifBlank { LocaleController.getString(org.telegram.messenger.R.string.InuDevBadgeInfo) }
        org.telegram.ui.Components.BulletinFactory.of(fragment)
            .createSimpleBulletin(title, description)
            .show()
    }

    @JvmStatic
    fun applyTo(user: TLRPC.User?) {
        if (user == null) return
        user.bot_verification_icon = resolveIcon(user.id, user.bot_verification_icon)
    }

    @JvmStatic
    fun applyTo(chat: TLRPC.Chat?) {
        if (chat == null) return
        chat.bot_verification_icon = resolveIcon(-chat.id, chat.bot_verification_icon)
    }

    private fun resolveIcon(rawId: Long, current: Long): Long {
        if (current != 0L && !ownedEmojiIds.contains(current)) {
            return current
        }
        // entiny: hide dev badges must clear existing ids, not just stop writing new ones
        if (InuConfig.HIDE_DEV_BADGES.value) return 0L
        return badgeFor(rawId)?.emojiId ?: 0L
    }

    @JvmStatic
    fun refreshCached() {
        for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (!UserConfig.getInstance(account).isClientActivated) continue
            val controller = MessagesController.getInstance(account)
            for (user in controller.users.values) applyTo(user)
            for (chat in controller.chats.values) applyTo(chat)
        }
        // entiny: reloadInterface avoids desynced animation clocks across chat header, pill, and dialog row
        AndroidUtilities.runOnUIThread {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface)
        }
    }

    @JvmStatic
    fun init(account: Int) {
        if (loaded) return
        loaded = true
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val cached = runCatching { InuDatabaseHelper.readKv(db, KV_MANIFEST) }.getOrNull()
            val etag = runCatching { InuDatabaseHelper.readKv(db, KV_ETAG) }.getOrNull()
            val fetchedAt = runCatching { InuDatabaseHelper.readKv(db, KV_FETCHED_AT) }
                .getOrNull()?.toLongOrNull() ?: 0L
            val owned = runCatching { InuDatabaseHelper.readKv(db, KV_OWNED_IDS) }.getOrNull()

            if (!cached.isNullOrEmpty()) {
                parse(cached)?.let { publish(it, owned) }
            } else if (!owned.isNullOrEmpty()) {
                ownedEmojiIds = parseOwned(owned)
            }

            if (System.currentTimeMillis() - fetchedAt >= TTL_MS) {
                Utilities.globalQueue.postRunnable { refresh(account, etag) }
            }
        }
    }

    private fun refresh(account: Int, etag: String?) {
        val (status, body, newEtag) = runCatching { fetch(etag) }.getOrElse {
            return
        }
        if (status == 304) {
            touchFetchedAt(account)
            return
        }
        if (status != 200 || body.isNullOrEmpty()) return
        val parsed = parse(body) ?: return

        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val owned = mergeOwned(
                runCatching { InuDatabaseHelper.readKv(db, KV_OWNED_IDS) }.getOrNull(),
                parsed.values.map { it.emojiId },
            )
            runCatching {
                InuDatabaseHelper.writeKv(db, KV_MANIFEST, body)
                InuDatabaseHelper.writeKv(db, KV_FETCHED_AT, System.currentTimeMillis().toString())
                InuDatabaseHelper.writeKv(db, KV_OWNED_IDS, owned.joinToString(","))
                if (!newEtag.isNullOrEmpty()) InuDatabaseHelper.writeKv(db, KV_ETAG, newEtag)
            }
            publish(parsed, owned.joinToString(","))
        }
    }

    private fun touchFetchedAt(account: Int) {
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            runCatching {
                InuDatabaseHelper.writeKv(db, KV_FETCHED_AT, System.currentTimeMillis().toString())
            }
        }
    }

    private fun publish(parsed: Map<Long, Badge>, owned: String?) {
        val ids = mergeOwned(owned, parsed.values.map { it.emojiId })
        AndroidUtilities.runOnUIThread {
            ownedEmojiIds = ids
            holders = parsed
        }
    }

    private fun mergeOwned(stored: String?, incoming: List<Long>): Set<Long> {
        val set = HashSet(parseOwned(stored))
        for (id in incoming) if (id != 0L) set.add(id)
        return set
    }

    private fun parseOwned(stored: String?): Set<Long> {
        if (stored.isNullOrEmpty()) return emptySet()
        return stored.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
    }

    private fun fetch(etag: String?): Triple<Int, String?, String?> {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            if (!etag.isNullOrEmpty()) connection.setRequestProperty("If-None-Match", etag)

            val status = connection.responseCode
            if (status == 304) return Triple(status, null, etag)
            if (status != 200) return Triple(status, null, null)

            val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            return Triple(status, body, connection.getHeaderField("ETag"))
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(body: String): Map<Long, Badge>? = runCatching {
        val root = JSONObject(body)
        val badgesJson = root.getJSONArray("badges")
        val bySlug = HashMap<String, Badge>(badgesJson.length())
        for (i in 0 until badgesJson.length()) {
            val o = badgesJson.getJSONObject(i)
            val slug = o.optString("slug")
            if (TextUtils.isEmpty(slug)) continue
            bySlug[slug] = Badge(
                slug = slug,
                titles = localeStrings(o.optJSONObject("title")),
                descriptions = localeStrings(o.optJSONObject("description")),
                icon = o.optString("icon"),
                // entiny: parse as string because a 64-bit document id does not survive a JSON double intact
                emojiId = o.optString("emojiId").toLongOrNull() ?: 0L,
            )
        }

        val holdersJson = root.getJSONArray("holders")
        val result = HashMap<Long, Badge>(holdersJson.length())
        for (i in 0 until holdersJson.length()) {
            val o = holdersJson.getJSONObject(i)
            val id = o.optString("id").toLongOrNull() ?: continue
            val badge = bySlug[o.optString("badge")] ?: continue
            result[normalizeId(id)] = badge
        }
        if (result.isEmpty()) null else result
    }.getOrNull()

    private fun localeStrings(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = HashMap<String, String>(LOCALES.size)
        for (locale in LOCALES) {
            val value = obj.optString(locale)
            if (value.isNotEmpty()) map[locale] = value
        }
        return map
    }

    private fun bundledHolders(): Map<Long, Badge> {
        val json = runCatching {
            ApplicationLoader.applicationContext
                ?.resources
                ?.openRawResource(org.telegram.messenger.R.raw.inu_badges_bundled)
                ?.bufferedReader()
                ?.use(BufferedReader::readText)
        }.getOrNull()
        return json?.let { parse(it) }.orEmpty()
    }
}
