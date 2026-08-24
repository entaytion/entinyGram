package desu.inugram

import android.content.Context
import android.content.Intent
import android.os.Build
import desu.inugram.helpers.CrashReporter
import desu.inugram.helpers.LoginHelper
import desu.inugram.helpers.ProxyVpnHelper
import desu.inugram.helpers.ShortcutHelper
import desu.inugram.helpers.UrlCleanerHelper
import desu.inugram.helpers.cloud.CloudSettingsHelper
import desu.inugram.helpers.dialogs.DrawerHelper
import desu.inugram.helpers.font.FontHelper
import desu.inugram.helpers.maps.MapsHelper
import desu.inugram.helpers.security.PasscodeHelper
import desu.inugram.helpers.theme.MonetHelper
import desu.inugram.helpers.theme.NonIslandHelper
import desu.inugram.helpers.update.ApkInstaller
import desu.inugram.helpers.update.UpdateHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.tl.TL_update
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedFloat
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ChatActivityEnterView
import org.telegram.ui.Components.GestureDetector2
import org.telegram.ui.Components.GestureDetectorFixDoubleTap
import org.telegram.ui.LaunchActivity
import org.telegram.ui.LauncherIconController


object InuHooks {
    @JvmStatic
    fun init(context: Context) {
        CrashReporter.install()
        InuConfig.load(context)
        FontHelper.init(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            FontHelper.installGlobal()
        }
        syncDoubleTapDelay()
        syncAnimationSpeed()
        syncChatInputRowHeight()
        desu.inugram.helpers.media.DownloadKeepAliveHelper.startPolling()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MonetHelper.registerOverlayChangeReceiver(context)
            MonetHelper.registerThemeReloadReceiver(context)
        }
        // APK housekeeping is not needed to render the first frame. Keep it off
        // Application.onCreate's critical path; the work is independent and safe
        // to perform after core config/theme wiring has started.
        Utilities.globalQueue.postRunnable {
            UpdateHelper.clearPendingIfInstalled()
            ApkInstaller.dismissInstalledNotification()
        }
        // Listener registration and VPN reconciliation do not affect the first
        // frame. Keep them off Application.onCreate's critical path; both
        // helpers marshal user-visible work back to the appropriate queue.
        Utilities.globalQueue.postRunnable {
            CloudSettingsHelper.attachAutoSyncListener()
            ProxyVpnHelper.init(context)
            UrlCleanerHelper.preload()
        }
    }

    private val newMessagesObserver = NotificationCenter.NotificationCenterDelegate { id, acc, args ->
        if (id != NotificationCenter.didReceiveNewMessages) return@NotificationCenterDelegate
        @Suppress("UNCHECKED_CAST")
        val messages = args[1] as? ArrayList<MessageObject> ?: return@NotificationCenterDelegate
        for (msg in messages) onNewMessage(msg, acc)
    }

    @JvmStatic
    fun onMessagesControllerCreated(messagesController: MessagesController, account: Int) {
        MapsHelper.syncMapProvider(messagesController)
        desu.inugram.helpers.dialogs.PinHelper.load(account)
        desu.inugram.helpers.dialogs.FolderMembershipHelper.load(account)
        desu.inugram.helpers.security.PresenceHelper.load(account)
        desu.inugram.helpers.security.GhostHelper.syncPresence(account)
        desu.inugram.helpers.media.StarGiftsHelper.refreshDeletedGiftsList()
        desu.inugram.helpers.media.StarGiftsHelper.loadStickerPack()
        // TTL-based cache pruning only ran when the TTL setting itself was changed — accounts
        // that never touch the setting (or just leave the app running for weeks) never got
        // pruned. Run it once per account on every cold start instead.
        desu.inugram.helpers.chat.SavedMessagesHelper.pruneIfNeeded(account)
        desu.inugram.helpers.security.PresenceHelper.pruneIfNeeded(account)
        AndroidUtilities.runOnUIThread {
            val nc = NotificationCenter.getInstance(account)
            // MessagesController (and thus its NotificationCenter instance) can be recreated for the
            // same account (relogin, account reset) — drop any stale observer before re-adding so
            // onNewMessage doesn't fire multiple times per message.
            nc.removeObserver(newMessagesObserver, NotificationCenter.didReceiveNewMessages)
            nc.addObserver(newMessagesObserver, NotificationCenter.didReceiveNewMessages)
        }
    }

    fun onNewMessage(message: MessageObject, account: Int) {
        if (message.messageOwner != null) UpdateHelper.onNewMessage(message.messageOwner)
    }

    @JvmStatic
    fun syncAnimationSpeed() {
        try {
            Class.forName("android.animation.ValueAnimator")
                .getMethod("setDurationScale", Float::class.javaPrimitiveType)
                .invoke(null, 1f / InuConfig.ANIMATION_SPEED.value)
        } catch (_: Throwable) {
        }
        AnimatedFloat.inu_multiplier = InuConfig.ANIMATION_SPEED.value
    }

    @JvmStatic
    fun onUpdate(update: TLObject?, account: Int) {
        if (update is TL_update.TL_updateUserStatus) {
            desu.inugram.helpers.security.PresenceHelper.onStatusUpdate(update, account)
        }
        LoginHelper.onUpdate(update, account)
    }

    @JvmStatic
    fun handleIntent(activity: LaunchActivity, intent: Intent?): Boolean {
        return PasscodeHelper.tryHandleDeepLink(activity, intent)
            || SearchRegistry.tryHandleDeepLink(activity, intent)
            || tryHandleUpdateDeepLink(activity, intent)
            || tryHandleFunDeepLink(activity, intent)
            || ShortcutHelper.handleAction(activity, intent)
    }

    // tg://update — runs the fork custom update check (stock doesn't route it).
    private fun tryHandleUpdateDeepLink(activity: LaunchActivity, intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != "tg") return false
        val host = uri.host ?: uri.schemeSpecificPart?.removePrefix("//")?.substringBefore('/')
        if (host != "update") return false
        UpdateHelper.checkForCustomUpdate(true) {
            if (UpdateHelper.pendingBetaUpdate != null) UpdateHelper.revealPendingUpdate()
        }
        return true
    }

    private fun tryHandleFunDeepLink(activity: LaunchActivity, intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != "tg") return false
        val host = uri.host ?: uri.schemeSpecificPart?.removePrefix("//")?.substringBefore('/')
        val (icon, text) = when (host) {
            "nya" -> R.raw.msg_emoji_cat to "meow~"
            "woof" -> R.raw.msg_emoji_activities to "woof :3"
            else -> return false
        }
        val fragment = activity.actionBarLayout?.lastFragment ?: return false
        BulletinFactory.of(fragment).createSimpleBulletin(icon, text).show()
        return true
    }

    @JvmStatic
    fun onAuthSuccess(account: Int) {
        PasscodeHelper.removeForAccount(account)
    }

    @JvmStatic
    fun onResume(launchActivity: LaunchActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MonetHelper.refreshMonetThemeIfChanged()
        }
        val bg = Theme.getColor(Theme.key_windowBackgroundWhite)
        launchActivity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        CrashReporter.maybeShowReportSheet(launchActivity)
        ProxyVpnHelper.reconcile()
        DrawerHelper.refreshUpdateState()
        desu.inugram.helpers.security.GhostHelper.syncPresence(org.telegram.messenger.UserConfig.selectedAccount)
    }

    @JvmStatic
    fun syncDoubleTapDelay() {
        val delay = InuConfig.DOUBLE_TAP_DELAY.value
        GestureDetectorFixDoubleTap.GestureDetectorCompatImplBase.DOUBLE_TAP_TIMEOUT = delay
        GestureDetector2.DOUBLE_TAP_TIMEOUT = delay
    }

    @JvmStatic
    fun syncChatInputRowHeight() {
        val height = NonIslandHelper.chatInputRowHeight()
        val delta = (height - 44) / 2
        ChatActivityEnterView.DEFAULT_HEIGHT = height
        ChatActivityEnterView.inu_FIELD_PADDING_TOP = 9 + delta
        ChatActivityEnterView.inu_FIELD_PADDING_BOTTOM = 10 + delta
        ChatActivityEnterView.inu_ICON_PADDING = 7.5f + delta
    }

    @JvmStatic
    fun getCurrentAppIconLicense(): CharSequence {
        val current = LauncherIconController.LauncherIcon.entries
            .firstOrNull { LauncherIconController.isEnabled(it) }
        val resId = when (current) {
            LauncherIconController.LauncherIcon.DEFAULT,
            LauncherIconController.LauncherIcon.OLD -> R.string.InuAppIconLicenseInugram
            else -> R.string.InuAppIconLicenseTelegram
        }
        return AndroidUtilities.replaceTags(getString(resId))
    }
}
