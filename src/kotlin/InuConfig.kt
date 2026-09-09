package desu.inugram

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import desu.inugram.helpers.chat.DoubleTapActionHelper
import desu.inugram.helpers.chat.PinnedReactionsHelper
import desu.inugram.helpers.font.FontConfig
import desu.inugram.helpers.menu.ChatMenuConfig
import desu.inugram.helpers.menu.MainTabsMenuConfig
import desu.inugram.helpers.menu.MessageMenuConfig
import desu.inugram.helpers.menu.ProfileInfoMenuConfig
import desu.inugram.helpers.menu.ProfileMenuConfig
import desu.inugram.ui.FormattingPopupConfig

object InuConfig {
    private const val PREFS_NAME = "inugram"

    lateinit var prefs: SharedPreferences
    private val _items = mutableListOf<Item<*>>()
    val items: List<Item<*>> get() = _items

    fun load(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        FontConfig.register() // force-init so its items join _items before we load them
        for (item in _items) item.load(prefs)
        migrateGhostMasterFlag()
        migrateGhostModeEnabledDefault()
        migrateGhostAutoOffline()
        migrateSelfDestructCategories()
        migrateAiRoles()
        migrateAiProviders()
        migrateSharedProviderKeys()
        migrateCenteringGroup()
    }

    // "ios_chat_header" used to be a standalone iOS chat header: it centered the header by itself,
    // independently of CENTER_TITLE_MAIN/CENTER_TITLE_CHATS, and moving the avatar into the "..."
    // slot was simply part of that mode. The key survived the rebuild of the centering group but
    // its meaning did not - it is now "compact pill", readable only through
    // InuUtils.compactChatPill(), i.e. only under both centering parents, and the avatar-in-slot
    // half became its own opt-in ([IOS_CHAT_HEADER_AVATAR_SLOT], default off).
    //
    // Without this step, anyone who had the old mode on lands on a header that is neither what
    // they had nor off: the parents are false, so the pill does nothing at all, or - once they
    // turn centering back on - the avatar is back inside the pill it used to sit outside of,
    // eating the room the title needs and pushing a long one into the marquee. Re-state their old
    // setup in the new vocabulary, and only ever for someone who actually had the legacy mode on.
    private fun migrateCenteringGroup() {
        if (CENTERING_GROUP_MIGRATED.value) {
            dropLegacyCenteringKeys()
            return
        }
        CENTERING_GROUP_MIGRATED.value = true
        // The legacy fingerprint has to be unambiguous, because this runs once for everyone and
        // the same key means two different things on either side of the rebuild. Under the NEW
        // nesting, ios_chat_header can only ever have been switched on from a row that is itself
        // only shown while both parents are on - so "on, with a parent off" is a state the new UI
        // cannot produce and the old one produced routinely. Anyone already sitting on the new
        // nesting is left completely alone; the cost is that a legacy user who happened to have
        // all three on is read as new and keeps the avatar inside the pill rather than in the
        // menu slot. That is one toggle away, and it is the right way round: never overwrite a
        // deliberate choice to repair a guess.
        val legacyStandaloneMode = IOS_CHAT_HEADER.value && !(CENTER_TITLE_MAIN.value && CENTER_TITLE_CHATS.value)
        if (legacyStandaloneMode) {
            CENTER_TITLE_MAIN.value = true
            CENTER_TITLE_CHATS.value = true
            // Only seed the avatar slot if the user has never had an opinion on it - the key is
            // new, so its mere presence means they already made a choice on the new build.
            if (!prefs.contains(IOS_CHAT_HEADER_AVATAR_SLOT.key)) {
                IOS_CHAT_HEADER_AVATAR_SLOT.value = true
            }
        }
        dropLegacyCenteringKeys()
    }

    // center_title_fixed / center_title_adaptive_width were dropped outright - the compact pill is
    // the adaptive-width mode now, and the fixed placement is what the symmetric room does. Nothing
    // reads them any more, so they are just dead weight in an export.
    private fun dropLegacyCenteringKeys() {
        if (!prefs.contains("center_title_fixed") && !prefs.contains("center_title_adaptive_width")) {
            return
        }
        prefs.edit(commit = true) {
            remove("center_title_fixed")
            remove("center_title_adaptive_width")
        }
    }

    // AI Compose used to store an arbitrary named list of endpoints ([AI_COMPOSE_ENDPOINTS]) you
    // picked one active one from. The unified AI Providers screen replaces that with exactly one
    // chat slot per known provider, so there is nothing left to "pick a name" for. One-time seed:
    // whichever endpoint was active becomes that provider's chat slot, so an already-configured
    // user doesn't silently lose their key on update. Extra endpoints beyond the active one (a
    // second saved Custom config, say) are not preserved -- the new model has room for one.
    private fun migrateAiProviders() {
        if (AI_PROVIDERS_MIGRATED.value) return
        AI_PROVIDERS_MIGRATED.value = true
        val list = AI_COMPOSE_ENDPOINTS.value
        if (list.isEmpty()) return
        val activeId = AI_COMPOSE_ACTIVE_ENDPOINT.value
        val endpoint = list.firstOrNull { it.id == activeId } ?: list.first()
        val url = endpoint.url
        val providerId = when {
            url.contains("generativelanguage.googleapis.com", ignoreCase = true) -> TRANSCRIBE_PROVIDER_GEMINI
            url.contains("api.openai.com", ignoreCase = true) -> TRANSCRIBE_PROVIDER_OPENAI
            url.contains("api.groq.com", ignoreCase = true) -> TRANSCRIBE_PROVIDER_GROQ
            url.contains("openrouter.ai", ignoreCase = true) -> AI_PROVIDER_OPENROUTER
            else -> TRANSCRIBE_PROVIDER_CUSTOM
        }
        when (providerId) {
            TRANSCRIBE_PROVIDER_GEMINI -> { AI_PROVIDER_GEMINI_KEY.value = endpoint.apiKey; if (endpoint.model.isNotBlank()) AI_CHAT_GEMINI_MODEL.value = endpoint.model }
            TRANSCRIBE_PROVIDER_OPENAI -> { AI_PROVIDER_OPENAI_KEY.value = endpoint.apiKey; if (endpoint.model.isNotBlank()) AI_CHAT_OPENAI_MODEL.value = endpoint.model }
            TRANSCRIBE_PROVIDER_GROQ -> { AI_PROVIDER_GROQ_KEY.value = endpoint.apiKey; if (endpoint.model.isNotBlank()) AI_CHAT_GROQ_MODEL.value = endpoint.model }
            AI_PROVIDER_OPENROUTER -> { AI_CHAT_OPENROUTER_KEY.value = endpoint.apiKey; if (endpoint.model.isNotBlank()) AI_CHAT_OPENROUTER_MODEL.value = endpoint.model }
            else -> { AI_CHAT_CUSTOM_URL.value = url; AI_CHAT_CUSTOM_KEY.value = endpoint.apiKey; AI_CHAT_CUSTOM_MODEL.value = endpoint.model }
        }
        AI_CHAT_ACTIVE_PROVIDER.value = providerId
    }

    // Gemini/OpenAI/Groq used to store a separate API key per scope (chat vs voice), which meant
    // pasting the same account's key twice. Now there's one shared key per provider -- this
    // one-time step copies whichever scope-specific key was already set (voice, since that
    // screen shipped first) into the new shared slot, so nobody has to re-paste it.
    private fun migrateSharedProviderKeys() {
        if (AI_SHARED_PROVIDER_KEYS_MIGRATED.value) return
        AI_SHARED_PROVIDER_KEYS_MIGRATED.value = true
        if (AI_PROVIDER_GEMINI_KEY.value.isBlank()) AI_PROVIDER_GEMINI_KEY.value = AI_TRANSCRIBE_GEMINI_KEY.value
        if (AI_PROVIDER_OPENAI_KEY.value.isBlank()) AI_PROVIDER_OPENAI_KEY.value = AI_TRANSCRIBE_OPENAI_KEY.value
        if (AI_PROVIDER_GROQ_KEY.value.isBlank()) AI_PROVIDER_GROQ_KEY.value = AI_TRANSCRIBE_GROQ_KEY.value
    }

    // AI_ROLE used to be a single free-text persona string with no way to save more than one.
    // It's now a list of named presets ([AI_ROLES]) you switch between from their own screen.
    // One-time seed: whatever the user had typed into the old field becomes their first preset,
    // so nobody's existing persona silently vanishes on update. Guarded by its own run-record so
    // a later backup restore can't re-seed over presets the user has since edited or deleted.
    private fun migrateAiRoles() {
        if (AI_ROLES_MIGRATED.value) return
        AI_ROLES_MIGRATED.value = true
        val legacy = AI_ROLE.value.trim()
        if (legacy.isNotEmpty() && AI_ROLES.value.isEmpty()) {
            val role = desu.inugram.helpers.ai.AiRole(id = "legacy", text = legacy)
            AI_ROLES.value = listOf(role)
            AI_ACTIVE_ROLE.value = role.id
        }
    }

    // The periodic "go offline again" re-assert used to be welded to GHOST_PRESENCE_MODE.DELAYED;
    // it is now its own switch ([GHOST_AUTO_OFFLINE]) that works with any presence mode.
    //
    // Minimal, non-stranding migration: GHOST_PRESENCE_MODE keeps all three of its values (NORMAL /
    // HIDDEN / DELAYED) so nobody's stored choice is reinterpreted, and DELAYED keeps meaning
    // exactly what it meant. The one-time step just turns the new independent toggle ON for anyone
    // who was on DELAYED, so their behaviour after the update is identical to before. HIDDEN and
    // NORMAL users are untouched (auto-offline stays off = stock-identical); HIDDEN users can now
    // opt into the re-assert they could never get before. Guarded by its own non-exportable
    // run-record so a restored backup can't re-arm it over a later explicit choice.
    private fun migrateGhostAutoOffline() {
        if (GHOST_AUTO_OFFLINE_MIGRATED.value) return
        GHOST_AUTO_OFFLINE_MIGRATED.value = true
        if (GHOST_PRESENCE_MODE.value == GhostPresenceModeItem.DELAYED) {
            GHOST_AUTO_OFFLINE.value = true
        }
    }

