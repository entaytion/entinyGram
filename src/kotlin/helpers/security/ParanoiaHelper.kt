package desu.inugram.helpers.security

import android.app.Activity
import android.content.Context
import androidx.core.content.edit
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.DialogObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.LauncherIconController
import org.telegram.ui.LauncherIconController.LauncherIcon
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils

object ParanoiaHelper {
    private val prefs by lazy {
        ApplicationLoader.applicationContext.getSharedPreferences("inugram_hidden", Context.MODE_PRIVATE)
    }

    @Volatile
    private var paranoiaCache: Boolean? = null

    @Volatile
    private var hiddenCache: Map<Int, Set<Long>>? = null

    fun isParanoia(): Boolean =
        paranoiaCache ?: prefs.getBoolean("paranoia", false).also { paranoiaCache = it }

    @JvmStatic
    fun isHidden(account: Int, dialogId: Long): Boolean {
        if (!isParanoia()) return false
        if (DialogObject.isEncryptedDialog(dialogId)) return true
        // entiny: never hide Telegram service notifications (777000) to prevent login lockout
        if (dialogId == 777000L) return false
        val selected = getHidden(account).contains(dialogId)
        return if (whitelist) !selected else selected
    }

    fun getHidden(account: Int): Set<Long> {
        val cache = hiddenCache ?: loadAll().also { hiddenCache = it }
        return cache[account] ?: emptySet()
    }

