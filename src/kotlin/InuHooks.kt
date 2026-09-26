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
import desu.inugram.helpers.media.MediaSendDebugHelper
import desu.inugram.helpers.security.PasscodeHelper
import desu.inugram.helpers.theme.MonetHelper
import desu.inugram.helpers.theme.NonIslandHelper
import desu.inugram.helpers.theme.ThemeSwitchHelper
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
        desu.inugram.helpers.network.CensorshipHelper.init()
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
        Utilities.globalQueue.postRunnable {
            UpdateHelper.clearPendingIfInstalled()
            ApkInstaller.dismissInstalledNotification()
        }
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
        for (controller in desu.inugram.helpers.feed.FeedController.allActiveFor(acc)) {
            controller.onNewMessages(messages)
        }
    }

    private val feedPruneObserver = NotificationCenter.NotificationCenterDelegate { id, acc, args ->
        val controllers = desu.inugram.helpers.feed.FeedController.allActiveFor(acc)
        if (controllers.isEmpty()) return@NotificationCenterDelegate
        when (id) {
            NotificationCenter.messagesDeleted -> {
                @Suppress("UNCHECKED_CAST")
                val ids = args[0] as? ArrayList<Int> ?: return@NotificationCenterDelegate
                val channelId = args.getOrNull(1) as? Long ?: 0L
                if (channelId != 0L) {
                    for (controller in controllers) {
                        // entiny: try both signed and negated channelId because stock notification sites disagree on sign convention
                        controller.onMessagesDeleted(channelId, ids)
                        controller.onMessagesDeleted(-channelId, ids)
                    }
                }
            }
            NotificationCenter.historyCleared -> {
                val dialogId = args.getOrNull(0) as? Long ?: return@NotificationCenterDelegate
                for (controller in controllers) controller.onHistoryCleared(dialogId)
            }
        }
    }

    // entiny: re-assert presence on reconnect because stock resets connection-level online state
    private val connectionStateObserver = NotificationCenter.NotificationCenterDelegate { id, acc, _ ->
        if (id != NotificationCenter.didUpdateConnectionState) return@NotificationCenterDelegate
        if (org.telegram.tgnet.ConnectionsManager.getInstance(acc).connectionState
            != org.telegram.tgnet.ConnectionsManager.ConnectionStateConnected
        ) return@NotificationCenterDelegate
        desu.inugram.helpers.security.GhostHelper.syncPresence(acc)
    }

    @JvmStatic
    fun onMessagesControllerCreated(messagesController: MessagesController, account: Int) {
        MapsHelper.syncMapProvider(messagesController)
        desu.inugram.helpers.dialogs.PinHelper.load(account)
        desu.inugram.helpers.dialogs.FolderMembershipHelper.load(account)
        desu.inugram.helpers.dialogs.RecentChatsHelper.load(account)
        desu.inugram.helpers.security.PresenceHelper.load(account)
        desu.inugram.helpers.security.GhostHelper.syncPresence(account)
        desu.inugram.helpers.badges.BadgeRegistry.init(account)
        desu.inugram.helpers.chat.SavedMessagesHelper.pruneIfNeeded(account)
        desu.inugram.helpers.security.PresenceHelper.pruneIfNeeded(account)
        AndroidUtilities.runOnUIThread {
            val nc = NotificationCenter.getInstance(account)
            // entiny: drop stale observers before adding because MessagesController can recreate without clearing them
            nc.removeObserver(newMessagesObserver, NotificationCenter.didReceiveNewMessages)
            nc.addObserver(newMessagesObserver, NotificationCenter.didReceiveNewMessages)
            nc.removeObserver(connectionStateObserver, NotificationCenter.didUpdateConnectionState)
            nc.addObserver(connectionStateObserver, NotificationCenter.didUpdateConnectionState)
            nc.removeObserver(feedPruneObserver, NotificationCenter.messagesDeleted)
            nc.addObserver(feedPruneObserver, NotificationCenter.messagesDeleted)
            nc.removeObserver(feedPruneObserver, NotificationCenter.historyCleared)
            nc.addObserver(feedPruneObserver, NotificationCenter.historyCleared)
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
        ThemeSwitchHelper.resetStuckOverlay(launchActivity)
        val bg = Theme.getColor(Theme.key_windowBackgroundWhite)
        launchActivity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        CrashReporter.maybeShowReportSheet(launchActivity)
        ProxyVpnHelper.reconcile()
        DrawerHelper.refreshUpdateState()
        desu.inugram.helpers.security.GhostHelper.syncPresence(org.telegram.messenger.UserConfig.selectedAccount)
        MediaSendDebugHelper.startWatchingCache()
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