    // GHOST_MODE (a single master on/off) was removed in favor of independent sub-toggles with
    // no stored master flag (see GhostHelper's class doc). Without this, anyone who had the old
    // master switch on silently lost every bit of Ghost Mode protection the moment they updated
    // past that change -- the new sub-toggles all default to false, so nothing carries over.
    //
    // This migration is destructive (it force-sets five sub-toggles), so it MUST be strictly
    // one-time. Its original guard was the mere presence of the legacy key, with the removal of
    // that key as the only record that it had run -- i.e. the run-record lived in the same
    // prefs file the migration writes, and nowhere else. Anything that reintroduces an older
    // `inugram.xml` (Android Auto Backup / device-to-device restore -- the manifest ships
    // allowBackup="true" + restoreAnyVersion="true" with no dataExtractionRules exclusion --
    // or any external prefs restore) re-arms the legacy key and re-fires the migration, stomping
    // the user's real per-toggle choices back to "all ghost on". That is the reported
    // "all four sub-toggles are active after an update even though only one is enabled" bug.
    //
    // [GHOST_MASTER_FLAG_MIGRATED] is an independent, non-exportable run-record: once it is set,
    // the migration never applies again regardless of whether the legacy key comes back.
    private fun migrateGhostMasterFlag() {
        if (GHOST_MASTER_FLAG_MIGRATED.value) {
            // Legacy key resurfaced (restore/import) after we had already migrated -- drop it
            // without touching the user's sub-toggles.
            if (prefs.contains("ghost_mode")) {
                logGhostMigration("skipped (already migrated), dropping resurfaced legacy ghost_mode key")
                prefs.edit(commit = true) { remove("ghost_mode") }
            }
            return
        }
        val hadLegacyMaster = prefs.contains("ghost_mode") && prefs.getBoolean("ghost_mode", false)
        if (hadLegacyMaster) {
            GHOST_HIDE_READ.value = true
            GHOST_HIDE_VOICE_READ.value = true
            GHOST_HIDE_STORY_READ.value = true
            GHOST_HIDE_TYPING.value = true
            GHOST_PRESENCE_MODE.value = GhostPresenceModeItem.HIDDEN
            logGhostMigration("applied: legacy ghost_mode=true -> all sub-toggles forced on")
        } else {
            logGhostMigration("skipped: no legacy ghost_mode=true (contains=${prefs.contains("ghost_mode")})")
        }
        // Record the run first, then clear the legacy key. If the process dies between the two,
        // the worst case is a stale key that the already-migrated branch above cleans up later --
        // never a second stomp.
        GHOST_MASTER_FLAG_MIGRATED.value = true
        if (prefs.contains("ghost_mode")) prefs.edit(commit = true) { remove("ghost_mode") }
    }

    // GHOST_MODE_ENABLED reintroduces a real, persisted master flag (see GhostHelper's class doc
    // for why): with sub-toggles alone, there was no way to "pause" ghost mode without wiping the
    // user's per-toggle picks, and turning the drawer quick-toggle back on forced every sub-toggle
    // to true regardless of what had actually been configured. Unlike the legacy `ghost_mode` key
    // this replaces a second time, this migration is guarded by its OWN non-exportable run-record
    // ([GHOST_MODE_ENABLED_MIGRATED]), not by the presence of the key it writes -- so a restored
    // backup that reintroduces an old `ghost_mode_enabled` value cannot re-arm it and stomp a
    // later choice. It only runs once, ever, on the upgrade that introduces this flag: if any
    // sub-toggle was already active (from the no-master-flag era), it flips the new master on so
    // existing protection doesn't silently vanish; a fresh install with everything off stays off.
    private fun migrateGhostModeEnabledDefault() {
        if (GHOST_MODE_ENABLED_MIGRATED.value) return
        GHOST_MODE_ENABLED_MIGRATED.value = true
        val hadActiveSubToggle = GHOST_HIDE_READ.value || GHOST_HIDE_VOICE_READ.value ||
            GHOST_HIDE_STORY_READ.value || GHOST_HIDE_TYPING.value ||
            GHOST_PRESENCE_MODE.value != GhostPresenceModeItem.NORMAL
        if (hadActiveSubToggle) {
            GHOST_MODE_ENABLED.value = true
        }
    }

    // Mirrors GhostHelper.shouldSuppress's convention: Log.d for adb, FileLog.d so a release-build
    // tester can capture it via Settings -> Additional -> Logs -> Send.
    private fun logGhostMigration(what: String) {
        android.util.Log.d("GhostMode", "migrateGhostMasterFlag $what")
        org.telegram.messenger.FileLog.d("GhostMode: migrateGhostMasterFlag $what")
    }

    // SAVE_SELF_DESTRUCT and SAVE_SECRET_CHAT_CONTENT (two single bools) were split into the
    // per-category items below. The split isn't a clean 1:1 rename, so migrate conservatively:
    // if either old flag was on, turn on every new category rather than guessing which ones the
    // old flag actually covered -- silently narrowing what a user had opted into is worse than
    // temporarily over-covering it (they can turn categories back off in Settings).
    private fun migrateSelfDestructCategories() {
        val hadOld = prefs.contains("save_self_destruct") || prefs.contains("save_secret_chat_content")
        if (!hadOld) return
        if (prefs.getBoolean("save_self_destruct", false) || prefs.getBoolean("save_secret_chat_content", false)) {
            SAVE_SELF_DESTRUCT_MEDIA.value = true
            SAVE_SELF_DESTRUCT_TEXT.value = true
            SAVE_VIEW_ONCE_MEDIA.value = true
            SAVE_TIMED_MESSAGES.value = true
        }
        prefs.edit(commit = true) {
            remove("save_self_destruct")
            remove("save_secret_chat_content")
        }
    }

    enum class PrefType { BOOL, INT, LONG, FLOAT, STRING }

    abstract class Item<T>(val key: String, val default: T, val exportable: Boolean = true) {
        private var currentValue: T = default

        // underlying SharedPreferences type this item serializes into
        abstract val prefType: PrefType

        open var value: T
            get() = currentValue
            set(v) {
                currentValue = v
                save()
            }

        init {
            _items.add(this)
        }

        fun load(prefs: SharedPreferences) {
            currentValue = read(prefs)
        }

        // commit=true (synchronous) is deliberate: apply() only queues the write to a background
        // thread, and does not survive the process dying before that flush lands -- which is
        // exactly what happens when an app update replaces the running process (MY_PACKAGE_REPLACED
        // kills it). A toggle flipped right before an update could silently lose that write and
        // revert to its last-flushed value. The sync cost here is negligible (a handful of ms on
        // a small prefs file, on a UI-triggered settings write, not a hot path).
        fun save() {
            prefs.edit(commit = true) { write() }
        }

        // batched write: set currentValue and stage it onto [editor] (call inside a prefs.edit {} block,
        // passing that block's editor, to commit several items in one transaction)
        fun unsafeSet(value: T, editor: SharedPreferences.Editor) {
            currentValue = value
            editor.write()
        }

        protected abstract fun read(prefs: SharedPreferences): T
        protected abstract fun SharedPreferences.Editor.write()
    }

    open class BoolItem(key: String, default: Boolean, exportable: Boolean = true) :
        Item<Boolean>(key, default, exportable) {
        override val prefType = PrefType.BOOL
        override fun read(prefs: SharedPreferences): Boolean = prefs.getBoolean(key, default)
        override fun SharedPreferences.Editor.write() {
            putBoolean(key, value)
        }

        fun toggle(): Boolean {
            val new = !this.value
            this.value = new
            return new
        }
    }

    open class IntItem(key: String, default: Int, exportable: Boolean = true) : Item<Int>(key, default, exportable) {
        override val prefType = PrefType.INT
        override fun read(prefs: SharedPreferences): Int = prefs.getInt(key, default)
        override fun SharedPreferences.Editor.write() {
            putInt(key, value)
        }
    }

    class FloatItem(key: String, default: Float, exportable: Boolean = true) : Item<Float>(key, default, exportable) {
        override val prefType = PrefType.FLOAT
        override fun read(prefs: SharedPreferences): Float = prefs.getFloat(key, default)
        override fun SharedPreferences.Editor.write() {
            putFloat(key, value)
        }
    }

    class StringItem(key: String, default: String, exportable: Boolean = true) :
        Item<String>(key, default, exportable) {
        override val prefType = PrefType.STRING
        override fun read(prefs: SharedPreferences): String = prefs.getString(key, default) ?: default
        override fun SharedPreferences.Editor.write() {
            putString(key, value)
        }
    }

    open class StringSetItem(key: String, default: Set<String> = emptySet(), exportable: Boolean = true) :
        Item<Set<String>>(key, default, exportable) {
        // No dedicated PrefType for string sets; STRING is a harmless placeholder here since the
        // backup export/import path branches on the *runtime* prefs value type (Set<String> matches
        // none of Boolean/Int/Long/Float/Double/String there), not on this field, for this item.
        override val prefType = PrefType.STRING
        override fun read(prefs: SharedPreferences): Set<String> =
            prefs.getStringSet(key, default)?.toSet() ?: default
        override fun SharedPreferences.Editor.write() {
            putStringSet(key, value)
        }
    }

    class LongItem(key: String, default: Long, exportable: Boolean = true) : Item<Long>(key, default, exportable) {
        override val prefType = PrefType.LONG
        override fun read(prefs: SharedPreferences): Long = prefs.getLong(key, default)
        override fun SharedPreferences.Editor.write() {
            putLong(key, value)
        }
    }

    // visible in ui
    @JvmField
    val HIDE_STORIES = BoolItem("hide_stories", false)

    @JvmField
    val SHOW_SECONDS = BoolItem("show_seconds", false)

    @JvmField
    val DISABLE_ROUNDING = BoolItem("disable_rounding", false)

    @JvmField
    val MATERIAL3_SWITCHES = BoolItem("material3_switches", false)

    @JvmField
    val MATERIAL3_FABS = BoolItem("material3_fabs", true)

    @JvmField
    val M3_SECTIONS_STYLE = BoolItem("m3_sections_style", false)

    @JvmField
    val MATERIAL3_AVATARS = BoolItem("material3_avatars", false)

    @JvmField
    val AVATAR_CORNERS = FloatItem("avatar_corners", 28.0f)

    @JvmField
    val UNIFIED_AVATAR_RADIUS = BoolItem("unified_avatar_radius", false)

