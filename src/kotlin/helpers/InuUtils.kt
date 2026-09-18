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

    @JvmStatic
    fun copyFileUriToClipboard(file: File): Boolean = runCatching {
        val context = ApplicationLoader.applicationContext
        val uri = FileProvider.getUriForFile(context, ApplicationLoader.getApplicationId() + ".provider", file)
        val clip = ClipData.newUri(context.contentResolver, "photo", uri)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(clip)
        true
    }.onFailure { FileLog.e(it) }.getOrDefault(false)

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

    @JvmStatic
    fun centerScreenTitles(): Boolean = desu.inugram.InuConfig.CENTER_TITLE_MAIN.value

    @JvmStatic
    fun centerChatTitle(): Boolean = desu.inugram.InuConfig.CENTER_TITLE_CHATS.value

    @JvmStatic
    fun compactChatPill(): Boolean =
        centerChatTitle() && desu.inugram.InuConfig.IOS_CHAT_HEADER.value

    @JvmStatic
    fun chatAvatarInMenuSlot(): Boolean =
        compactChatPill() && desu.inugram.InuConfig.IOS_CHAT_HEADER_AVATAR_SLOT.value

    @JvmStatic
    fun chatAvatarStatic(): Boolean =
        compactChatPill() &&
            !chatAvatarInMenuSlot() &&
            desu.inugram.InuConfig.IOS_CHAT_HEADER_AVATAR_STATIC.value

    @JvmStatic
    fun chatAvatarOnRight(): Boolean =
        centerChatTitle() &&
            !chatAvatarInMenuSlot() &&
            !chatAvatarStatic() &&
            desu.inugram.InuConfig.CENTER_TITLE_RIGHT_AVATAR.value

    @JvmStatic
    fun shouldCenterTitle(fragment: Any?): Boolean {
        if (fragment == null) return false
        if (fragment.javaClass.name == "org.telegram.ui.ChatActivity") {
            return centerChatTitle()
        }
        return centerScreenTitles()
    }
}