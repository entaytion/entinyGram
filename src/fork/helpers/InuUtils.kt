package desu.inugram.helpers

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import androidx.core.content.FileProvider
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.NativeByteBuffer
import org.telegram.tgnet.SerializedData
import org.telegram.tgnet.TLObject
import org.telegram.ui.ActionBar.BaseFragment
import desu.inugram.ui.feed.FeedActivity
import java.io.File
import kotlin.system.exitProcess

public object InuUtils {
    private val _nextId = java.util.concurrent.atomic.AtomicInteger(1)
    fun generateId(): Int {
        return _nextId.getAndIncrement()
    }

    fun parseFloodWaitSeconds(text: String): Int? {
        if (!text.startsWith("FLOOD_WAIT_") && !text.startsWith("FLOOD_PREMIUM_WAIT_")) return null
        return text.substringAfter("WAIT_").toIntOrNull()?.coerceAtLeast(1)
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

    fun linkUsernames(text: CharSequence, onClick: (String) -> Unit): CharSequence {
        val result = SpannableStringBuilder(text)
        val links = result.getSpans(0, result.length, URLSpan::class.java)
        MessageObject.addUrlsByPattern(false, result, false, 0, 0, false)
        for (span in result.getSpans(0, result.length, URLSpan::class.java)) {
            if (span in links) continue
            val start = result.getSpanStart(span)
            val end = result.getSpanEnd(span)
            result.removeSpan(span)
            if (!span.url.startsWith("@") || links.any { result.getSpanStart(it) < end && start < result.getSpanEnd(it) }) continue
            result.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        onClick(span.url.substring(1))
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = ds.linkColor
                    }
                },
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return result
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

    // serialized message without attach path. stack-inspection monkeypatch —
    // we can't actually override Message.writeAttachPath without a hooking lib,
    // so we no-op the writes it would issue when we detect it in the stack.
    // it writes its string first, and an int only right after it.
    class CleanSerializedData(size: Int) : SerializedData(size) {
        private var skippedAttachPath = false

        private fun inWriteAttachPath(): Boolean {
            val stack = Thread.currentThread().stackTrace
            for (i in 2 until stack.size.coerceAtMost(8)) {
                if (stack[i].methodName == "writeAttachPath") return true
            }
            return false
        }

        override fun writeString(s: String) {
            skippedAttachPath = inWriteAttachPath()
            if (!skippedAttachPath) super.writeString(s)
        }

        override fun writeInt32(x: Int) {
            if (skippedAttachPath && inWriteAttachPath()) return
            skippedAttachPath = false
            super.writeInt32(x)
        }
    }

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
    fun centerScreenTitles(): Boolean = desu.inugram.InuConfig.CENTER_TITLE_MAIN.value

    @JvmStatic
    fun centerChatTitle(): Boolean = desu.inugram.InuConfig.CENTER_TITLE_CHATS.value

    @JvmStatic
    fun compactChatPill(): Boolean =
        centerChatTitle() && desu.inugram.InuConfig.IOS_CHAT_HEADER.value

    @JvmStatic
    fun hideChatAvatar(): Boolean = desu.inugram.InuConfig.HIDE_CHAT_AVATAR.value

    @JvmStatic
    fun chatAvatarInMenuSlot(): Boolean =
        !hideChatAvatar() && compactChatPill() && desu.inugram.InuConfig.IOS_CHAT_HEADER_AVATAR_SLOT.value

    @JvmStatic
    fun chatAvatarSwapTapAndHold(): Boolean =
        chatAvatarInMenuSlot() && desu.inugram.InuConfig.IOS_CHAT_HEADER_AVATAR_SWAP.value

    @JvmStatic
    fun chatAvatarStatic(): Boolean =
        !hideChatAvatar() && compactChatPill() &&
            !chatAvatarInMenuSlot() &&
            desu.inugram.InuConfig.IOS_CHAT_HEADER_AVATAR_STATIC.value

    @JvmStatic
    fun chatAvatarOnRight(): Boolean =
        centerChatTitle() &&
            !hideChatAvatar() &&
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

    @JvmStatic
    fun isFeedHeader(fragment: BaseFragment?): Boolean = fragment is FeedActivity
}