    @JvmField
    val MATERIAL_PROFILE_ACTIONS = BoolItem("material_profile_actions", false)

    @JvmField
    val M3_NAVIGATION_ANIMATION = BoolItem("m3_navigation_animation", false)

    @JvmField
    val M3_BOTTOM_TABS = BoolItem("m3_bottom_tabs", false)

    // snapshot of theme state before Monet was enabled, "day|night|autoNightType"; empty = none
    @JvmField
    val MONET_PREV = StringItem("monet_prev", "", exportable = false)

    class PredictiveBackModeItem : IntItem("predictive_back_mode", OFF) {
        // Migrate the old DISABLE_PREDICTIVE_BACK boolean (removed when this became a 3-way
        // mode): disabled=true mapped to OFF, disabled=false mapped to STOCK (the only "on"
        // state that existed before MATERIAL3 was added) — otherwise the setting silently
        // reset to OFF for anyone who had predictive back enabled under the old scheme.
        override fun read(prefs: SharedPreferences): Int {
            if (prefs.contains(key)) return prefs.getInt(key, default)
            if (!prefs.contains("disable_predictive_back")) return default
            val migrated = if (prefs.getBoolean("disable_predictive_back", true)) OFF else STOCK
            prefs.edit(commit = true) {
                putInt(key, migrated)
                remove("disable_predictive_back")
            }
            return migrated
        }

        companion object {
            const val OFF = 0
            const val STOCK = 1
            const val MATERIAL3 = 2
        }
    }

    @JvmField
    val PREDICTIVE_BACK_MODE = PredictiveBackModeItem()

    // Skips SpoilerEffect.addSpoilers()/SpoilersTextView's tap-to-reveal entirely — text spoilers
    // render already-revealed everywhere, same idea as NagramX's showSpoilersDirectly.
    @JvmField
    val SHOW_SPOILERS_DIRECTLY = BoolItem("show_spoilers_directly", false)

    @JvmField
    val HDR_IMAGES = BoolItem("hdr_images", true, exportable = false)

    class TextSpoilerModeItem : IntItem("text_spoiler_mode", SIMPLE) {
        companion object {
            const val DEFAULT = 0
            const val SIMPLE = 1
            const val EPSTEIN = 2
        }
    }

    @JvmField
    val TEXT_SPOILER_MODE = TextSpoilerModeItem()

    @JvmField
    val SPOILER_EXTEND_TO_LINE_END = BoolItem("spoiler_extend_to_line_end", false)

    @JvmField
    val LINK_PREVIEW_SPOILER = BoolItem("link_preview_spoiler", true)

    class MediaSpoilerModeItem : IntItem("media_spoiler_mode", PILL) {
        // Migrate the old `simple_media_spoilers` boolean toggle: on → pill, off → telegram.
        override fun read(prefs: SharedPreferences): Int {
            if (prefs.contains(key)) return prefs.getInt(key, default)
            if (!prefs.contains("simple_media_spoilers")) return default
            val migrated = if (prefs.getBoolean("simple_media_spoilers", true)) PILL else TELEGRAM
            prefs.edit {
                putInt(key, migrated)
                remove("simple_media_spoilers")
            }
            return migrated
        }

        companion object {
            const val TELEGRAM = 0
            const val PILL = 1
            const val CIRCLE = 2
        }
    }

    @JvmField
    val MEDIA_SPOILER_MODE = MediaSpoilerModeItem()

    class BlockedMessagesModeItem : IntItem("blocked_messages_mode", OFF) {
        companion object {
            const val OFF = 0
            const val SPOILER = 1
            const val HIDE = 2
        }
    }

    @JvmField
    val BLOCKED_MESSAGES_MODE = BlockedMessagesModeItem()

    class AttachCameraModeItem : IntItem("attach_camera_mode", STATIC) {
        // Migrate the old `disable_instant_camera` boolean toggle: on → static, off → instant.
        override fun read(prefs: SharedPreferences): Int {
            if (prefs.contains(key)) return prefs.getInt(key, default)
            if (!prefs.contains("disable_instant_camera")) return default
            val migrated = if (prefs.getBoolean("disable_instant_camera", true)) STATIC else INSTANT
            prefs.edit {
                putInt(key, migrated)
                remove("disable_instant_camera")
            }
            return migrated
        }

        companion object {
            const val INSTANT = 0
            const val STATIC = 1
            const val FAB = 2
            const val TAB = 3
        }
    }

    @JvmField
    val ATTACH_CAMERA_MODE = AttachCameraModeItem()

    @JvmField
    val GIF_SEEKBAR = BoolItem("gif_seekbar", true)

    @JvmField
    val SEND_MP4_DOCUMENT_AS_VIDEO = BoolItem("send_mp4_document_as_video", true)

    @JvmField
    val BYPASS_GIF_RESTRICTIONS = BoolItem("bypass_gif_restrictions", false)

    @JvmField
    val SORT_ALBUMS_BY_SIZE = BoolItem("sort_albums_by_size", true)

    @JvmField
    val DOWNLOAD_DIRECTORY = StringItem("download_directory", "entinyGram")

    @JvmField
    val AUTO_DISABLE_PROXY_ON_VPN = BoolItem("auto_disable_proxy_on_vpn", false)

    @JvmField
    val PROXY_SUPPRESSED_BY_VPN = BoolItem("proxy_suppressed_by_vpn", false, exportable = false)

    @JvmField
    val SHOW_ALL_RECENT_STICKERS = BoolItem("show_all_recent_stickers", true)

    @JvmField
    val UNLIMITED_FAVORITE_STICKERS = BoolItem("unlimited_favorite_stickers", false)

    @JvmField
    val UNLIMITED_PINNED_CHATS = BoolItem("unlimited_pinned_chats", false)

    @JvmField
    val UNLIMITED_FOLDER_CHATS = BoolItem("unlimited_folder_chats", false)

    @JvmField
    val UNLIMITED_PINNED_CHATS_COUNT = IntItem("unlimited_pinned_chats_count", 20)

    @JvmField
    val HIDE_TRENDING_STICKERS = BoolItem("hide_trending_stickers", true)

    @JvmField
    val NAVIGATION_DRAWER = BoolItem("navigation_drawer", false)

    @JvmField
    val DRAWER_BACK_GESTURE = BoolItem("drawer_back_gesture", false)

    @JvmField
    val DRAWER_M3_SECTIONS = BoolItem("drawer_m3_sections", false)

    @JvmField
    val SHOW_DRAWER_ACCOUNTS = BoolItem("show_drawer_accounts", true)

    @JvmField
    val BOTTOM_TABS_HIDE = BoolItem("bottom_tabs_hide", false)

    @JvmField
    val BOTTOM_TABS_COMPACT_MODE = BoolItem("bottom_tabs_hide_compact_mode", false)

    /** order + enabled state for non-Chats bottom tabs; Chats is always first and mandatory */
    @JvmField
    val BOTTOM_TABS_ORDER = MainTabsMenuConfig("bottom_tabs_order")

    @JvmField
    val BOTTOM_TABS_SHOW_TITLES = BoolItem("bottom_tabs_show_titles", true)

    @JvmField
    val DIALOGS_FAB_MAIN_ACTION = IntItem("dialogs_fab_main_action", 1)

    @JvmField
    val DIALOGS_FAB_SECONDARY_ACTION = IntItem("dialogs_fab_secondary_action", 2)

    @JvmField
    val DIALOGS_FAB_HIDE_ON_SCROLL = BoolItem("dialogs_fab_hide_on_scroll", true)

    @JvmField
    val DIALOGS_FAB_OFFSET_FOR_BOTTOM_BAR = BoolItem("dialogs_fab_offset_for_bottom_bar", true)

    @JvmField
    val DIALOGS_FAB_LEFT_SIDE = BoolItem("dialogs_fab_left_side", false)

    @JvmField
    val HIDE_KEYBOARD_ON_SCROLL = BoolItem("hide_keyboard_on_scroll", true)

    @JvmField
    val DISABLE_PULL_TO_NEXT = BoolItem("disable_pull_to_next", true)

    @JvmField
    val DISABLE_SENSITIVE = BoolItem("disable_sensitive", false)

    @JvmField
    val DISABLE_CHAT_BACKGROUNDS = BoolItem("disable_chat_backgrounds", false)

    @JvmField
    val DISABLE_CHAT_THEMES = BoolItem("disable_chat_themes", false)

    @JvmField
    val DISABLE_BG_PARALLAX = BoolItem("disable_bg_parallax", true)

    @JvmField
    val DISABLE_SWIPE_TO_UNARCHIVE = BoolItem("disable_swipe_to_unarchive", true)

    @JvmField
    val DISABLE_SWIPE_TO_HIDE_GENERAL_TOPIC = BoolItem("disable_swipe_to_hide_general_topic", true)

    @JvmField
    val DISABLE_CONTACTS_PERMISSION_NAG = BoolItem("disable_contacts_permission_nag", false)

    @JvmField
    val DISABLE_LOCKSCREEN_PERMISSION_NAG = BoolItem("disable_lockscreen_permission_nag", false)

    class PullDownActionItem : IntItem("pull_down_action", REVEAL_ARCHIVE) {
        // Migrate the old `open_archive_on_pull` boolean toggle: on → open archive, off → reveal (stock).
        override fun read(prefs: SharedPreferences): Int {
            if (prefs.contains(key)) return prefs.getInt(key, default)
            if (!prefs.contains("open_archive_on_pull")) return default
            val migrated = if (prefs.getBoolean("open_archive_on_pull", false)) OPEN_ARCHIVE else REVEAL_ARCHIVE
            prefs.edit {
                putInt(key, migrated)
                remove("open_archive_on_pull")
            }
            return migrated
        }

        companion object {
            const val DISABLED = 0
            const val REVEAL_ARCHIVE = 1
            const val OPEN_ARCHIVE = 2
            const val SAVED_MESSAGES = 3
            const val SEARCH = 4
        }
    }

    @JvmField
    val PULL_DOWN_ACTION = PullDownActionItem()

    @JvmField
    val CHAT_ALWAYS_SHOW_DOWN = BoolItem("chat_always_show_down", true)

    @JvmField
    val CHAT_TWO_FINGER_SELECT = BoolItem("chat_two_finger_select", true)

