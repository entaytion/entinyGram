package desu.inugram.helpers

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import desu.inugram.InuConfig
import desu.inugram.helpers.security.GhostHelper
// #if PLUGINS
import desu.inugram.helpers.plugins.PluginManager
// #endif
import desu.inugram.helpers.security.ParanoiaHelper
import desu.inugram.helpers.security.PasscodeHelper
import desu.inugram.ui.AccountPickerActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.AlertsCreator
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.LaunchActivity

object ShortcutHelper {
    const val SWITCH_ACCOUNT_ACTION = "desu.inugram.action.SWITCH_ACCOUNT"

    private class Entry(
        val id: String,
        val action: String,
        val labelRes: Int,
        val longLabelRes: Int = labelRes,
        val iconRes: Int,
        val rank: Int,
        val target: Class<out Activity> = LaunchActivity::class.java,
        val requiresUnlocked: Boolean = false,
        val shouldShow: () -> Boolean,
        val onClick: (LaunchActivity) -> Unit,
    )

    private val entries = listOf(
        Entry(
            id = "inu_enter_paranoia",
            action = "desu.inugram.action.ENTER_PARANOIA",
            labelRes = R.string.InuParanoiaMode,
            iconRes = R.drawable.inu_shortcut_paranoia,
            rank = 0,
            shouldShow = ParanoiaHelper::shouldShowLauncherShortcut,
            onClick = { activity ->
                if (!ParanoiaHelper.isParanoia() && ParanoiaHelper.canUseLauncherShortcut()) {
                    ParanoiaHelper.enableParanoia(activity)
                }
            },
        ),
        Entry(
            id = "inu_switch_account",
            action = SWITCH_ACCOUNT_ACTION,
            labelRes = R.string.InuAccountSwitchShortcut,
            iconRes = R.drawable.inu_shortcut_switch_account,
            rank = 1,
            target = AccountPickerActivity::class.java,
            requiresUnlocked = true,
            shouldShow = { InuConfig.ACCOUNT_SWITCH_SHORTCUT.value && countSelectableAccounts() > 1 },
            onClick = { activity -> showAccountPicker(activity) },
        ),
        Entry(
            id = "inu_toggle_ghost_mode",
            action = "desu.inugram.action.TOGGLE_GHOST_MODE",
            labelRes = R.string.InuGhostMode,
            iconRes = R.drawable.inu_shortcut_ghost,
            // entiny: rank 2 sank below the "1 + a" ranks MediaDataController.buildShortcuts() gives recent
            // chats, so the toggle showed up after them instead of right under "New conversation" (rank 0)
            rank = 0,
            shouldShow = { InuConfig.GHOST_MODE_LAUNCHER_SHORTCUT.value },
            onClick = { activity -> GhostHelper.toggleGhostMode() },
        ),
        // #if PLUGINS
        Entry(
            id = "inu_plugins_safe_mode",
            action = PluginManager.SAFE_MODE_ACTION,
            labelRes = R.string.InuPluginsSafeMode,
            longLabelRes = R.string.InuPluginsSafeModeShortcut,
            iconRes = R.drawable.inu_shortcut_safe_mode,
            rank = 2,
            shouldShow = PluginManager::isEngineEnabled,
            onClick = { PluginManager.requestSafeMode() },
        ),
        // #endif
    )

    @JvmStatic
    fun sync(context: Context) {
        for (entry in entries) {
            if (entry.shouldShow()) {
                val intent = Intent(context, entry.target).setAction(entry.action)
                val shortcut = ShortcutInfoCompat.Builder(context, entry.id)
                    .setShortLabel(getString(entry.labelRes))
                    .setLongLabel(getString(entry.longLabelRes))
                    .setIcon(IconCompat.createWithResource(context, entry.iconRes))
                    .setRank(entry.rank)
                    .setIntent(intent)
                    .build()
                ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            } else {
                ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(entry.id))
            }
        }
    }

    @JvmStatic
    fun handleAction(activity: LaunchActivity, intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        val entry = entries.firstOrNull { it.action == action } ?: return false
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return true
        // returning false lets stock stash the intent and replay handleIntent() once unlocked,
        // instead of us drawing over the passcode screen.
        if (entry.requiresUnlocked && (AndroidUtilities.needShowPasscode() || SharedConfig.isWaitingForPasscodeEnter)) {
            return false
        }
        entry.onClick(activity)
        return true
    }

    // adaptive icons show only the central 72/108 of the bitmap, the rest is bleed for masks and parallax
    @JvmStatic
    fun createAvatarIcon(photo: Bitmap?, user: TLRPC.User?, chat: TLRPC.Chat?): IconCompat {
        val serviceType = when {
            user == null -> AvatarDrawable.AVATAR_TYPE_NORMAL
            UserObject.isReplyUser(user) -> AvatarDrawable.AVATAR_TYPE_REPLIES
            UserObject.isUserSelf(user) -> AvatarDrawable.AVATAR_TYPE_SAVED
            else -> AvatarDrawable.AVATAR_TYPE_NORMAL
        }
        val avatar = if (photo != null && serviceType == AvatarDrawable.AVATAR_TYPE_NORMAL) {
            photo
        } else {
            val placeholderSize = AndroidUtilities.dp(58f)
            val drawable = (if (user != null) AvatarDrawable(user) else AvatarDrawable(chat)).apply {
                if (serviceType != AvatarDrawable.AVATAR_TYPE_NORMAL) avatarType = serviceType
                setRoundRadius(1)
                setBounds(0, 0, placeholderSize, placeholderSize)
            }
            Bitmap.createBitmap(placeholderSize, placeholderSize, Bitmap.Config.ARGB_8888).also { drawable.draw(Canvas(it)) }
        }

        val size = AndroidUtilities.dp(108f)
        val inset = size / 6f
        val matrix = Matrix().apply {
            setRectToRect(
                RectF(0f, 0f, avatar.width.toFloat(), avatar.height.toFloat()),
                RectF(inset, inset, size - inset, size - inset),
                Matrix.ScaleToFit.FILL,
            )
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(avatar, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        }
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(result).drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        return IconCompat.createWithAdaptiveBitmap(result)
    }

    fun countSelectableAccounts(): Int =
        (0 until UserConfig.MAX_ACCOUNT_COUNT).count {
            UserConfig.getInstance(it).currentUser != null && !PasscodeHelper.isAccountHidden(it)
        }

    private fun showAccountPicker(activity: LaunchActivity) {
        if (countSelectableAccounts() < 2) return
        val previous = UserConfig.selectedAccount
        val controller = MessagesController.getInstance(previous)
        // entiny: ignoreSetOnline prevents resuming account from reporting online before switch completes
        controller.ignoreSetOnline = true

        val dialog = AlertsCreator.createAccountSelectDialog(activity) { account ->
            controller.ignoreSetOnline = false
            if (account != previous) activity.switchToAccount(account, true)
        }
        if (dialog == null) {
            controller.ignoreSetOnline = false
            return
        }
        dialog.setOnDismissListener { controller.ignoreSetOnline = false }
        dialog.show()
    }
}