    private fun loadAll(): Map<Int, Set<Long>> {
        val map = HashMap<Int, Set<Long>>()
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            val stored = prefs.getStringSet("hiddenChats$a", null) ?: continue
            map[a] = stored.mapNotNull { it.toLongOrNull() }.toHashSet()
        }
        return map
    }

    fun setHidden(account: Int, ids: Collection<Long>) {
        prefs.edit { putStringSet("hiddenChats$account", ids.map(Long::toString).toHashSet()) }
        hiddenCache = null
        reconcileLauncherShortcut()
    }

    var whitelist: Boolean
        get() = prefs.getBoolean("whitelist", false)
        set(value) = prefs.edit { putBoolean("whitelist", value) }

    var hideSettings: Boolean
        get() = prefs.getBoolean("hideSettings", false)
        set(value) = prefs.edit { putBoolean("hideSettings", value) }

    @JvmStatic
    fun shouldHideSettings(): Boolean = isParanoia() && hideSettings

    var disableNotifications: Boolean
        get() = prefs.getBoolean("disableNotifications", false)
        set(value) = prefs.edit { putBoolean("disableNotifications", value) }

    @JvmStatic
    fun shouldSuppressNotifications(): Boolean = isParanoia() && disableNotifications

    var hideOtherAccounts: Boolean
        get() = prefs.getBoolean("hideOtherAccounts", false)
        set(value) = prefs.edit { putBoolean("hideOtherAccounts", value) }

    @JvmStatic
    fun hidesOtherAccounts(): Boolean = isParanoia() && hideOtherAccounts

    var hideFolders: Boolean
        get() = prefs.getBoolean("hideFolders", false)
        set(value) = prefs.edit { putBoolean("hideFolders", value) }

    @JvmStatic
    fun shouldHideFolders(): Boolean = isParanoia() && hideFolders

    @Volatile
    private var hideMyStoriesCache: Boolean? = null

    var hideMyStories: Boolean
        get() = hideMyStoriesCache ?: prefs.getBoolean("hideMyStories", false).also { hideMyStoriesCache = it }
        set(value) {
            prefs.edit { putBoolean("hideMyStories", value) }
            hideMyStoriesCache = value
        }

    @JvmStatic
    fun shouldHideMyStories(): Boolean = isParanoia() && hideMyStories

    @JvmStatic
    fun hidesStoriesOf(account: Int, dialogId: Long): Boolean =
        shouldHideMyStories() && dialogId == UserConfig.getInstance(account).clientUserId

    var disguiseIcon: Boolean
        get() = prefs.getBoolean("disguiseIcon", false)
        set(value) = prefs.edit { putBoolean("disguiseIcon", value) }

    var launcherShortcut: Boolean
        get() = prefs.getBoolean("launcherShortcut", false)
        set(value) = prefs.edit { putBoolean("launcherShortcut", value) }

    fun canUseLauncherShortcut(account: Int = UserConfig.selectedAccount): Boolean =
        hasExitCode() && getHidden(account).isNotEmpty()

    @JvmStatic
    fun shouldShowLauncherShortcut(): Boolean =
        launcherShortcut && !isParanoia() && canUseLauncherShortcut()

    private fun reconcileLauncherShortcut() {
        if (launcherShortcut && !canUseLauncherShortcut()) launcherShortcut = false
    }

    @Volatile
    private var disguisedCache: Boolean? = null

    @JvmStatic
    fun isDisguised(): Boolean = disguisedCache ?: (isParanoia() && disguiseIcon).also { disguisedCache = it }

    // entiny: Telegram server reports client by registered api_id title; mask with AppName in session list
    @JvmStatic
    fun getSessionAppName(serverName: String): String {
        if (!InuConfig.MASK_SERVER_APP_NAME.value) return serverName
        if (serverName.contains("inugram", ignoreCase = true) || serverName.contains("entinygram", ignoreCase = true)) {
            return runCatching { LocaleController.getString(R.string.AppName) }.getOrElse { serverName }
        }
        return serverName
    }

    // entiny: strip git SHA suffix from app_version for cosmetic display in sessions list
    private val GIT_SHA_SUFFIX = Regex("-[0-9a-fA-F]{6,40}(?=[ (]|$)")

    @JvmStatic
    fun getSessionAppVersion(rawVersion: String): String {
        if (!InuConfig.MASK_SERVER_APP_NAME.value) return rawVersion
        return GIT_SHA_SUFFIX.replace(rawVersion, "")
    }

    @JvmStatic
    fun filterLauncherIcons(icons: MutableList<LauncherIcon>) {
        if (isDisguised()) {
            icons.remove(LauncherIcon.DEFAULT)
            icons.remove(LauncherIcon.STOCK)
            val disguiseIdx = icons.indexOf(LauncherIcon.DISGUISE)
            if (disguiseIdx != -1) {
                icons.removeAt(disguiseIdx)
                icons.add(0, LauncherIcon.DISGUISE)
            }
        } else {
            icons.remove(LauncherIcon.DISGUISE)
        }
    }

    private fun enableDisguise() {
        val current = LauncherIcon.values().firstOrNull { LauncherIconController.isEnabled(it) } ?: LauncherIcon.DEFAULT
        prefs.edit { putString("savedIcon", current.name) }
        LauncherIconController.setIcon(LauncherIcon.DISGUISE)
    }

    private fun disableDisguise() {
        val saved = prefs.getString("savedIcon", null) ?: return
        prefs.edit { remove("savedIcon") }
        val icon = runCatching { LauncherIcon.valueOf(saved) }.getOrNull() ?: LauncherIcon.DEFAULT
        LauncherIconController.setIcon(icon)
    }

    fun hasExitCode(): Boolean = prefs.contains("exitHash")

    fun setExitCode(code: String) {
        SecretHash.store(prefs, "exitHash", "exitSalt", code.trim())
        reconcileLauncherShortcut()
    }

    @JvmStatic
    fun filterTopPeers(account: Int, peers: MutableList<TLRPC.TL_topPeer>) {
        if (!isParanoia()) return
        peers.removeAll { isHidden(account, DialogObject.getPeerDialogId(it.peer)) }
    }

    @JvmStatic
    fun filterDialogs(account: Int, dialogs: ArrayList<TLRPC.Dialog>): ArrayList<TLRPC.Dialog> {
        if (!isParanoia()) return dialogs
        return dialogs.filterTo(ArrayList()) { !isHidden(account, it.id) }
    }

    @JvmStatic
    fun filterContacts(account: Int, list: MutableList<TLRPC.TL_contact>) {
        if (!isParanoia()) return
        list.removeAll { isHidden(account, it.user_id) }
    }

    private val skippedBlocked = Array(UserConfig.MAX_ACCOUNT_COUNT) { HashSet<Long>() }

    @JvmStatic
    fun skipBlockedPeer(account: Int, peerId: Long): Boolean {
        if (!isHidden(account, peerId)) return false
        skippedBlocked[account].add(peerId)
        return true
    }

    @JvmStatic
    fun getSkippedBlocked(account: Int): Int = skippedBlocked[account].size

    @JvmStatic
    fun unskipBlockedPeer(account: Int, peerId: Long): Boolean {
        if (!isHidden(account, peerId)) return false
        skippedBlocked[account].remove(peerId)
        return true
    }

    @JvmStatic
    fun resetSkippedBlocked(account: Int) {
        skippedBlocked[account].clear()
    }

    @JvmStatic
    fun adjustBlockedTotal(account: Int, total: Int): Int =
        if (total <= 0) total else (total - skippedBlocked[account].size).coerceAtLeast(0)

    private class StrippedExceptions {
        val allowed = ArrayList<Long>()
        val disallowed = ArrayList<Long>()
    }

    private val strippedExceptions = HashMap<Long, StrippedExceptions>()

    private fun getExceptionsKey(account: Int, type: Int): Long = account.toLong() shl 32 or type.toLong()

    @JvmStatic
    fun filterPrivacyRules(account: Int, type: Int, rules: MutableList<TLRPC.PrivacyRule>?) {
        val key = getExceptionsKey(account, type)
        if (!isParanoia()) {
            strippedExceptions.remove(key)
            return
        }
        // entiny: preserve previously stashed exceptions when empty rules arrive to avoid dropping ids on next upload
        if (rules.isNullOrEmpty()) return
        val stripped = StrippedExceptions()
        for (rule in rules) {
            when (rule) {
                is TLRPC.TL_privacyValueAllowUsers -> stripUsers(account, rule.users, stripped.allowed)
                is TLRPC.TL_privacyValueDisallowUsers -> stripUsers(account, rule.users, stripped.disallowed)
                is TLRPC.TL_privacyValueAllowChatParticipants -> stripChats(account, rule.chats, stripped.allowed)
                is TLRPC.TL_privacyValueDisallowChatParticipants -> stripChats(account, rule.chats, stripped.disallowed)
            }
        }
        if (stripped.allowed.isEmpty() && stripped.disallowed.isEmpty()) {
            strippedExceptions.remove(key)
        } else {
            strippedExceptions[key] = stripped
        }
    }

    private fun stripUsers(account: Int, users: MutableList<Long>, into: MutableList<Long>) {
        users.removeAll { if (isHidden(account, it)) into.add(it) else false }
    }

    private fun stripChats(account: Int, chats: MutableList<Long>, into: MutableList<Long>) {
        chats.removeAll { if (isHidden(account, -it)) into.add(-it) else false }
    }

    // entiny: re-inject hidden peer ids before upload so server does not drop them
    @JvmStatic
    fun restorePrivacyExceptions(account: Int, type: Int, allowed: MutableList<Long>, disallowed: MutableList<Long>) {
        val stripped = strippedExceptions[getExceptionsKey(account, type)] ?: return
        stripped.allowed.forEach { if (!allowed.contains(it) && !disallowed.contains(it)) allowed.add(it) }
        stripped.disallowed.forEach { if (!allowed.contains(it) && !disallowed.contains(it)) disallowed.add(it) }
    }

    @JvmStatic
    fun stripPrivacyExceptions(account: Int, allowed: MutableList<Long>, disallowed: MutableList<Long>) {
        if (!isParanoia()) return
        allowed.removeAll { isHidden(account, it) }
        disallowed.removeAll { isHidden(account, it) }
    }

    @JvmStatic
    fun anyHidden(account: Int, ids: Collection<Long>): Boolean {
        if (!isParanoia()) return false
        return ids.any { isHidden(account, it) }
    }

    @JvmStatic
    fun matchesExitCode(query: String?): Boolean {
        if (!isParanoia() || query.isNullOrBlank()) return false
        return SecretHash.verify(prefs, "exitHash", "exitSalt", query.trim())
    }

    fun enableParanoia(fragment: BaseFragment) {
        fragment.parentActivity?.let { enableParanoia(it) }
    }

    fun disableParanoia(fragment: BaseFragment) {
        fragment.parentActivity?.let { disableParanoia(it) }
    }

    fun enableParanoia(activity: Activity) = setParanoia(activity, true)

    fun disableParanoia(activity: Activity) = setParanoia(activity, false)

    private fun setParanoia(activity: Activity, value: Boolean) {
        if (value) {
            if (disguiseIcon) enableDisguise()
        } else {
            disableDisguise()
        }
        prefs.edit(commit = true) { putBoolean("paranoia", value) }
        paranoiaCache = value
        InuUtils.restartApp(activity)
    }
}