    @JvmField
    val CHAT_REMEMBER_ALL_REPLIES = BoolItem("chat_remember_all_replies", true)

    @JvmField
    val DISABLE_BOT_DRAFT_TOP = BoolItem("disable_bot_draft_top", true)

    @JvmField
    val HIDE_BOTTOM_BAR_JOINED = BoolItem("hide_bottom_bar_joined", false)

    @JvmField
    val HIDE_BOTTOM_BAR_NON_JOINED = BoolItem("hide_bottom_bar_non_joined", false)

    @JvmField
    val HIDE_BOTTOM_BAR_NON_JOINED_GROUPS = BoolItem("hide_bottom_bar_non_joined_groups", false)

    @JvmField
    val HIDE_BOTTOM_BAR_REPLIES = BoolItem("hide_bottom_bar_replies", false)

    @JvmField
    val HIDE_BOTTOM_BAR_PINNED = BoolItem("hide_bottom_bar_pinned", false)

    @JvmField
    val HIDE_BOT_SLASH_GROUPS = BoolItem("hide_bot_slash_groups", true)

    @JvmField
    val HIDE_BOT_SLASH_BOTS = BoolItem("hide_bot_slash_bots", false)

    @JvmField
    val HIDE_BOT_WEBVIEW_INPUT = BoolItem("hide_bot_webview_input", false)

    @JvmField
    val HIDE_SEND_AS_PICKER = BoolItem("hide_send_as_picker", false)

    @JvmField
    val SEND_TO_DISCUSS_WITHOUT_JOIN = BoolItem("send_to_discuss_without_join", true)

    @JvmField
    val HIDE_BOT_WEBVIEW_DIALOGS = BoolItem("hide_bot_webview_dialogs", true)

    @JvmField
    val HIDE_AI_EDITOR = BoolItem("hide_ai_editor", false)

    // AI compose — client-side rewrite/continue of the draft via a user-configured OpenAI-compatible endpoint
    @JvmField
    val AI_COMPOSE_ENABLED = BoolItem("ai_compose_enabled", false)

    @JvmField
    val AI_COMPOSE_ACTIVE_ENDPOINT = StringItem("ai_compose_active_endpoint", "", exportable = false)

    // Legacy multi-endpoint list, kept only so [migrateAiProviders] can seed the new per-provider
    // fields below from whatever endpoint was active. Superseded by the unified AI Providers
    // screen, where each provider has exactly one chat slot instead of an arbitrary named list.
    @JvmField
    val AI_COMPOSE_ENDPOINTS = desu.inugram.helpers.ai.AiEndpointsConfig("ai_compose_endpoints")

    @JvmField
    val AI_CHAT_ACTIVE_PROVIDER = IntItem("ai_chat_active_provider", TRANSCRIBE_PROVIDER_GEMINI)

    // One API key per named provider, shared by chat and voice -- it's the same account either
    // way, so asking twice was pure duplication. Only the model differs per scope (a chat
    // completion model isn't a transcription model), so models stay split below.
    @JvmField
    val AI_PROVIDER_GROQ_KEY = StringItem("ai_provider_groq_key", "", exportable = false)

    @JvmField
    val AI_PROVIDER_GEMINI_KEY = StringItem("ai_provider_gemini_key", "", exportable = false)

    @JvmField
    val AI_PROVIDER_OPENAI_KEY = StringItem("ai_provider_openai_key", "", exportable = false)

    @JvmField
    val AI_CHAT_GROQ_MODEL = StringItem("ai_chat_groq_model", "llama-3.3-70b-versatile", exportable = false)

    @JvmField
    val AI_CHAT_GEMINI_MODEL = StringItem("ai_chat_gemini_model", "gemini-3.1-flash-lite", exportable = false)

    @JvmField
    val AI_CHAT_OPENAI_MODEL = StringItem("ai_chat_openai_model", "gpt-4o-mini", exportable = false)

    // When a named provider is active for both scopes, defaults to one shared model field; flip
    // off to pick a different model per scope (e.g. a fast chat model but a specific transcription
    // model for the same Gemini account).
    @JvmField
    val AI_SAME_MODEL_GROQ = BoolItem("ai_same_model_groq", true)

    @JvmField
    val AI_SAME_MODEL_GEMINI = BoolItem("ai_same_model_gemini", true)

    @JvmField
    val AI_SAME_MODEL_OPENAI = BoolItem("ai_same_model_openai", true)

    @JvmField
    val AI_CHAT_OPENROUTER_KEY = StringItem("ai_chat_openrouter_key", "", exportable = false)

    @JvmField
    val AI_CHAT_OPENROUTER_MODEL = StringItem("ai_chat_openrouter_model", "openai/gpt-4o-mini", exportable = false)

    @JvmField
    val AI_CHAT_CUSTOM_URL = StringItem("ai_chat_custom_url", "", exportable = false)

    @JvmField
    val AI_CHAT_CUSTOM_KEY = StringItem("ai_chat_custom_key", "", exportable = false)

    @JvmField
    val AI_CHAT_CUSTOM_MODEL = StringItem("ai_chat_custom_model", "", exportable = false)

    @JvmField
    val AI_CHAT_CUSTOM_NAME = StringItem("ai_chat_custom_name", "", exportable = false)

    @JvmField
    val AI_PROVIDERS_MIGRATED = BoolItem("ai_providers_migrated", false, exportable = false)

    @JvmField
    val AI_SHARED_PROVIDER_KEYS_MIGRATED = BoolItem("ai_shared_provider_keys_migrated", false, exportable = false)

    @JvmField
    val AI_SUMMARY_ENABLED = BoolItem("ai_summary_enabled", false)

    @JvmField
    val AI_REASONING_ENABLED = BoolItem("ai_reasoning_enabled", false)

    @JvmField
    val AI_REASONING_EFFORT = StringItem("ai_reasoning_effort", "medium")

    // Legacy single-string persona field, kept only so [migrateAiRoles] can seed the first
    // preset in [AI_ROLES] from whatever the user had typed here. Not read anywhere else.
    @JvmField
    val AI_ROLE = StringItem("ai_role", "Assistant", exportable = false)

    @JvmField
    val AI_ROLES = desu.inugram.helpers.ai.AiRolesConfig("ai_roles")

    @JvmField
    val AI_ACTIVE_ROLE = StringItem("ai_active_role", "", exportable = false)

    @JvmField
    val AI_ROLES_MIGRATED = BoolItem("ai_roles_migrated", false, exportable = false)

    @JvmField
    val AI_HISTORY_ENABLED = BoolItem("ai_history_enabled", true)

    @JvmField
    val AI_STREAM_ENABLED = BoolItem("ai_stream_enabled", true)

    @JvmField
    val AI_ONLY_ANSWER = BoolItem("ai_only_answer", false)

    @JvmField
    val AI_INSERT_QUOTE = BoolItem("ai_insert_quote", true)

    @JvmField
    val AI_TEMPERATURE = FloatItem("ai_temperature", 1.0f)

    // AI Transcription (Voice-to-Text)
    // Unified provider id space, shared by the AI Providers screen's chat and voice sections.
    // Cloudflare has no chat API here (voice-only); OpenRouter has no transcription API (chat-only).
    const val TRANSCRIBE_PROVIDER_GROQ = 0
    const val TRANSCRIBE_PROVIDER_GEMINI = 1
    const val TRANSCRIBE_PROVIDER_OPENAI = 2
    const val TRANSCRIBE_PROVIDER_CF = 3
    const val TRANSCRIBE_PROVIDER_CUSTOM = 4
    const val AI_PROVIDER_OPENROUTER = 5

    @JvmField
    val AI_TRANSCRIBE_ENABLED = BoolItem("ai_transcribe_enabled", false)

    @JvmField
    val AI_TRANSCRIBE_PROVIDER = IntItem("ai_transcribe_provider", TRANSCRIBE_PROVIDER_GROQ)

    @JvmField
    val AI_TRANSCRIBE_GROQ_KEY = StringItem("ai_transcribe_groq_key", "", exportable = false)

    @JvmField
    val AI_TRANSCRIBE_GROQ_MODEL = StringItem("ai_transcribe_groq_model", "whisper-large-v3-turbo", exportable = false)

    @JvmField
    val AI_TRANSCRIBE_GEMINI_KEY = StringItem("ai_transcribe_gemini_key", "", exportable = false)

    @JvmField
    val AI_TRANSCRIBE_GEMINI_MODEL = StringItem("ai_transcribe_gemini_model", "gemini-3.5-flash", exportable = false)

    @JvmField
    val AI_TRANSCRIBE_OPENAI_KEY = StringItem("ai_transcribe_openai_key", "", exportable = false)

    @JvmField
    val AI_TRANSCRIBE_OPENAI_MODEL = StringItem("ai_transcribe_openai_model", "whisper-1", exportable = false)

    @JvmField
    val AI_TRANSCRIBE_CF_ACCOUNT_ID = StringItem("ai_transcribe_cf_account_id", "", exportable = false)

    @JvmField
    val AI_TRANSCRIBE_CF_API_TOKEN = StringItem("ai_transcribe_cf_api_token", "", exportable = false)

    @JvmField
    val AI_TRANSCRIBE_CF_MODEL = StringItem("ai_transcribe_cf_model", "@cf/openai/whisper", exportable = false)

    @JvmField
    val AI_TRANSCRIBE_CUSTOM_URL = StringItem("ai_transcribe_custom_url", "", exportable = false)

    @JvmField
    val AI_TRANSCRIBE_CUSTOM_KEY = StringItem("ai_transcribe_custom_key", "", exportable = false)

    @JvmField
    val AI_TRANSCRIBE_CUSTOM_MODEL = StringItem("ai_transcribe_custom_model", "", exportable = false)

    @JvmField
    val AI_TRANSCRIBE_CUSTOM_NAME = StringItem("ai_transcribe_custom_name", "", exportable = false)

    @JvmField
    val AI_TRANSCRIBE_PROMPT = StringItem("ai_transcribe_prompt", "")

    // ISO code of the language spoken in voice messages; empty means the provider guesses. Whisper
    // decides from the first seconds of audio, which is exactly where short or noisy voice notes go
    // wrong, so pinning this is the single biggest accuracy win available on the transcription path.
    @JvmField
    val AI_TRANSCRIBE_LANGUAGE = StringItem("ai_transcribe_language", "")

