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

/**
 * Remote developer-badge registry.
 *
 * The holder list used to be compiled into the fork, so adding one channel meant
 * shipping an APK. It now comes from a manifest that is fetched once an hour, cached in
 * `inu_kv`, and falls back to the compiled-in list when it has never been fetched.
 *
 * How a badge actually reaches the screen: [applyTo] writes the badge's custom-emoji id into
 * `TLRPC.User.bot_verification_icon` / `TLRPC.Chat.bot_verification_icon` as the object is put
 * into MessagesController. Stock already renders that field in seven places (DialogCell,
 * UserCell, ProfileSearchCell, ChatAvatarContainer, ProfileActivity, GroupCallUserCell,
 * UserInfoCell) through AnimatedEmojiDrawable, so the fork needs no drawing code of its own -
 * no spans, no custom drawables, and nothing that can throw the chat header's layout off.
 *
 * Two things this deliberately does NOT do:
 *  - It never asks the server about a single id. The whole list is fetched and matched locally,
 *    because a per-id lookup would tell the maintainer which chats a user has open.
 *  - It never overwrites a real Telegram verification. [applyTo] only writes when the field is
 *    empty or already holds an id this registry put there.
 *
 * Worth knowing: this is a plain HTTPS request, so unlike everything else in the app it does not
 * go through MTProto or the user's proxy - the maintainer's host sees the client IP once an hour.
 * Moving the manifest into a Telegram channel (the way UpdateHelper reads updates) would remove
 * that entirely if it ever becomes a concern.
 */
object BadgeRegistry {

    /** Every locale the manifest and the app's own translations carry a badge string in. */
    private val LOCALES = listOf("en", "uk", "ru", "tr", "ja", "zh")

    data class Badge(
        val slug: String,
        /** Keyed by language tag (see [LOCALES]); a missing key means untranslated. */
        val titles: Map<String, String>,
        val descriptions: Map<String, String>,
        /** Bundled drawable name - the offline and Lite-mode fallback. */
        val icon: String,
        /** Custom emoji document id, or 0 when the badge has no remote icon yet. */
        val emojiId: Long,
    )

    private const val ENDPOINT = "https://entaytion.is-a.dev/api/entinygram/badges"
    private const val TTL_MS = 60L * 60 * 1000 // 1 hour, matches Vercel edge max-age=3600

    private const val KV_MANIFEST = "badges:manifest"
    private const val KV_ETAG = "badges:etag"
    private const val KV_FETCHED_AT = "badges:fetched_at"

    /**
     * Every emoji id this registry has ever written into a stock object, not just the ones in
     * the current manifest. Without the history a revoked badge could never be cleared: the id
     * would be gone from the manifest, [applyTo] would no longer recognise it as ours, and it
     * would sit in the cached user blob forever looking like a genuine Telegram verification.
     */
    private const val KV_OWNED_IDS = "badges:owned_ids"

    @Volatile
    private var holders: Map<Long, Badge> = bundledHolders()

    /**
     * Seeded from the compiled-in ids rather than left empty: on the very first frames, before
     * the cached list has been read back, a user object may already carry one of our ids from
     * the local cache. Starting empty would make [resolveIcon] mistake it for a real Telegram
     * verification and refuse to ever clear it.
     */
    @Volatile
    private var ownedEmojiIds: Set<Long> = holders.values.mapNotNull { it.emojiId.takeIf { id -> id != 0L } }.toSet()

    @Volatile
    private var loaded = false

    /**
     * Dialog ids arrive in several shapes for the same entity - positive for users, negative for
     * chats, and channels additionally offset by 1_000_000_000_000. The manifest stores the bare
     * id, so everything is folded to that form before lookup.
     */
    private fun normalizeId(rawId: Long): Long {
        var id = kotlin.math.abs(rawId)
        if (id > 1_000_000_000_000L) {
            id -= 1_000_000_000_000L
        }
        return id
    }

    /** Draw-path safe: a map lookup, no I/O, no parsing, no allocation. */
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

