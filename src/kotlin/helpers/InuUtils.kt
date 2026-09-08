package desu.inugram.helpers

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.View
import androidx.core.content.FileProvider
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.tgnet.NativeByteBuffer
import org.telegram.tgnet.TLObject
import java.io.File
import kotlin.system.exitProcess

public object InuUtils {
    private val _nextId = java.util.concurrent.atomic.AtomicInteger(1)
    fun generateId(): Int {
        return _nextId.getAndIncrement()
    }


    @JvmStatic
    fun setAutofillHint(view: View, hint: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.setAutofillHints(hint)
            view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        }
    }

    @JvmStatic
    fun restartApp(activity: Activity) {
        val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
        activity.finishAffinity()
        activity.startActivity(intent)
        exitProcess(0)
    }

    /** Sets the system clipboard to a content URI for the given file. Caller handles bulletins. */
    @JvmStatic
    fun copyFileUriToClipboard(file: File): Boolean = runCatching {
        val context = ApplicationLoader.applicationContext
        val uri = FileProvider.getUriForFile(context, ApplicationLoader.getApplicationId() + ".provider", file)
        val clip = ClipData.newUri(context.contentResolver, "photo", uri)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(clip)
        true
    }.onFailure { FileLog.e(it) }.getOrDefault(false)

    /** Deep-clones a [TLObject] via serialize → deserialize round-trip. */
    inline fun <T : TLObject, R : TLObject> cloneTLObject(
        obj: T,
        deserialize: (NativeByteBuffer, Int, Boolean) -> R?,
    ): R? {
        val buf = NativeByteBuffer(obj.objectSize)
        return try {
            obj.serializeToStream(buf)
            buf.position(0)
            deserialize(buf, buf.readInt32(false), false)
        } catch (e: Exception) {
            FileLog.e(e)
            null
        } finally {
            buf.reuse()
        }
    }

    // ---- header centering matrix ----------------------------------------------------------
    // Single source of truth for the whole "Centering" group. The toggles are strictly nested,
    // so every predicate re-checks its parents: a stale pref from an older build (or a deeplink
    // that flipped a child while its parent was off) can never produce a half-centered header.
    // Java call sites ask these questions, never InuConfig directly.

    /** Centered action bar titles on every screen except chats. Root of the group. */
    @JvmStatic
    fun centerScreenTitles(): Boolean = desu.inugram.InuConfig.CENTER_TITLE_MAIN.value

    /** Centered title/subtitle inside chat and channel headers. */
    @JvmStatic
    fun centerChatTitle(): Boolean =
        centerScreenTitles() && desu.inugram.InuConfig.CENTER_TITLE_CHATS.value

    /** Centered chat pill hugs its content instead of spanning the whole free room. */
    @JvmStatic
    fun compactChatPill(): Boolean =
        centerChatTitle() && desu.inugram.InuConfig.IOS_CHAT_HEADER.value

    /** Avatar takes over the action bar's overflow slot. Compact pill only. */
    @JvmStatic
    fun chatAvatarInMenuSlot(): Boolean =
        compactChatPill() && desu.inugram.InuConfig.IOS_CHAT_HEADER_AVATAR_SLOT.value

    /**
     * Avatar sits at the right end of the centered pill. The menu slot wins when both are on:
     * the avatar cannot be in two places, and moving it out of the pill is the more specific ask.
     */
    @JvmStatic
    fun chatAvatarOnRight(): Boolean =
        centerChatTitle() &&
            !chatAvatarInMenuSlot() &&
            desu.inugram.InuConfig.CENTER_TITLE_RIGHT_AVATAR.value

    @JvmStatic
    fun shouldCenterTitle(fragment: Any?): Boolean {
        if (fragment == null) return false // not attached yet — don't center until we know which screen this is
        if (fragment.javaClass.name == "org.telegram.ui.ChatActivity") {
            return centerChatTitle()
        }
        return centerScreenTitles()
    }
}