    @JvmField
    val HIDE_RICH_EDITOR_BUTTON = BoolItem("hide_rich_editor_button", false)

    @JvmField
    val HIDE_MESSAGE_SUMMARY = BoolItem("hide_message_summary", false)

    @JvmField
    val HIDE_IV_SUMMARY = BoolItem("hide_iv_summary", false)

    @JvmField
    val HIDE_REPOST_TO_STORY = BoolItem("hide_repost_to_story", true)

    @JvmField
    val HIDE_PAID_REACTION_UPSELL = BoolItem("hide_paid_reaction_upsell", true)

    @JvmField
    val HIDE_CAPTION_LIMIT_UPSELL = BoolItem("hide_caption_limit_upsell", false)

    @JvmField
    val HIDE_ATTACH_PREMIUM_BADGES = BoolItem("hide_attach_premium_badges", false)

    @JvmField
    val HIDE_EMOJI_PREMIUM_UPSELL = BoolItem("hide_emoji_premium_upsell", false)

    @JvmField
    val HIDE_HASHTAG_SUGGESTIONS = BoolItem("hide_hashtag_suggestions", true)

    @JvmField
    val HIDE_GIFT_BUTTON_INPUT = BoolItem("hide_gift_button_input", false)

    @JvmField
    val HIDE_GIFT_CARDS_IN_CHAT = BoolItem("hide_gift_cards_in_chat", false)

    @JvmField
    val HIDE_GIVEAWAYS = BoolItem("hide_giveaways", false)

    @JvmField
    val HIDE_CHANNEL_RECOMMENDATIONS = BoolItem("hide_channel_recommendations", false)

    @JvmField
    val DISABLE_CALL_RATING = BoolItem("disable_call_rating", false)

    @JvmField
    val HIDE_GROUP_STICKER_PACK = BoolItem("hide_group_sticker_pack", false)

    @JvmField
    val DISABLE_PROFILE_SCROLL_SNAP = BoolItem("disable_profile_scroll_snap", true)

    @JvmField
    val REDUCE_PROFILE_MOTION = BoolItem("reduce_profile_motion", true)

    @JvmField
    val PROFILE_PREFER_MEDIA_TAB = BoolItem("profile_prefer_media_tab", true)

    @JvmField
    val DISABLE_MOTION_PHOTOS = BoolItem("disable_motion_photos", true)

    @JvmField
    val DISABLE_VOLUME_PLAY_VIDEO = BoolItem("disable_volume_play_video", true)

    @JvmField
    val DISABLE_QUICK_SHARE = BoolItem("disable_quick_share", true)

    @JvmField
    val HIDE_CHANNEL_SHARE_BUTTON = BoolItem("hide_channel_share_button", false)

    @JvmField
    val HIDE_PROFILE_STORY_BUTTON = BoolItem("hide_profile_story_button", false)

    @JvmField
    val HIDE_PROFILE_GIFT_BUTTON = BoolItem("hide_profile_gift_button", false)

    @JvmField
    val HIDE_PROFILE_LIVE_ACTIONS_BUTTON = BoolItem("hide_profile_live_actions_button", false)

    @JvmField
    val HIDE_PREMIUM_BADGE = BoolItem("hide_premium_badge", false)

    @JvmField
    val HIDE_COLLECTIBLE_STATUS = BoolItem("hide_collectible_status", false)

    @JvmField
    val HIDE_STARS_RATING = BoolItem("hide_stars_rating", false)

    @JvmField
    val HIDE_VERIFICATION_BADGE = BoolItem("hide_verification_badge", false)

    @JvmField
    val HIDE_PROFILE_COLORFUL_BACKGROUND = BoolItem("hide_profile_colorful_background", false)

    @JvmField
    val HIDE_GIFTS_AROUND_AVATAR = BoolItem("hide_gifts_around_avatar", false)

    @JvmField
    val HIDE_PROFILE_GIFTS_TAB = BoolItem("hide_profile_gifts_tab", false)

    @JvmField
    val HIDE_SIMILAR_CHANNELS_TAB = BoolItem("hide_similar_channels_tab", false)

    @JvmField
    val HIDE_PROFILE_ICONS = BoolItem("hide_profile_icons", false)

    @JvmField
    val DISABLE_PROFILE_MUSIC_AUTOPLAY = BoolItem("disable_profile_music_autoplay", true)

    @JvmField
    val HIDE_REACTIONS_ENTRY = BoolItem("hide_reactions_entry", false)

    @JvmField
    val HIDE_SUGGESTION_BIRTHDAY_SETUP = BoolItem("hide_suggestion_birthday_setup", false)

    @JvmField
    val HIDE_SUGGESTION_BIRTHDAY_CONTACTS = BoolItem("hide_suggestion_birthday_contacts", false)

    @JvmField
    val HIDE_SUGGESTION_PASSWORD = BoolItem("hide_suggestion_password", false)

    @JvmField
    val HIDE_SUGGESTION_PHONE = BoolItem("hide_suggestion_phone", false)

    @JvmField
    val HIDE_SUGGESTION_PREMIUM = BoolItem("hide_suggestion_premium", true)

    @JvmField
    val HIDE_SUGGESTION_CUSTOM = BoolItem("hide_suggestion_custom", false)

    @JvmField
    val HIDE_PROXY_SPONSOR_CHAT = BoolItem("hide_proxy_sponsor_chat", true)

    @JvmField
    val HIDE_PSA_PROMO_CHAT = BoolItem("hide_psa_promo_chat", false)

    @JvmField
    val HIDE_GIFT_AUCTIONS_HINT = BoolItem("hide_gift_auctions_hint", false)

    @JvmField
    val HIDE_CACHE_HINT = BoolItem("hide_cache_hint", false)

    @JvmField
    val DELETE_FOR_BOTH_MESSAGES = BoolItem("delete_for_both_messages", true)

    @JvmField
    val DELETE_FOR_BOTH_DMS = BoolItem("delete_for_both_dms", false)

    @JvmField
    val DELETE_FOR_BOTH_GROUPS = BoolItem("delete_for_both_groups", false)

    @JvmField
    val DOUBLE_TAP_ACTION_INCOMING = IntItem("double_tap_action_incoming", 1)

    @JvmField
    val DOUBLE_TAP_ACTION_OUTGOING = IntItem("double_tap_action_outgoing", 1)

    @JvmField
    val DOUBLE_TAP_ACTION_CHANNEL = IntItem("double_tap_action_channel", DoubleTapActionHelper.INHERIT_INCOMING)

    @JvmField
    val DOUBLE_TAP_DELAY = IntItem("double_tap_delay", 220)

    @JvmField
    val STICKER_SIZE = FloatItem("sticker_size", 14.0f)

    @JvmField
    val NO_STICKER_EXTRA_PADDING = BoolItem("no_sticker_extra_padding", true)

    class FoldersDisplayModeItem : IntItem("folders_display_mode", TITLES) {
        companion object {
            const val TITLES = 1
            const val TITLES_AND_ICONS = 2
            const val ICONS_ONLY = 3
        }
    }

    @JvmField
    val FOLDERS_DISPLAY_MODE = FoldersDisplayModeItem()

    class FoldersUnreadCounterModeItem : IntItem("folders_unread_counter_mode", REGULAR) {
        companion object {
            const val HIDE = 0
            const val REGULAR = 1
            const val EXCLUDE_MUTED = 2
            const val EXCLUDE_MUTED_NON_DMS = 3
        }
    }

    @JvmField
    val FOLDERS_UNREAD_COUNTER_MODE = FoldersUnreadCounterModeItem()

    @JvmField
    val HIDE_ALL_CHATS_TAB = BoolItem("hide_all_chats_tab", false)

    @JvmField
    val TAB_INDICATOR_STROKE = BoolItem("tab_indicator_stroke", false)

    @JvmField
    val FOLDERS_AT_BOTTOM = BoolItem("folders_at_bottom", false)

    @JvmField
    val HIDE_ARCHIVE_FROM_CHAT_LIST = BoolItem("hide_archive_from_chat_list", false)

    class CommunityDisplayModeItem : IntItem("community_display_mode", REGULAR) {
        companion object {
            const val REGULAR = 1
            const val LONG_TAP = 2
            const val INVISIBLE = 3
        }
    }

    @JvmField
    val COMMUNITY_DISPLAY_MODE = CommunityDisplayModeItem()

    class DialogsTitleTextItem : IntItem("dialogs_title_text", INUGRAM) {
        companion object {
            const val INUGRAM = 1
            const val USERNAME = 2
            const val FIRST_NAME = 3
            const val CHATS = 4
            const val FOLDER = 5
        }
    }

    @JvmField
    val DIALOGS_TITLE_TEXT = DialogsTitleTextItem()

    @JvmField
    val DIALOGS_TITLE_TEXT_OVERRIDE_ARCHIVE = BoolItem("dialogs_title_text_override_archive", false)

    class StickerTimeModeItem : IntItem("sticker_time_mode", SHOW) {
        companion object {
            const val SHOW = 1;
            const val HIDE_TIME = 2;
            const val HIDE_INCOMING = 3;
            const val HIDE_FULL = 4;
        }

        fun isHideTime(): Boolean = value == HIDE_TIME
        fun isHideIncoming(): Boolean = value == HIDE_INCOMING
        fun isHideFull(): Boolean = value == HIDE_FULL
    }

    @JvmField
    val STICKER_TIME_MODE = StickerTimeModeItem()

    @JvmField
    val CALL_CONFIRMATION = BoolItem("call_confirmation", true)

    @JvmField
    val HD_BLUETOOTH_CALL_AUDIO = BoolItem("hd_bluetooth_call_audio", true)

    @JvmField
    val FORCE_RELAY_CALLS = BoolItem("force_relay_calls", false)

    @JvmField
    val PRESENCE_LOGGER_NOTIFY = BoolItem("presence_logger_notify", false)

    // Days to keep local presence logs. 0 = never auto-clear. Mirrors DeletedMessagesTtlItem.
    class PresenceLogsTtlItem : IntItem("presence_logs_ttl", NEVER) {
        companion object {
            const val NEVER = 0
            const val ONE_DAY = 1
            const val ONE_WEEK = 7
            const val ONE_MONTH = 30
        }
    }

    @JvmField
    val PRESENCE_LOGS_TTL = PresenceLogsTtlItem()

