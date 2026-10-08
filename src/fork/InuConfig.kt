package desu.inugram

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import desu.inugram.helpers.chat.DoubleTapActionHelper
import desu.inugram.helpers.chat.PinnedReactionsHelper
import desu.inugram.helpers.font.FontConfig
import desu.inugram.helpers.menu.ChatMenuConfig
import desu.inugram.helpers.menu.DialogsMenuConfig
import desu.inugram.helpers.menu.DrawerMenuConfig
import desu.inugram.helpers.menu.MainTabsMenuConfig
import desu.inugram.helpers.menu.MessageMenuConfig
import desu.inugram.helpers.menu.ProfileInfoMenuConfig
import desu.inugram.helpers.menu.ProfileMenuConfig
import desu.inugram.helpers.pillstack.PillStackMenuConfig
// #if PLUGINS
import desu.inugram.helpers.plugins.ui.PluginActionSettingsConfig
// #endif
import desu.inugram.ui.FormattingPopupConfig

object InuConfig {
    private const val PREFS_NAME = "inugram"

    lateinit var prefs: SharedPreferences
    private val _items = mutableListOf<Item<*>>()
    val items: List<Item<*>> get() = _items

    fun load(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        FontConfig.register() // entiny: ensure config items register before load
        for (item in _items) item.load(prefs)
        migrateGhostMasterFlag()
        migrateGhostModeEnabledDefault()
        migrateGhostAutoOffline()
        migrateSelfDestructCategories()
        migrateFeedEnabled(context)
        desu.inugram.helpers.vibration.HapticFeedbackConstants.sync()
    }

    // entiny: the feed became opt-in; anyone who ever touched it keeps it on
    private fun migrateFeedEnabled(context: Context) {
        if (prefs.contains(FEED_ENABLED.key)) return
        val touched = listOf(
            FEED_EXCLUDED_CHANNELS.key, FEED_INCLUDE_ARCHIVED.key, FEED_MARK_READ_ON_SCROLL.key,
        ).any { prefs.contains(it) } ||
            DIALOGS_MENU_ITEMS.value.any { it.item == DialogsMenuConfig.Item.FEED && it.enabled } ||
            BOTTOM_TABS_ORDER.value.any { it.item == MainTabsMenuConfig.Item.FEED && it.enabled } ||
            java.io.File(context.applicationInfo.dataDir, "shared_prefs/inu_feed.xml").exists()
        FEED_ENABLED.value = touched
    }

    private fun migrateGhostAutoOffline() {
        if (GHOST_AUTO_OFFLINE_MIGRATED.value) return
        GHOST_AUTO_OFFLINE_MIGRATED.value = true
        if (GHOST_PRESENCE_MODE.value == GhostPresenceModeItem.DELAYED) {
            GHOST_AUTO_OFFLINE.value = true
        }
    }