    /** Device language, else English, else whatever the manifest did bother to translate. */
    private fun pickLocalized(strings: Map<String, String>): String {
        val lang = LocaleController.getInstance().currentLocale?.language
        return strings[lang] ?: strings["en"] ?: strings.values.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    /**
     * The badge is drawn through stock's own bot-verification rendering (see the class doc), which
     * has no click handling of its own for it - tapping it used to fall through to whatever
     * happened to sit underneath (the avatar, the header), popping an unrelated bulletin. Wired
     * from [org.telegram.ui.ProfileActivity]'s bot-verification leftDrawable click.
     */
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

    /**
     * Decide what `bot_verification_icon` should hold. Returns [current] untouched whenever it
     * carries a real Telegram verification, so a badge of ours can never hide one of theirs.
     */
    private fun resolveIcon(rawId: Long, current: Long): Long {
        if (current != 0L && !ownedEmojiIds.contains(current)) {
            return current
        }
        // Turning the setting off has to clear ids already written, not just stop writing new
        // ones - the field survives in the cached user blob otherwise.
        if (InuConfig.HIDE_DEV_BADGES.value) return 0L
        return badgeFor(rawId)?.emojiId ?: 0L
    }

    /**
     * Re-runs [resolveIcon] over everything MessagesController is already holding, then asks the
     * UI to redraw.
     *
     * Needed because [applyTo] is only ever reached from putUser/putChat, i.e. as an object
     * *enters* the controller. Flipping [InuConfig.HIDE_DEV_BADGES] left every already-cached user
     * and chat carrying the id it was given on the way in, and re-putting them would not have
     * helped either - putUser() returns early when handed the same instance it already holds, so
     * resolveIcon() would never run. The setting looked completely dead until the process was
     * restarted and the caches were rebuilt from scratch.
     *
     * Call on the UI thread: it mutates objects the UI reads and then publishes a rebuild.
     */
    @JvmStatic
    fun refreshCached() {
        for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (!UserConfig.getInstance(account).isClientActivated) continue
            val controller = MessagesController.getInstance(account)
            for (user in controller.users.values) applyTo(user)
            for (chat in controller.chats.values) applyTo(chat)
        }
        // Deliberately NOT NotificationCenter.updateInterfaces. That path updates each surface
        // through its own incremental route, and for this field the routes run on three different
        // clocks: the chat header drops the drawable in the frame it is told, the pill behind it
        // eases to its new width over 320ms, and a dialog row cross-fades the badge out while
        // rebuilding its name layout in one frame - so the badge ghosts over a name that has
        // already moved. Every one of those is correct on its own and they still do not agree,
        // which is what reads as the toggle glitching rather than switching.
        //
        // reloadInterface rebuilds the fragments outright, so the whole UI arrives in the new
        // state at once with nothing left mid-animation. It is what the other appearance toggles
        // in this fork already use, and rebuildAllFragments() skips the topmost fragment, so the
        // settings page the switch lives on does not flicker under the finger.
        AndroidUtilities.runOnUIThread {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface)
        }
    }

    /**
     * Loads the cached manifest and refreshes it when stale. Cheap to call repeatedly - the
     * first call wins and the rest return immediately.
     */
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
            // Offline, DNS blocked, host down - the cached manifest (or the bundled list) stands.
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

    /** Returns null on malformed input so a bad deploy leaves the previous list in place. */
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
                // Sent as a string: a document id does not survive a JSON double intact.
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

    /** Reads whichever of [LOCALES] the manifest actually set; skips the rest instead of storing "". */
    private fun localeStrings(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = HashMap<String, String>(LOCALES.size)
        for (locale in LOCALES) {
            val value = obj.optString(locale)
            if (value.isNotEmpty()) map[locale] = value
        }
        return map
    }

    /**
     * Compiled-in fallback so a fresh install shows badges before its first fetch and an
     * offline one keeps showing them. This used to be a hand-typed `hashMapOf` here that had
     * to be kept in sync with the database by eyeballing a diff every time a badge or holder
     * changed on entaytion-is.a.dev - the two lists had already drifted (a holder present in
     * the database was missing here). Instead this parses the same manifest shape [parse]
     * reads over the network, from a JSON snapshot bundled as a raw resource.
     *
     * Refresh it with `bun run scripts/sync-badges-bundle.ts` after changing badges/holders
     * on the server - not by hand-editing Kotlin.
     */
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