    @JvmField
    val CONFIRM_INTERNAL_LINKS = BoolItem("confirm_internal_links", false)

    @JvmField
    val DISABLE_BROWSER_SWIPE_COLLAPSE = BoolItem("disable_browser_swipe_collapse", true)

    @JvmField
    val CONFIRM_REACTION_NON_MEMBER = BoolItem("confirm_reaction_non_member", false)

    @JvmField
    val HIDE_CALL_ACTION_BUTTON = BoolItem("hide_call_action_button", true)

    class ProfileIdModeItem : IntItem("profile_id_mode", BOT_API_ID) {
        companion object {
            const val OFF = 0
            const val TELEGRAM_ID = 1
            const val BOT_API_ID = 2
        }
    }

    @JvmField
    val PROFILE_ID_MODE = ProfileIdModeItem()

    @JvmField
    val SHOW_PROFILE_REG_DATE = BoolItem("show_profile_reg_date", true)

    @JvmField
    val DISABLE_CHAT_BUBBLES = BoolItem("disable_chat_bubbles", true)

    @JvmField
    val WEB_PREVIEW_REPLACEMENTS_ENABLED = BoolItem("web_preview_replacements_enabled", true)

    @JvmField
    val WEB_PREVIEW_REPLACEMENTS = StringItem("web_preview_replacements", "")

    @JvmField
    val STRIP_TRACKING_PARAMS_ON_OPEN = BoolItem("strip_tracking_params", true)

    @JvmField
    val STRIP_TRACKING_PARAMS_ON_PASTE = BoolItem("strip_tracking_params_on_paste", true)

    @JvmField
    val DISABLE_INTRO_STICKER = BoolItem("disable_intro_sticker", true)

    @JvmField
    val DISABLE_DRAFT_UPLOAD = BoolItem("disable_draft_upload", false)

    @JvmField
    val ROUND_DEFAULT_CAMERA = IntItem("round_default_camera", 1) // 1=Front, 2=Rear, 3=Ask

    @JvmField
    val ROUND_RECORDER_KEEP_ZOOM = BoolItem("round_recorder_keep_zoom", false)

    @JvmField
    val ROUND_RECORDER_ZOOM_SLIDER = BoolItem("round_recorder_zoom_slider", true)

    @JvmField
    val ROUND_RECORDER_ZOOM_BUTTONS = BoolItem("round_recorder_zoom_buttons", true)

    @JvmField
    val ROUND_RECORDER_EXPONENTIAL_ZOOM = BoolItem("round_recorder_exponential_zoom", true)

    @JvmField
    val ROUND_RECORDER_DUAL_CAMERA = BoolItem("round_recorder_dual_camera", true)

    // todo: remove in 40
    class NonIslandSplitFromTabBarsItem(key: String) : BoolItem(key, false) {
        override fun read(prefs: SharedPreferences): Boolean {
            if (prefs.contains(key)) return prefs.getBoolean(key, default)
            if (!prefs.contains("non_island_tab_bars")) return default
            val migrated = prefs.getBoolean("non_island_tab_bars", false)
            prefs.edit { putBoolean(key, migrated) }
            return migrated
        }
    }

    @JvmField
    val NON_ISLAND_FOLDERS_BAR = NonIslandSplitFromTabBarsItem("non_island_folders_bar")

    @JvmField
    val NON_ISLAND_SHARED_MEDIA_TABS = NonIslandSplitFromTabBarsItem("non_island_shared_media_tabs")

    @JvmField
    val NON_ISLAND_GLOBAL_SEARCH = BoolItem("non_island_global_search", false)

    @JvmField
    val NON_ISLAND_CHAT_ELEMENTS = BoolItem("non_island_chat_elements", false)

    @JvmField
    val HIDE_FADE_VIEW = BoolItem("hide_fade_view", false)

    @JvmField
    val DISABLE_SCRIM_BLUR = BoolItem("disable_scrim_blur", false)

    @JvmField
    val DISABLE_GLASS_GLARE = BoolItem("disable_glass_glare", true)

    // Channel posts stretch to the full available width instead of a narrow auto-sized bubble.
    // Ported from exteraless (https://github.com/exteraless/exteraless) -- see WideChannelPostLayout.kt.
    // Only applies to actual broadcast-channel posts -- there is no "Feed" surface in this fork
    // to apply a separate wide-in-feed variant to (exteraless's WIDE_FEED_POSTS counterpart gates
    // on a searchType==4 that only exists alongside their own Feed feature).
    @JvmField
    val WIDE_CHANNEL_POSTS = BoolItem("wide_channel_posts", false)

    @JvmField
    val REDUCE_MENU_MOTION = BoolItem("reduce_menu_motion", true)

    @JvmField
    val PROFILE_PHOTO_GRADIENT_FADE = BoolItem("profile_photo_gradient_fade", false)

    @JvmField
    val SIMPLE_ATTACH_POPUP_ANIMATION = BoolItem("simple_attach_popup_animation", false)

    @JvmField
    val CHAT_VOICE_IN_ATTACH = BoolItem("chat_voice_in_attach", false)

    @JvmField
    val CHAT_VIEWS_BOTTOM = BoolItem("chat_views_bottom", false)

    @JvmField
    val DISABLE_CHAT_TITLE_PHONE = BoolItem("disable_chat_title_phone", true)

    @JvmField
    val SEARCH_FROM_GLOBAL = BoolItem("search_from_global", true)

    @JvmField
    val HIDE_MY_PHONE_NUMBER = BoolItem("hide_my_phone_number", true)

    @JvmField
    val REACTIONS_IN_ROW = IntItem("reactions_in_row", 8)

    @JvmField
    val CHAT_INPUT_MAX_LINES = IntItem("chat_input_max_lines", 8)

    @JvmField
    val REACTION_BAR_BELOW = BoolItem("reaction_bar_below", false)

    @JvmField
    val PINNED_REACTIONS_ENABLED = BoolItem("pinned_reactions_enabled", false)

    @JvmField
    val PINNED_REACTIONS = PinnedReactionsHelper.ConfigItem("pinned_reactions")

    @JvmField
    val OLD_MENTION_INDICATOR = BoolItem("old_mention_indicator", true)

    @JvmField
    val SHOW_FORWARD_TIME = BoolItem("show_forward_time", true)

    class ForwardHeaderModeItem : IntItem("forward_header_mode", REGULAR) {
        override fun read(prefs: SharedPreferences): Int {
            if (prefs.contains(key)) return prefs.getInt(key, default)
            if (!prefs.contains("compact_forwarded")) return default
            val migrated = if (prefs.getBoolean("compact_forwarded", false)) COMPACT else REGULAR
            prefs.edit {
                putInt(key, migrated)
                remove("compact_forwarded")
            }
            return migrated
        }

        companion object {
            const val REGULAR = 0
            const val ICON = 1
            const val COMPACT = 2
        }
    }

    @JvmField
    val FORWARD_HEADER_MODE = ForwardHeaderModeItem()

    @JvmField
    val COMPACT_EDITED = BoolItem("compact_edited", false)

    @JvmField
    val SHOW_FORWARDS_COUNT = BoolItem("show_forwards_count", false)

    @JvmField
    val BUBBLE_TAILS = BoolItem("bubble_tails", true)

    @JvmField
    val SHOW_POLL_RESULTS_BEFORE_VOTE = BoolItem("show_poll_results_before_vote", false)

    @JvmField
    val INTERACTIVE_CHAT_PREVIEW = BoolItem("disable_chat_preview_expand", true)

    @JvmField
    val FORMATTING_POPUP = BoolItem("formatting_popup", true)

    @JvmField
    val SUGGEST_CUSTOM_EMOJI_AFTER = BoolItem("suggest_custom_emoji_after", true)

    class TextClassifierModeItem : IntItem("text_classifier_mode", IMPROVED) {
        companion object {
            const val NATIVE = 1
            const val IMPROVED = 2
            const val OFF = 3
        }
    }

    @JvmField
    val TEXT_CLASSIFIER_MODE = TextClassifierModeItem()

    @JvmField
    val FORMATTING_POPUP_ITEMS = FormattingPopupConfig("formatting_popup_items")

    @JvmField
    val MESSAGE_MENU_ITEMS = MessageMenuConfig("message_menu_items")

    @JvmField
    val MESSAGE_MENU_BOTTOM_ROW = BoolItem("message_menu_bottom_row", false)

    // false = bottom (default), true = top
    @JvmField
    val MESSAGE_MENU_QUICK_ACTIONS_TOP = BoolItem("message_menu_quick_actions_top", false)

    @JvmField
    val CHAT_MENU_ITEMS = ChatMenuConfig("chat_menu_items")

    @JvmField
    val PROFILE_SETTINGS_ROWS = ProfileMenuConfig("profile_settings_rows")

    @JvmField
    val PROFILE_INFO_ROWS = ProfileInfoMenuConfig("profile_info_rows")

    // hide entries from ProfileActivity's ⋮ overflow menu (default off = stock)
    @JvmField
    val HIDE_PROFILE_MENU_SEND_GIFT = BoolItem("hide_profile_menu_send_gift", false)

    @JvmField
    val HIDE_PROFILE_MENU_ARCHIVED_STORIES = BoolItem("hide_profile_menu_archived_stories", false)

    class ForwardLongTapItem : IntItem("forward_long_tap_action", CHOOSE_MODE) {
        companion object {
            const val OFF = 0
            const val CHOOSE_MODE = 1
            const val WITHOUT_AUTHOR = 2
            const val WITHOUT_CAPTION = 3
        }
    }

    @JvmField
    val FORWARD_LONG_TAP_ACTION = ForwardLongTapItem()

    class ReplyLongTapItem : IntItem("reply_long_tap_action", OFF) {
        companion object {
            const val OFF = 0
            const val CHOOSE_MODE = 1
            const val REPLY_IN = 2
            const val REPLY_IN_DMS = 3
        }
    }

    @JvmField
    val REPLY_LONG_TAP_ACTION = ReplyLongTapItem()

    class RepeatModeItem : IntItem("repeat_mode", COPY) {
        companion object {
            const val COPY = 0
            const val FORWARD = 1
            const val ASK = 2
        }
    }

    @JvmField
    val REPEAT_MODE = RepeatModeItem()

    @JvmField
    val ANIMATION_SPEED = FloatItem("animation_speed", 1.0f)