    // entiny: ghost mode migrated from single master flag to independent sub-toggles; old master flag lost on update since sub-toggles default to false
    private fun migrateGhostMasterFlag() {
        if (GHOST_MASTER_FLAG_MIGRATED.value) {
            // entiny: legacy ghost_mode key resurfaced after migration -- drop it without affecting sub-toggles
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
        // entiny: record migration run first, then clear legacy key; prevents double-stomp if process dies
        GHOST_MASTER_FLAG_MIGRATED.value = true
        if (prefs.contains("ghost_mode")) prefs.edit(commit = true) { remove("ghost_mode") }
    }

    // entiny: ghost mode enabled default depends on whether any sub-toggles were already active
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

    // entiny: log ghost migration using GhostHelper convention (Log.d adb, FileLog.d for release testers)
    private fun logGhostMigration(what: String) {
        android.util.Log.d("GhostMode", "migrateGhostMasterFlag $what")
        org.telegram.messenger.FileLog.d("GhostMode: migrateGhostMasterFlag $what")
    }

    // entiny: self-destruct categories migrated from two bools; if either old flag was on, turn on all categories to avoid narrowing user choices
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

        abstract val prefType: PrefType

        open var value: T
            get() = currentValue
            set(v) {
                currentValue = v
                saveAsync()
            }

        init {
            _items.add(this)
        }

        fun load(prefs: SharedPreferences) {
            currentValue = read(prefs)
        }

        // entiny: save() uses commit=true for atomic write; unsafeSet stages writes without commit
        fun save() {
            prefs.edit(commit = true) { write() }
        }

        fun saveAsync() {
            prefs.edit(commit = false) { write() }
        }

        // entiny: unsafeSet stages value+editor for batched writes without commit
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

    class ReactionsInRowItem : IntItem("reactions_in_row", 8) {
        // entiny: more columns than the screen holds shrinks emoji in the picker into dots
        fun maxFit(): Int {
            val dm = android.content.res.Resources.getSystem().displayMetrics
            val minDp = minOf(dm.widthPixels, dm.heightPixels) / dm.density
            return ((minDp - 12f) / 36f).toInt().coerceAtLeast(6)
        }

        override var value: Int
            get() = super.value.coerceIn(6, maxFit())
            set(v) {
                super.value = v
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
        // entiny: STRING is a placeholder for string sets because backup import/export checks the runtime prefs type
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

    @JvmField
    val HIDE_STORIES = BoolItem("hide_stories", false)

    @JvmField
    val SHOW_SECONDS = BoolItem("show_seconds", false)

    @JvmField
    val DISABLE_ROUNDING = BoolItem("disable_rounding", false)

    @JvmField
    val MATERIAL3_SWITCHES = BoolItem("material3_switches", false)

    @JvmField
    val MATERIAL3_SLIDERS = BoolItem("material3_sliders", false)

    @JvmField
    val MATERIAL3_FABS = BoolItem("material3_fabs", true)

    @JvmField
    val M3_SECTIONS_STYLE = BoolItem("m3_sections_style", false)
    val M3_ACCENT_ICONS = BoolItem("m3_accent_icons", false)

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

    @JvmField
    val PLUGINS_ENABLED = BoolItem("plugins_enabled", false)

    // json: ordered [{id, file, enabled}] — install id + source file + middleware order + per-plugin
    // enabled. the install ids live here and nowhere else, so losing it wipes every plugin's storage.
    // device-local, not exported
    @JvmField
    val PLUGINS_STATE = StringItem("plugins_state", "", exportable = false)

    @JvmField
    val PLUGINS_COMPACT_LIST = BoolItem("plugins_compact_list", false)

    // the "only install plugins you trust" warning was read and accepted: consent, not a
    // preference, so it is never carried over by an import
    @JvmField
    val PLUGINS_TRUSTED = BoolItem("plugins_trusted", false, exportable = false)

    // the dev server warning was read and accepted, and the receiver is up: consent again, and one
    // that grants anything with adb access an unreviewed install, so it is never carried over
    @JvmField
    val PLUGINS_DEV_MODE = BoolItem("plugins_dev_mode", false, exportable = false)

    // snapshot of theme state before Monet was enabled, "day|night|autoNightType"; empty = none
    @JvmField
    val MONET_PREV = StringItem("monet_prev", "", exportable = false)

    class PredictiveBackModeItem : IntItem("predictive_back_mode", OFF) {
        // entiny: predictive back mode migrated from boolean to 3-way; old disable flag maps to OFF
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

    class ClockFormatItem : IntItem("clock_format", SYSTEM) {
        companion object {
            const val SYSTEM = 0
            const val TWELVE_HOUR = 1
            const val TWENTY_FOUR_HOUR = 2
        }
    }

    @JvmField
    val CLOCK_FORMAT = ClockFormatItem()

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

    class AttachCameraModeItem : IntItem("attach_camera_mode", INSTANT) {
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
    val KEEP_CHATS_IN_STACK = BoolItem("keep_chats_in_stack", false)

    @JvmField
    val SORT_ALBUMS_BY_SIZE = BoolItem("sort_albums_by_size", true)

    @JvmField
    val ATTACH_CAMERA_SQUARE = BoolItem("attach_camera_square", true)

    @JvmField
    val DOWNLOAD_DIRECTORY = StringItem("download_directory", "entinyGram")

    @JvmField
    val AUTO_DISABLE_PROXY_ON_VPN = BoolItem("auto_disable_proxy_on_vpn", false)

    @JvmField
    val EXTRA_DEBUG_LOGS = BoolItem("extra_debug_logs", false, exportable = false)

    @JvmField
    val PROXY_SUPPRESSED_BY_VPN = BoolItem("proxy_suppressed_by_vpn", false, exportable = false)

    @JvmField
    val ANTICENSOR_WS_TUNNEL = BoolItem("anticensor_ws_tunnel", false, exportable = false)

    @JvmField
    val ANTICENSOR_WS_SECRET = StringItem("anticensor_ws_secret", "", exportable = false)

    @JvmField
    val ANTICENSOR_WS_PORT = IntItem("anticensor_ws_port", 0, exportable = false)

    @JvmField
    val LEAK_GUARD = BoolItem("leak_guard", false)

    @JvmField
    val SHOW_ALL_RECENT_STICKERS = BoolItem("show_all_recent_stickers", true)

    @JvmField
    val UNLIMITED_FAVORITE_STICKERS = BoolItem("unlimited_favorite_stickers", false)

    @JvmField
    val UNLIMITED_PINNED_CHATS = BoolItem("unlimited_pinned_chats", false)

    @JvmField
    val UNLIMITED_FOLDER_CHATS = BoolItem("unlimited_folder_chats", false)

    @JvmField
    val LOCAL_FOLDERS = BoolItem("local_folders", false)

    @JvmField
    val UNLIMITED_PINNED_CHATS_COUNT = IntItem("unlimited_pinned_chats_count", 20)

    @JvmField
    val HIDE_TRENDING_STICKERS = BoolItem("hide_trending_stickers", true)

    @JvmField
    val NAVIGATION_DRAWER = BoolItem("navigation_drawer", false)

    @JvmField
    val DRAWER_BACK_GESTURE = BoolItem("drawer_back_gesture", false)

    @JvmField
    val DRAWER_KEEP_OPTIONS_MENU = BoolItem("drawer_keep_options_menu", false)

    @JvmField
    val DRAWER_M3_SECTIONS = BoolItem("drawer_m3_sections", true)

    @JvmField
    val SHOW_DRAWER_ACCOUNTS = BoolItem("show_drawer_accounts", true)

    @JvmField
    val DRAWER_MENU_ITEMS = DrawerMenuConfig("drawer_menu_items")

    @JvmField
    val BOTTOM_TABS_HIDE = BoolItem("bottom_tabs_hide", false)

    @JvmField
    val BOTTOM_TABS_COMPACT_MODE = BoolItem("bottom_tabs_hide_compact_mode", false)

    @JvmField
    val BOTTOM_TABS_ORDER = MainTabsMenuConfig("bottom_tabs_order")

    @JvmField
    val BOTTOM_TABS_SHOW_TITLES = BoolItem("bottom_tabs_show_titles", true)
    @JvmField val BOTTOM_TABS_HIDE_TOP_SEARCH = BoolItem("bottom_tabs_hide_top_search", false)
    @JvmField val BOTTOM_TABS_SEARCH_SEPARATE = BoolItem("bottom_tabs_search_separate", false)

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
    val KEEP_KEYBOARD_WHILE_TYPING = BoolItem("keep_keyboard_while_typing", false)

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
    val INSTANT_MARK_REACTIONS_READ = BoolItem("instant_mark_reactions_read", false)

    @JvmField
    val DISABLE_BOT_DRAFT_TOP = BoolItem("disable_bot_draft_top", true)

    @JvmField
    val MENTION_SEPARATOR = StringItem("mention_separator", "")

    @JvmField
    val MENTION_SEPARATOR_FOR_BOTS = BoolItem("mention_separator_for_bots", false)

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
    val CONFIRM_SEND_VOICE = BoolItem("confirm_send_voice", false)

    @JvmField
    val CONFIRM_SEND_STICKER = BoolItem("confirm_send_sticker", false)

    @JvmField
    val CONFIRM_SEND_GIF = BoolItem("confirm_send_gif", false)

    @JvmField
    val HIDE_BOT_WEBVIEW_DIALOGS = BoolItem("hide_bot_webview_dialogs", true)

    @JvmField
    val HIDE_AI_EDITOR = BoolItem("hide_ai_editor", false)

    @JvmField
    val AI_CHAT_ACTIVE_PROVIDER = IntItem("ai_chat_active_provider", TRANSCRIBE_PROVIDER_GEMINI)

    @JvmField
    val AI_PROVIDERS = StringItem("ai_providers", "", exportable = false)

    @JvmField
    val AI_CHAT_PROVIDER_ID = StringItem("ai_chat_provider_id", "", exportable = false)

    @JvmField
    val AI_VOICE_PROVIDER_ID = StringItem("ai_voice_provider_id", "", exportable = false)

    @JvmField
    val AI_VOICE_MODEL = StringItem("ai_voice_model", "", exportable = false)

    @JvmField
    val AI_EDITOR_PROVIDER_ID = StringItem("ai_editor_provider_id", "", exportable = false)

    @JvmField
    val AI_EDITOR_MODEL = StringItem("ai_editor_model", "", exportable = false)

    @JvmField
    val AI_SUMMARY_PROVIDER_ID = StringItem("ai_summary_provider_id", "", exportable = false)

    @JvmField
    val AI_SUMMARY_MODEL = StringItem("ai_summary_model", "", exportable = false)

    @JvmField
    val AI_SUMMARY_TEMPERATURE = FloatItem("ai_summary_temperature", -1f)

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
    val AI_SUMMARY_ENABLED = BoolItem("ai_summary_enabled", false)

    @JvmField
    val AI_REASONING_ENABLED = BoolItem("ai_reasoning_enabled", false)

    @JvmField
    val AI_REASONING_EFFORT = StringItem("ai_reasoning_effort", "medium")

    @JvmField
    val AI_ROLES = desu.inugram.helpers.ai.AiRolesConfig("ai_roles")

    @JvmField
    val AI_ACTIVE_ROLE = StringItem("ai_active_role", "", exportable = false)

    @JvmField
    val AI_ROLES_SEEDED = BoolItem("ai_roles_seeded", false, exportable = false)

    @JvmField
    val AI_STREAM_ENABLED = BoolItem("ai_stream_enabled", true)

    @JvmField
    val AI_ONLY_ANSWER = BoolItem("ai_only_answer", false)

    @JvmField
    val AI_INSERT_QUOTE = BoolItem("ai_insert_quote", true)

    @JvmField
    val AI_TEMPERATURE = FloatItem("ai_temperature", 1.0f)

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
    val AI_TRANSCRIBE_GROQ_MODEL = StringItem("ai_transcribe_groq_model", "whisper-large-v3-turbo", exportable = false)

    @JvmField
    val AI_TRANSCRIBE_GEMINI_MODEL = StringItem("ai_transcribe_gemini_model", "gemini-3.5-flash", exportable = false)

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
    val MEMORY_TRIM = BoolItem("memory_trim", true)

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

    @JvmField
    val SMALL_GIFS = BoolItem("small_gifs", false)

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
    val REMEMBER_SELECTED_FOLDER = BoolItem("remember_selected_folder", false)

    @JvmField
    val TAB_INDICATOR_STROKE = BoolItem("tab_indicator_stroke", false)

    @JvmField
    val FOLDERS_AT_BOTTOM = BoolItem("folders_at_bottom", false)

    @JvmField
    val HIDE_ARCHIVE_FROM_CHAT_LIST = BoolItem("hide_archive_from_chat_list", false)

    @JvmField
    val PRESENCE_COLOR_DOTS = BoolItem("presence_color_dots", false)

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
            const val CUSTOM = 6
        }
    }

    @JvmField
    val DIALOGS_TITLE_TEXT = DialogsTitleTextItem()

    @JvmField
    val DIALOGS_TITLE_TEXT_CUSTOM_TEXT = StringItem("dialogs_title_text_custom_text", "")

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
    val CALL_ROTATION = BoolItem("call_rotation", false)

    @JvmField
    val FORCE_RELAY_CALLS = BoolItem("force_relay_calls", false)

    @JvmField
    val PRESENCE_LOGGER_NOTIFY = BoolItem("presence_logger_notify", false)

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

    // entiny: brings back the accounts list on the settings screen that customizable-settings-rows hid
    @JvmField
    val SETTINGS_SHOW_ACCOUNTS = BoolItem("settings_show_accounts", true)

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

    // entiny: 1=Front, 2=Rear, 3=Ask
    @JvmField
    val ROUND_DEFAULT_CAMERA = IntItem("round_default_camera", 1)

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

    @JvmField
    val ROUND_RECORDER_60FPS = BoolItem("round_recorder_60fps", false)

    @JvmField
    val ROUND_RECORDER_LOCK_EXPOSURE = BoolItem("round_recorder_lock_exposure", false)

    @JvmField
    val ROUND_RECORDER_EXPOSURE_BUTTON = BoolItem("round_recorder_exposure_button", true)

    @JvmField
    val ROUND_RECORDER_EXPOSURE_LEVELS = BoolItem("round_recorder_exposure_levels", true)

    @JvmField
    val ROUND_RECORDER_EXPOSURE_FRONT = FloatItem("round_recorder_exposure_front", 0f)

    @JvmField
    val ROUND_RECORDER_EXPOSURE_BACK = FloatItem("round_recorder_exposure_back", 0f)

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
    val DISABLE_GLASS_GLARE = BoolItem("disable_glass_glare", false)

    @JvmField
    val LIQUID_GLASS_ANGLE = IntItem("liquid_glass_angle", 0)

    @JvmField
    val LIQUID_GLASS_INTENSITY = IntItem("liquid_glass_intensity", 75)

    @JvmField
    val LIQUID_GLASS_TINT = IntItem("liquid_glass_tint", 100)

    @JvmField
    val NICHE_SETTINGS_UNLOCKED = BoolItem("niche_settings_unlocked", false)

    @JvmField
    val HIDE_DONATE_CARD = BoolItem("hide_donate_card", false, exportable = false)

    @JvmField
    val DISABLE_PROFILE_AVATAR_BLUR = BoolItem("disable_profile_avatar_blur", false)

    @JvmField
    val WIDE_CHANNEL_POSTS = BoolItem("wide_channel_posts", false)

    @JvmField
    val WIDE_CHANNEL_POSTS_INSET = FloatItem("wide_channel_posts_inset", 8.0f)

    @JvmField
    val REDUCE_MENU_MOTION = BoolItem("reduce_menu_motion", true)

    @JvmField
    val PROFILE_PHOTO_GRADIENT_FADE = BoolItem("profile_photo_gradient_fade", false)

    @JvmField
    val SIMPLE_ATTACH_POPUP_ANIMATION = BoolItem("simple_attach_popup_animation", false)

    @JvmField
    val KEEP_SCREEN_ON_RECORDING = BoolItem("keep_screen_on_recording", false)

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
    val REACTIONS_IN_ROW = ReactionsInRowItem()

    @JvmField
    val CHAT_INPUT_MAX_LINES = IntItem("chat_input_max_lines", 8)

    @JvmField
    val CHAT_INPUT_TEXT_SIZE = IntItem("chat_input_text_size", 18)

    @JvmField
    val IOS_INPUT_BUTTON_PLACEMENT = BoolItem("ios_button_placement", false)

    @JvmField
    val IOS_INPUT_APPEARANCE = BoolItem("ios_input_appearance", false)

    @JvmField
    val COMPACT_INPUT_SIZE = BoolItem("compact_input_size", false)

    @JvmField
    val ACTION_BUTTON_STYLE = IntItem("action_button_style", 0)

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
    val SHOW_MEDIA_SIZE = BoolItem("show_media_size", false)

    @JvmField
    val FORWARD_PRO = BoolItem("forward_pro", false)

    // Forward Pro quick toggles remembered between forwards
    val FORWARD_PRO_SILENT = BoolItem("forward_pro_silent", false)
    val FORWARD_PRO_HIDE_CAPTION = BoolItem("forward_pro_hide_caption", false)
    val FORWARD_PRO_SHOW_SENDER = BoolItem("forward_pro_show_sender", true)

    @JvmField
    val BUBBLE_TAILS = BoolItem("bubble_tails", true)

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

    @JvmField
    val MESSAGE_MENU_QUICK_ACTIONS_TOP = BoolItem("message_menu_quick_actions_top", false)

    @JvmField
    val CHAT_MENU_ITEMS = ChatMenuConfig("chat_menu_items")

    @JvmField
    val PROFILE_SETTINGS_ROWS = ProfileMenuConfig("profile_settings_rows")

    @JvmField
    val PROFILE_INFO_ROWS = ProfileInfoMenuConfig("profile_info_rows")

    @JvmField
    val DIALOGS_MENU_ITEMS = DialogsMenuConfig("dialogs_menu_items")

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

    @JvmField
    val CENTER_TITLE_MAIN = BoolItem("center_title_main", false)

    @JvmField
    val CENTER_TITLE_CHATS = BoolItem("center_title_chats", false)

    @JvmField
    val CENTER_TITLE_RIGHT_AVATAR = BoolItem("center_title_right_avatar", false)

    @JvmField
    val IOS_BOTTOM_NAVIGATION_BAR = BoolItem("ios_bottom_navigation_bar", false)

    @JvmField
    val IOS_CHATS_TAB_RETURNS_TO_FIRST_FOLDER = BoolItem("ios_chats_tab_returns_to_first_folder", false)

    @JvmField
    val DIALOG_AVATAR_OPENS_PROFILE = BoolItem("dialog_avatar_opens_profile", false)

    @JvmField
    val ALWAYS_SHOW_DOWNLOADS = BoolItem("always_show_downloads", false)

    @JvmField
    val IOS_CHAT_HEADER = BoolItem("ios_chat_header", false)

    @JvmField
    val HIDE_CHAT_AVATAR = BoolItem("hide_chat_avatar", false)

    @JvmField
    val IOS_CHAT_HEADER_AVATAR_SLOT = BoolItem("ios_chat_header_avatar_slot", false)

    @JvmField
    val IOS_CHAT_HEADER_AVATAR_STATIC = BoolItem("ios_chat_header_avatar_static", false)

    @JvmField
    val CHAT_HEADER_NO_PILL = BoolItem("chat_header_no_pill", false)

    @JvmField
    val CLOWN_AVATAR_BLOCKED = BoolItem("clown_avatar_blocked", false)

    @JvmField
    val CLOWN_AVATAR_BLOCKED_ME = BoolItem("clown_avatar_blocked_me", false)

    @JvmField
    val CLOWN_AVATAR_EMOJI = IntItem("clown_avatar_emoji", 0)

    @JvmField
    val CHAT_TITLE_MARQUEE = BoolItem("chat_title_marquee", false)

    @JvmField
    val SAVE_SELF_DESTRUCT_MEDIA = BoolItem("save_self_destruct_media", false)

    @JvmField
    val SAVE_SELF_DESTRUCT_TEXT = BoolItem("save_self_destruct_text", false)

    @JvmField
    val SAVE_VIEW_ONCE_MEDIA = BoolItem("save_view_once_media", false)

    @JvmField
    val VIEW_ONCE_SHOW_NORMAL = BoolItem("view_once_show_normal", false)

    @JvmField
    val AUTO_SAVE_ONE_TIME = BoolItem("auto_save_one_time", false)

    @JvmField
    val SAVE_TIMED_MESSAGES = BoolItem("save_timed_messages", false)

    @JvmField
    val SAVE_ANY_STORY = BoolItem("save_any_story", false)

    @JvmField
    val SAVE_SELECTED_FILES = BoolItem("save_selected_files", false)

    @JvmField
    val LOCAL_NAMES = BoolItem("local_names", false)

    @JvmField
    val AUTO_SAVE_STORIES = BoolItem("auto_save_stories", false)

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
    val SAVE_DELETED_OWN = BoolItem("save_deleted_own", false)

    @JvmField
    val PROMPT_KEEP_LOCAL_ON_DELETE = BoolItem("prompt_keep_local_on_delete", false)

    @JvmField
    val ALLOW_FORWARD_RESTRICTED = BoolItem("allow_forward_restricted", false)

    @JvmField
    val ALLOW_SCREENSHOTS = BoolItem("allow_screenshots", false)
    val FORCE_NO_VIBRATION = object : BoolItem("force_no_vibration", false) {
        override var value: Boolean
            get() = super.value
            set(v) {
                super.value = v
                desu.inugram.helpers.vibration.HapticFeedbackConstants.sync()
            }
    }

    @JvmField
    val SUPPRESS_SCREENSHOT_NOTIFICATION = BoolItem("suppress_screenshot_notification", false)

    @JvmField
    val HIDE_SPONSORED_MESSAGES = BoolItem("hide_sponsored_messages", true)

    @JvmField
    val SAVE_EDITED_MESSAGES = BoolItem("save_edited_messages", false)

    // #if PLUGINS
    @JvmField
    val MESSAGE_PLUGIN_ACTIONS = PluginActionSettingsConfig("message_plugin_actions")
    // #endif

    @JvmField
    val SAVE_USER_INFO = BoolItem("save_user_info", false)

    @JvmField
    val SAVE_READ_TIME = BoolItem("save_read_time", false)

    @JvmField
    val SAVE_LAST_SEEN = BoolItem("save_last_seen", false)

    @JvmField
    val SHOW_EDIT_HISTORY_DIFF = BoolItem("show_edit_history_diff", false)

    @JvmField
    val SHOW_MUTUAL_CONTACT_ICON = BoolItem("show_mutual_contact_icon", true)

    @JvmField
    val SHOW_MUTUAL_CONTACT_IN_CHATS = BoolItem("show_mutual_contact_in_chats", true)
    // #if PLUGINS
    @JvmField
    val CHAT_PLUGIN_ACTIONS = PluginActionSettingsConfig("chat_plugin_actions")
    // #endif

    @JvmField
    val MASK_SERVER_APP_NAME = BoolItem("mask_server_app_name", true)

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
    val REGEX_FILTER_PATTERNS = StringItem("regex_filter_patterns", "")

    @JvmField
    val REGEX_FILTERS_JSON = StringItem("regex_filters_json", "[]")

    @JvmField
    val REGEX_FILTER_EXCLUSIONS_JSON = StringItem("regex_filter_exclusions_json", "[]")

    @JvmField
    val REGEX_FILTERS_MIGRATED = BoolItem("regex_filters_migrated", false, exportable = false)

    @JvmField
    val REGEX_FILTER_HIDE_REPLIES = BoolItem("regex_filter_hide_replies", false)

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
        // entiny: a plugin's tile source only exists on the osmdroid map, the stored choice stays untouched
        override var value: Int
            get() = if (desu.inugram.helpers.maps.EntinyMapTiles.active != null) OSM_LITE else super.value
            set(v) {
                super.value = v
            }

        override fun SharedPreferences.Editor.write() {
            putInt(key, super.value)
        }

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
            const val DISABLED = 4
        }
    }

    @JvmField
    val MAP_PREVIEW_PROVIDER = MapPreviewProviderItem()

    class UpdatesEnabledItem : BoolItem("updates_enabled", true, exportable = false) {
        override fun read(prefs: SharedPreferences): Boolean {
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

    @JvmField
    val UPDATES_INCLUDE_BETA = BoolItem("updates_include_beta", false)

    // entiny: push through a UnifiedPush distributor (ntfy etc.) instead of Firebase
    @JvmField
    val UNIFIED_PUSH = BoolItem("unified_push", false)
    val UNIFIED_PUSH_GATEWAY = StringItem("unified_push_gateway", "https://p2p.belloworld.it/")

    @JvmField
    val FOREGROUND_PUSH_SERVICE = BoolItem("foreground_push_service", false)

    @JvmField
    val DEVICE_SPOOF = BoolItem("device_spoof", false)

    @JvmField
    val DEVICE_SPOOF_PRESET = StringItem("device_spoof_preset", "oneplus_13r")

    @JvmField
    val DEVICE_SPOOF_MODEL = StringItem("device_spoof_model", "")

    @JvmField
    val DEVICE_SPOOF_SYSTEM = StringItem("device_spoof_system", "")

    @JvmField
    val DEVICE_SPOOF_APP = StringItem("device_spoof_app", "")

    @JvmField
    val DEVICE_SPOOF_HIDE_TZ = BoolItem("device_spoof_hide_tz", false)

    @JvmField
    val DEVICE_SPOOF_HIDE_INSTALLER = BoolItem("device_spoof_hide_installer", false)

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

    // entiny: keep global target language separate so per-dialog translations do not overwrite user preference
    @JvmField
    val TRANSLATE_TARGET_LANGUAGE = StringItem("translate_target_language", "")

    // entiny: one-shot guard for adopting stock's legacy per-session "translate to" pick into
    // TRANSLATE_TARGET_LANGUAGE - without it, every settings screen visit re-adopts whatever
    // language was last picked in an in-chat translate dialog, silently replacing "Follow app
    // language" the moment the user ever translates a single message anywhere
    @JvmField
    val TRANSLATE_TARGET_LANGUAGE_MIGRATED = BoolItem("translate_target_language_migrated", false)

    @JvmField
    val FORCE_TRANSLATE = BoolItem("force_translate", false)

    @JvmField
    val AUTO_TRANSLATE_ALL = BoolItem("auto_translate_all", false)

    @JvmField
    val TRANSLATE_OUTGOING = BoolItem("translate_outgoing", false)

    @JvmField
    val INPUT_TRANSLATE = BoolItem("input_translate", false)

    @JvmField
    val TRANSLATE_IN_SEND_PREVIEW = BoolItem("translate_in_send_preview", false)

    @JvmField
    val TRANSLATE_PROVIDER = IntItem("translate_provider", 0)

    // entiny: skip stock 6-message detection sample threshold so translate banner shows on first detected foreign message
    @JvmField
    val INSTANT_TRANSLATE_BANNER = BoolItem("instant_translate_banner", true)

    @JvmField
    val IGNORE_TRANSLATIONS_DISABLED = BoolItem("ignore_translations_disabled", false)

    @JvmField
    val TRANSLATE_DEEPL_KEY = StringItem("translate_deepl_key", "", exportable = false)

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

    @JvmField
    val TRANSLATE_LLM_CONTEXT = IntItem("translate_llm_context", 0)

    @JvmField
    val TRANSLATE_LLM_TEMPERATURE = FloatItem("translate_llm_temperature", 0.3f)

    @JvmField
    val TRANSLATION_PROVIDER = StringItem("translation_provider", "", exportable = false)

    // entiny: where the marketplace looks; kept so a test catalog can stand in for the real one
    @JvmField
    val MARKET_INDEX_URL = StringItem("market_index_url", "https://raw.githubusercontent.com/entinyGram/plugins/main/index.json", exportable = false)

    @JvmField
    val ACCOUNT_ORDER = StringItem("account_order", "", exportable = false)

    @JvmField
    val ACCOUNT_SWITCH_SHORTCUT = BoolItem("account_switch_shortcut", false)

    @JvmField
    val HIDE_RECENT_CHATS_SHORTCUTS = BoolItem("hide_recent_chats_shortcuts", false)

    class RecentChatsStyleItem : IntItem("recent_chats_style", POPUP) {
        companion object {
            const val POPUP = 0
            const val SIDEBAR = 1
            const val PAGE = 2
            const val STRIP = 3
        }
    }

    @JvmField
    val RECENT_CHATS_STYLE = RecentChatsStyleItem()

    @JvmField
    val RECENT_CHATS_LIMIT = IntItem("recent_chats_limit", 25)

    @JvmField
    val CHAT_EXPORT = BoolItem("chat_export", false)

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
    val BIOMETRIC_REQUIRE_CONFIRMATION_PASSCODE = BoolItem("biometric_require_confirmation_passcode", true)

    @JvmField
    val BIOMETRIC_REQUIRE_CONFIRMATION_BOTS = BoolItem("biometric_require_confirmation_bots", true)

    @JvmField
    val BIOMETRIC_REQUIRE_CONFIRMATION_ACTIONS = BoolItem("biometric_require_confirmation_actions", false)

    @JvmField
    val BIOMETRIC_ALLOW_DEVICE_CREDENTIAL = BoolItem("biometric_allow_device_credential", false)

    @JvmField
    val BIOMETRIC_LOCK_ARCHIVE = BoolItem("biometric_lock_archive", false)

    @JvmField
    val BIOMETRIC_LOCK_ARCHIVE_EVERY_TIME = BoolItem("biometric_lock_archive_every_time", false)

    // entiny: run-records are non-exportable so restoring a backup cannot re-arm the migration
    @JvmField
    val GHOST_MASTER_FLAG_MIGRATED = BoolItem("ghost_master_flag_migrated", false, exportable = false)

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

    // entiny: auto-offline re-asserts offline after live actions because server flips account online implicitly
    @JvmField
    val GHOST_AUTO_OFFLINE = BoolItem("ghost_auto_offline", false)

    @JvmField
    val GHOST_AUTO_OFFLINE_MIGRATED = BoolItem("ghost_auto_offline_migrated", false, exportable = false)

    @JvmField
    val GHOST_HIDE_APP_BAR_ICON = BoolItem("ghost_hide_app_bar_icon", false)

    @JvmField
    val GHOST_MODE_LAUNCHER_SHORTCUT = BoolItem("ghost_mode_launcher_shortcut", false)

    @JvmField
    val GHOST_WHITELIST_DIALOGS = StringSetItem("ghost_whitelist_dialogs", emptySet())

    // entiny: legacy whitelist/target sets, folded into GHOST_CHAT_OVERRIDES; kept so old backups still import
    val GHOST_TARGET_DIALOGS = StringSetItem("ghost_target_dialogs", emptySet())

    // entiny: per-chat overrides as "dialogId:read:typing" (0 default, 1 always, 2 never)
    val GHOST_CHAT_OVERRIDES = StringSetItem("ghost_chat_overrides", emptySet())
    val GHOST_OVERRIDES_MIGRATED = BoolItem("ghost_overrides_migrated", false, exportable = false)

    // entiny: which chat types global ghost mode covers; ghost chats ignore these
    val GHOST_SCOPE_USERS = BoolItem("ghost_scope_users", true)
    val GHOST_SCOPE_GROUPS = BoolItem("ghost_scope_groups", true)
    val GHOST_SCOPE_CHANNELS = BoolItem("ghost_scope_channels", true)

    @JvmField
    val LOCAL_PREMIUM = BoolItem("local_premium", false)

    @JvmField
    val LOCAL_CUSTOM_EMOJI = BoolItem("local_custom_emoji", false)

    @JvmField
    val HIDE_DEV_BADGES = BoolItem("hide_dev_badges", false)

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

    @JvmField
    val FEED_ENABLED = BoolItem("feed_enabled", false)

    @JvmField
    val FEED_EXCLUDED_CHANNELS = StringSetItem("feed_excluded_channels", emptySet())

    @JvmField
    val FEED_INCLUDE_ARCHIVED = BoolItem("feed_include_archived", false)

    @JvmField
    val FEED_NEWEST_ON_TOP = BoolItem("feed_newest_on_top", false)

    @JvmField
    val FEED_MARK_READ_ON_SCROLL = BoolItem("feed_mark_read_on_scroll", true)

    // entiny: Pill Stack, ported from exteraGram/exteraless -- see src/kotlin/helpers/pillstack/.
    @JvmField
    val PILL_STACK_ENABLED = BoolItem("pill_stack_enabled", false)

    @JvmField
    val PILL_STACK_VISIBLE_COUNT = IntItem("pill_stack_visible_count", 1)

    @JvmField
    val PILL_STACK_INFINITE_SCROLL = BoolItem("pill_stack_infinite_scroll", false)

    @JvmField
    val PILL_STACK_ACTIVE_PILLS = StringItem("pill_stack_active_pills", "")

    @JvmField
    val PILL_STACK_HIDDEN_PILLS = StringItem("pill_stack_hidden_pills", "")

    @JvmField
    val PILL_STACK_LAYOUT = PillStackMenuConfig("pill_stack_layout")

    @JvmField
    val PILL_STACK_RATE_INSTANCES = StringItem("pill_stack_rate_instances", "")

    @JvmField
    val PILL_STACK_RATE_CACHE = StringItem("pill_stack_rate_cache", "", exportable = false)

    @JvmField
    val PILL_STACK_RATE_CACHE_TIME = LongItem("pill_stack_rate_cache_time", 0L, exportable = false)

    @JvmField
    val PILL_STACK_GOLD_CACHE = StringItem("pill_stack_gold_cache", "", exportable = false)

    @JvmField
    val PILL_STACK_GOLD_CACHE_TIME = LongItem("pill_stack_gold_cache_time", 0L, exportable = false)

    // entiny: which pill id each visible slot last settled on, so a rebuild (e.g. reattaching the search bar) doesn't snap back to slot 0.
    @JvmField
    val PILL_STACK_LAST_ACTIVE = StringItem("pill_stack_last_active", "", exportable = false)

    // entiny: niche -- render the pills in the action bar next to the menu instead of inside the chats search bar
    @JvmField
    val PILL_STACK_IN_HEADER = BoolItem("pill_stack_in_header", false)

    @JvmField
    val PILL_STACK_PROXY_COUNTRY = BoolItem("pill_stack_proxy_country", true)

    // entiny: weather pill location -- the device position by default, or a point picked on the map (no location permission)
    @JvmField
    val WEATHER_USE_CURRENT_LOCATION = BoolItem("weather_use_current_location", true)

    @JvmField
    val WEATHER_FAHRENHEIT = BoolItem("weather_fahrenheit", false)

    @JvmField
    val WEATHER_LOCATION = StringItem("weather_location", "")

    @JvmField
    val WEATHER_LOCATION_ADDRESS = StringItem("weather_location_address", "")
}
