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
    private var _nextId = 1;
    fun generateId(): Int {
        return _nextId++
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

    @JvmStatic
    fun shouldCenterTitle(fragment: Any?): Boolean {
        if (fragment == null) return false
        val name = fragment.javaClass.name
        return when {
            name == "org.telegram.ui.ChatActivity" -> desu.inugram.InuConfig.CENTER_TITLE_CHATS.value
            name == "org.telegram.ui.ProfileActivity" -> desu.inugram.InuConfig.CENTER_TITLE_PROFILE.value
            name == "org.telegram.ui.DialogsActivity" -> desu.inugram.InuConfig.CENTER_TITLE_DIALOGS.value
            name.startsWith("org.telegram.ui") && name.contains("Settings") -> desu.inugram.InuConfig.CENTER_TITLE_SETTINGS.value
            else -> false
        }
    }
}