    class IconReplacementItem : IntItem("icon_replacement", OFF) {
        companion object {
            const val OFF = 0
            const val SOLAR = 1
            const val VKUI = 2
            const val PHOSPHOR = 3
        }
    }

    @JvmField
    val ICON_REPLACEMENT = IconReplacementItem()

    // Header centering, top of the group: centers action bar titles on every screen EXCEPT
    // chats. Everything below is nested under it and is meaningless on its own - InuUtils owns
    // the whole decision matrix, nothing else reads these items directly.
    @JvmField
    val CENTER_TITLE_MAIN = BoolItem("center_title_main", false)

    // Extends CENTER_TITLE_MAIN into chat/channel headers: title and subtitle move to the middle
    // of the pill, the avatar stays pinned to its left edge.
    @JvmField
    val CENTER_TITLE_CHATS = BoolItem("center_title_chats", false)

    // Moves the avatar to the right end of the centered pill instead of its left. Loses to
    // IOS_CHAT_HEADER_AVATAR_SLOT when both are on.
    @JvmField
    val CENTER_TITLE_RIGHT_AVATAR = BoolItem("center_title_right_avatar", false)

    @JvmField
    val IOS_BOTTOM_NAVIGATION_BAR = BoolItem("ios_bottom_navigation_bar", false)

    @JvmField
    val IOS_CHATS_TAB_RETURNS_TO_FIRST_FOLDER = BoolItem("ios_chats_tab_returns_to_first_folder", false)

    // Compact pill: the centered chat pill shrinks to hug title/subtitle (and the avatar, unless
    // that moved out to the menu slot) instead of spanning the whole room between the back button
    // and the menu, like Telegram for iOS. Nested under CENTER_TITLE_CHATS.
    @JvmField
    val IOS_CHAT_HEADER = BoolItem("ios_chat_header", false)

    // Compact-pill-only: the avatar leaves the pill and takes over the action bar's overflow
    // ("...") slot - tap opens the profile, long press opens the chat menu.
    @JvmField
    val IOS_CHAT_HEADER_AVATAR_SLOT = BoolItem("ios_chat_header_avatar_slot", false)

    @JvmField
    val CHAT_TITLE_MARQUEE = BoolItem("chat_title_marquee", false)

    // Non-exportable run-record for [migrateCenteringGroup]. Independent of the keys it rewrites,
    // so a restored backup that resurrects the legacy layout can never re-fire it over a choice
    // the user has made since.
    @JvmField
    val CENTERING_GROUP_MIGRATED = BoolItem("centering_group_migrated", false, exportable = false)

    // Per-category local preservation of self-destruct content. All default off = stock behavior.
    @JvmField
    val SAVE_SELF_DESTRUCT_MEDIA = BoolItem("save_self_destruct_media", false)

    @JvmField
    val SAVE_SELF_DESTRUCT_TEXT = BoolItem("save_self_destruct_text", false)

    @JvmField
    val SAVE_VIEW_ONCE_MEDIA = BoolItem("save_view_once_media", false)

    // Only meaningful when SAVE_SELF_DESTRUCT_MEDIA/SAVE_VIEW_ONCE_MEDIA keeps a local copy: off
    // (default) still gates the view behind the stock one-time reveal/blur; on shows it as a
    // regular reopenable photo right away.
    @JvmField
    val VIEW_ONCE_SHOW_NORMAL = BoolItem("view_once_show_normal", false)

    @JvmField
    val SAVE_TIMED_MESSAGES = BoolItem("save_timed_messages", false)

    @JvmField
    val SAVE_ANY_STORY = BoolItem("save_any_story", false)

    @JvmField
    val SAVE_DELETED_MESSAGES = BoolItem("save_deleted_messages", false)

    @JvmField
    val SAVE_DELETED_PRIVATE = BoolItem("save_deleted_private", false)

    @JvmField
    val SAVE_DELETED_GROUPS = BoolItem("save_deleted_groups", false)

    @JvmField
    val SAVE_DELETED_CHANNELS = BoolItem("save_deleted_channels", false)

    @JvmField
    val SAVE_DELETED_BOTS = BoolItem("save_deleted_bots", false)

    @JvmField
    val SAVE_DELETED_OWN = BoolItem("save_deleted_own", true)

    @JvmField
    val ALLOW_FORWARD_RESTRICTED = BoolItem("allow_forward_restricted", false)

    @JvmField
    val ALLOW_SCREENSHOTS = BoolItem("allow_screenshots", false)

    @JvmField
    val SUPPRESS_SCREENSHOT_NOTIFICATION = BoolItem("suppress_screenshot_notification", false)

    @JvmField
    val HIDE_SPONSORED_MESSAGES = BoolItem("hide_sponsored_messages", true)

    @JvmField
    val SAVE_EDITED_MESSAGES = BoolItem("save_edited_messages", false)

    @JvmField
    val SAVE_USER_INFO = BoolItem("save_user_info", false)

    @JvmField
    val SHOW_EDIT_HISTORY_DIFF = BoolItem("show_edit_history_diff", false)

    @JvmField
    val SHOW_MUTUAL_CONTACT_ICON = BoolItem("show_mutual_contact_icon", true)

    @JvmField
    val SHOW_MUTUAL_CONTACT_IN_CHATS = BoolItem("show_mutual_contact_in_chats", true)

    @JvmField
    val MASK_SERVER_APP_NAME = BoolItem("mask_server_app_name", true)

    // Days to keep deleted/edited message cache. 0 = never auto-clear.
    class DeletedMessagesTtlItem : IntItem("deleted_messages_ttl", NEVER) {
        companion object {
            const val NEVER = 0
            const val ONE_DAY = 1
            const val ONE_WEEK = 7
            const val ONE_MONTH = 30
        }
    }

    @JvmField
    val DELETED_MESSAGES_TTL = DeletedMessagesTtlItem()

    @JvmField
    val REGEX_FILTER_ENABLED = BoolItem("regex_filter_enabled", false)

    @JvmField
    val REGEX_FILTER_PATTERNS = StringItem(
        "regex_filter_patterns",
        "(?i)(реклама|промокод|казино|знижка|ставк|підпишись|referral|crypto|binance|buy now)"
    )

    @JvmField
    val REGEX_FILTERS_JSON = StringItem("regex_filters_json", "[]")

    @JvmField
    val REGEX_FILTER_EXCLUSIONS_JSON = StringItem("regex_filter_exclusions_json", "[]")

    @JvmField
    val REGEX_FILTERS_MIGRATED = BoolItem("regex_filters_migrated", false, exportable = false)

    class RegexFilterModeItem : IntItem("regex_filter_mode", HIDE) {
        companion object {
            const val HIDE = 0
            const val SPOILER = 1
        }
    }

    @JvmField
    val REGEX_FILTER_MODE = RegexFilterModeItem()

    class NotificationIconItem : IntItem("notification_icon", TELEGRAM) {
        companion object {
            const val TELEGRAM = 0
            const val INUGRAM = 1
            const val OLD_ENTINYGRAM = 2
        }
    }

    @JvmField
    val NOTIFICATION_ICON = NotificationIconItem()

    class MapProviderItem : IntItem("map_provider", OSM_LITE) {
        companion object {
            const val GOOGLE = 0
            const val OSM_LITE = 2 // osmdroid raster renderer (pure java, no native libs)
        }
    }

    @JvmField
    val MAP_PROVIDER = MapProviderItem()

    class MapPreviewProviderItem : IntItem("map_preview_provider", DEFAULT) {
        companion object {
            const val DEFAULT = 0
            const val TELEGRAM = 1
            const val GOOGLE = 2
            const val YANDEX = 3
            const val DISABLED = 4
        }
    }

    @JvmField
    val MAP_PREVIEW_PROVIDER = MapPreviewProviderItem()

    class UpdatesEnabledItem : BoolItem("updates_enabled", true, exportable = false) {
        override fun read(prefs: SharedPreferences): Boolean {
            // compat, remove after a few months
            if (!prefs.contains(key) && prefs.contains("update_channel")) {
                val value = prefs.getInt("update_channel", 1) != 0
                prefs.edit { putBoolean(key, value) }
                return value
            }
            return prefs.getBoolean(key, default)
        }
    }

    @JvmField
    val UPDATES_ENABLED = UpdatesEnabledItem()

    // Opts into also matching #prerelease-tagged CI posts (see scripts/ci/upload.ts /
    // UpdateHelper.searchByTag). Off by default -- regular users only ever match #release, so a
    // beta/pre-release build can never get offered to someone who didn't ask for it.
    @JvmField
    val UPDATES_INCLUDE_BETA = BoolItem("updates_include_beta", false)

    @JvmField
    val EXTRA_DEBUG_LOGS = BoolItem("extra_debug_logs", false, exportable = false)

    // internal state
    @JvmField
    val VOICE_HINT_SHOWN = BoolItem("voice_hint_shown", false, exportable = false)

    @JvmField
    val MINIMIZE_STICKERS_CREATOR = BoolItem("minimize_stickers_creator", true, exportable = false)

    @JvmField
    val UPDATE_LAST_CHECK_MS = LongItem("update_last_check_ms", 0L, exportable = false)

    @JvmField
    val CLOUD_SYNC_ACCOUNT_ID = LongItem("cloud_sync_account_id", 0L, exportable = false)

    @JvmField
    val CLOUD_SYNC_AUTO = BoolItem("cloud_sync_auto", false)

    @JvmField
    val CLOUD_SYNC_AUTO_USER_SET = BoolItem("cloud_sync_auto_user_set", false, exportable = false)

    @JvmField
    val EVENT_LOG_CHAR_DIFF = BoolItem("event_log_char_diff", true)

    @JvmField
    val IN_PLACE_TRANSLATION = BoolItem("in_place_translation", true)

    @JvmField
    val TRANSLATE_WEB_PREVIEWS = BoolItem("translate_web_previews", true)

    @JvmField
    val KEEP_ORIGINAL_AFTER_TRANSLATION = BoolItem("keep_original_after_translation", false)

    @JvmField
    val TRANSLATE_AUTO_DETECT_LANG = BoolItem("translate_auto_detect_lang", true)

    // Force translation even for messages Telegram considers to be in the user's own language
    @JvmField
    val FORCE_TRANSLATE = BoolItem("force_translate", false)

    // OwlGram-style "auto-translate everything": the DEFAULT translating state for a dialog the
    // user has not decided about yet. Deliberately not a second list of chats - stock already
    // persists every explicit per-dialog choice (TranslateController.translatingDialogs, saved as
    // `translating_dialog_languages2`), and that override wins over this default. So switching a
    // single chat off from its own translate bar is remembered as an exception for free, the same
    // way OwlGram's AutoTranslateConfig behaves, without a parallel exceptions store to keep in
    // sync. Per-topic granularity is the one thing stock's dialog-keyed map cannot express.
    @JvmField
    val AUTO_TRANSLATE_ALL = BoolItem("auto_translate_all", false)

    @JvmField
    val TRANSLATE_OUTGOING = BoolItem("translate_outgoing", false)

    // Third-party translation providers (0 = Telegram API, stock behavior)
    @JvmField
    val TRANSLATE_PROVIDER = IntItem("translate_provider", 0)

    // Stock only shows the chat-bar "Translate to X?" banner after on-device language detection
    // has accumulated 6 sampled messages agreeing the dialog is in a foreign language (2 if the
    // chat has autotranslation on) -- see TranslateController.checkDialogTranslatable. A single
    // opened post/deep-link rarely reaches that sample size, so the banner silently never
    // appears even though the per-message Translate button (which has no such threshold) works
    // fine. This skips the sample-size wait: the dialog is marked translatable off the very first
    // confidently-detected foreign-language message.
    @JvmField
    val INSTANT_TRANSLATE_BANNER = BoolItem("instant_translate_banner", false)

    // A channel/user can opt out of the translate banner + button-menu suggestion via
    // `translations_disabled` (set by the channel owner in Telegram's own channel settings).
    // Stock respects it only for the banner -- the per-message context-menu Translate button
    // ignores it entirely already. This makes the banner ignore it too, for consistency.
    @JvmField
    val IGNORE_TRANSLATIONS_DISABLED = BoolItem("ignore_translations_disabled", false)

    @JvmField
    val TRANSLATE_DEEPL_KEY = StringItem("translate_deepl_key", "", exportable = false)

    @JvmField
    val TRANSLATE_YANDEX_KEY = StringItem("translate_yandex_key", "", exportable = false)

    @JvmField
    val TRANSLATE_MICROSOFT_KEY = StringItem("translate_microsoft_key", "", exportable = false)

    @JvmField
    val TRANSLATE_MICROSOFT_REGION = StringItem("translate_microsoft_region", "", exportable = false)

    @JvmField
    val TRANSLATE_LLM_URL = StringItem("translate_llm_url", "", exportable = false)

    @JvmField
    val TRANSLATE_LLM_KEY = StringItem("translate_llm_key", "", exportable = false)

    @JvmField
    val TRANSLATE_LLM_MODEL = StringItem("translate_llm_model", "")

    @JvmField
    val TRANSLATE_LLM_PROMPT = StringItem("translate_llm_prompt", "")

    // How many preceding messages of the same chat are handed to the LLM as conversation context
    // (0 = off). They are quoted for reference and never translated themselves. This is what a
    // plain per-message translator cannot do: pronouns, grammatical gender, honorifics and
    // one-word replies ("yes", "his") only resolve correctly when the model can see what was said
    // before. Costs tokens, hence the explicit size rather than a boolean.
    @JvmField
    val TRANSLATE_LLM_CONTEXT = IntItem("translate_llm_context", 0)

    // Sampling temperature for the LLM provider. Translation wants determinism, so the default is
    // low; raising it helps only with deliberately loose/idiomatic rewrites.
    @JvmField
    val TRANSLATE_LLM_TEMPERATURE = FloatItem("translate_llm_temperature", 0.3f)

    @JvmField
    val ACCOUNT_ORDER = StringItem("account_order", "", exportable = false)

    @JvmField
    val ACCOUNT_SWITCH_SHORTCUT = BoolItem("account_switch_shortcut", false)

    @JvmField
    val FASTER_DOWNLOADS = BoolItem("faster_downloads", true)

    @JvmField
    val FASTER_UPLOADS = BoolItem("faster_uploads", true)

    @JvmField
    val KEEP_DOWNLOADS_IN_BACKGROUND = BoolItem("keep_downloads_in_background", false)

    @JvmField
    val BLOCK_SLEEP_WHILE_DOWNLOADING = BoolItem("block_sleep_while_downloading", false)

    @JvmField
    val BIOMETRIC_CONFIRM_DELETE_CHAT = BoolItem("biometric_confirm_delete_chat", false)

    @JvmField
    val BIOMETRIC_CONFIRM_LOGOUT = BoolItem("biometric_confirm_logout", false)

    @JvmField
    val BIOMETRIC_ALLOW_DEVICE_CREDENTIAL = BoolItem("biometric_allow_device_credential", false)

    @JvmField
    val BIOMETRIC_LOCK_ARCHIVE = BoolItem("biometric_lock_archive", false)

    @JvmField
    val BIOMETRIC_LOCK_ARCHIVE_EVERY_TIME = BoolItem("biometric_lock_archive_every_time", false)

    // --- ghost mode (invisible mode) ---
    // Real, persisted master switch: GHOST_MODE_ENABLED gates every sub-toggle below (see
    // GhostHelper.shouldSuppress). While it's off, nothing is suppressed no matter what the
    // sub-toggles say -- they keep their own values so turning the master back on resumes
    // exactly what was configured, instead of forcing everything to "all on". All suppression
    // defaults stay off so a fresh install remains stock-identical without needing a gate.
    // GHOST_READ_ON_SEND defaults to true, matching AyuGram's markReadAfterSend.
    // Run-record for [migrateGhostMasterFlag]. Never exported: a backup taken before the
    // migration must not be able to un-set it and re-arm the stomp on restore.
    @JvmField
    val GHOST_MASTER_FLAG_MIGRATED = BoolItem("ghost_master_flag_migrated", false, exportable = false)

    // Run-record for [migrateGhostModeEnabledDefault]. Never exported, same reasoning as
    // GHOST_MASTER_FLAG_MIGRATED above.
    @JvmField
    val GHOST_MODE_ENABLED_MIGRATED = BoolItem("ghost_mode_enabled_migrated", false, exportable = false)

    @JvmField
    val GHOST_MODE_ENABLED = BoolItem("ghost_mode_enabled", false)

    @JvmField
    val GHOST_HIDE_READ = BoolItem("ghost_hide_read", false)

    @JvmField
    val GHOST_READ_ON_SEND = BoolItem("ghost_read_on_send", true)

    @JvmField
    val GHOST_MARK_READ_LOCALLY = BoolItem("ghost_mark_read_locally", true)

    @JvmField
    val GHOST_HIDE_VOICE_READ = BoolItem("ghost_hide_voice_read", false)

    @JvmField
    val GHOST_HIDE_STORY_READ = BoolItem("ghost_hide_story_read", false)

    @JvmField
    val GHOST_HIDE_TYPING = BoolItem("ghost_hide_typing", false)

    class GhostPresenceModeItem : IntItem("ghost_presence_mode", NORMAL) {
        // Migrate the old GHOST_HIDE_ONLINE boolean toggle (removed when presence became a
        // 3-way mode): without this, everyone who had "hide online" on under the old scheme
        // silently reverted to NORMAL on the update that introduced this enum, since the new
        // key never existed for them and just fell back to its own default.
        override fun read(prefs: SharedPreferences): Int {
            if (prefs.contains(key)) return prefs.getInt(key, default)
            if (!prefs.contains("ghost_hide_online")) return default
            val migrated = if (prefs.getBoolean("ghost_hide_online", true)) HIDDEN else NORMAL
            prefs.edit(commit = true) {
                putInt(key, migrated)
                remove("ghost_hide_online")
            }
            return migrated
        }

        companion object {
            const val NORMAL = 0
            const val HIDDEN = 1
            const val DELAYED = 2
        }
    }

    @JvmField
    val GHOST_PRESENCE_MODE = GhostPresenceModeItem()

    // Independent of GHOST_PRESENCE_MODE (AyuGram's design: "don't send online" and "auto go
    // offline" are two separate switches). Telegram's server flips the account online implicitly
    // on any live action (sending a message, a reaction, ...), which no client-side packet filter
    // can prevent -- so even a HIDDEN-presence user leaks "online" until something re-asserts
    // offline. Previously only the DELAYED mode scheduled that re-assert, which meant HIDDEN --
    // the strictest setting -- was paradoxically the least protected.
    @JvmField
    val GHOST_AUTO_OFFLINE = BoolItem("ghost_auto_offline", false)

    // Run-record for [migrateGhostAutoOffline]. Non-exportable, same reasoning as the other ghost
    // run-records above.
    @JvmField
    val GHOST_AUTO_OFFLINE_MIGRATED = BoolItem("ghost_auto_offline_migrated", false, exportable = false)

    @JvmField
    val GHOST_WHITELIST_DIALOGS = StringSetItem("ghost_whitelist_dialogs", emptySet())

    @JvmField
    val LOCAL_PREMIUM = BoolItem("local_premium", false)

    @JvmField
    val LOCAL_CUSTOM_EMOJI = BoolItem("local_custom_emoji", false)

    @JvmField
    val HIDE_DEV_BADGES = BoolItem("hide_dev_badges", false)

    @JvmField
    val HIDDEN_STAR_GIFTS = BoolItem("hidden_star_gifts", false)

    @JvmField
    val DELETED_MESSAGES_TRANSPARENT = BoolItem("deleted_messages_transparent", false)

    @JvmField
    val DELETED_MARK_COLOR = IntItem("deleted_mark_color", 0)

    class DeletedMarkStyleItem : IntItem("deleted_mark_style", TRASH_BIN) {
        companion object {
            const val NOTHING = 0
            const val TRASH_BIN = 1
            const val CROSS = 2
            const val EYE_CROSSED = 3
            const val TRASH_BIN_OUTLINE = 4
        }
    }

    @JvmField
    val DELETED_MARK_STYLE = DeletedMarkStyleItem()

    @JvmField
    val OPEN_BY_USER_ID = BoolItem("open_by_user_id", true)

    @JvmField
    val SELECTION_BOTTOM_NO_QUOTE = BoolItem("selection_bottom_no_quote", false)
}
