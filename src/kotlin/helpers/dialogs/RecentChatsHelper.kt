package desu.inugram.helpers.dialogs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.TextUtils
import android.util.SparseArray
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import desu.inugram.helpers.InuDatabaseHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.Emoji
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarPopupWindow
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.ProfileActivity
import org.telegram.ui.TopicsFragment
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicReference

/**
 * Quick-switcher popup listing the most recently opened dialogs. Purely client-side (own
 * [InuDatabaseHelper] table, populated by [addToRecentDialogs] — see the stock hook in
 * ChatActivity's view creation) - inspired by exteraless/NekoGram's BackButtonMenuRecent, adapted
 * to this fork's storage conventions instead of a dedicated SharedPreferences file.
 */
object RecentChatsHelper {
    private const val MAX_RECENT_DIALOGS = 25

    // account -> most-recently-opened first
    private val cache = SparseArray<LinkedList<Long>>()

    @JvmStatic
    fun load(account: Int) {
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val loaded = LinkedList(InuDatabaseHelper.loadRecentDialogs(db))
            synchronized(cache) {
                cache.put(account, loaded)
            }
        }
    }

    private fun listFor(account: Int): LinkedList<Long> {
        synchronized(cache) {
            return cache.get(account) ?: LinkedList<Long>().also { cache.put(account, it) }
        }
    }

    @JvmStatic
    fun addToRecentDialogs(account: Int, dialogId: Long) {
        if (dialogId == 0L) return
        synchronized(cache) {
            val list = listFor(account)
            list.remove(dialogId)
            list.addFirst(dialogId)
            while (list.size > MAX_RECENT_DIALOGS) list.removeLast()
        }
        val storage = MessagesStorage.getInstance(account) ?: return
        val openedAt = System.currentTimeMillis()
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            InuDatabaseHelper.saveRecentDialog(db, dialogId, openedAt)
            InuDatabaseHelper.trimRecentDialogs(db, MAX_RECENT_DIALOGS)
        }
    }

    @JvmStatic
    fun clearRecentDialogs(account: Int) {
        synchronized(cache) {
            listFor(account).clear()
        }
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            InuDatabaseHelper.clearRecentDialogs(db)
        }
    }

    @JvmStatic
    fun show(fragment: BaseFragment, anchor: View) {
        val currentAccount = fragment.currentAccount
        val context = fragment.parentActivity ?: return
        val fragmentView = fragment.fragmentView ?: return
        val dialogs = synchronized(cache) { ArrayList(listFor(currentAccount)) }
        if (dialogs.isEmpty()) return

        val layout = object : ActionBarPopupWindow.ActionBarPopupWindowLayout(context, R.drawable.popup_fixed_alert4, fragment.getResourceProvider()) {
            val path = Path()
            override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
                canvas.save()
                path.rewind()
                AndroidUtilities.rectTmp.set(child.left.toFloat(), child.top.toFloat(), child.right.toFloat(), child.bottom.toFloat())
                path.addRoundRect(AndroidUtilities.rectTmp, dp(12f).toFloat(), dp(12f).toFloat(), Path.Direction.CW)
                canvas.clipPath(path)
                val draw = super.drawChild(canvas, child, drawingTime)
                canvas.restore()
                return draw
            }
        }
        val backgroundPaddings = Rect()
        val shadowDrawable: Drawable = context.resources.getDrawable(R.drawable.popup_fixed_alert4).mutate()
        shadowDrawable.getPadding(backgroundPaddings)
        layout.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground))

        val scrimPopupWindowRef = AtomicReference<ActionBarPopupWindow?>()

        val headerView = FrameLayout(context)
        headerView.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground))

        val titleTextView = TextView(context)
        titleTextView.setTextColor(Theme.getColor(Theme.key_dialogTextBlue))
        titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        titleTextView.text = getString(R.string.InuRecentChats)
        titleTextView.typeface = AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM)
        headerView.addView(titleTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 24, Gravity.LEFT))

        val clearImageView = ImageView(context)
        clearImageView.scaleType = ImageView.ScaleType.CENTER
        clearImageView.setColorFilter(Theme.getColor(Theme.key_actionBarDefaultSubmenuItemIcon))
        clearImageView.setImageResource(R.drawable.msg_close)
        clearImageView.background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector))
        clearImageView.setOnClickListener {
            AlertDialog.Builder(context, fragment.getResourceProvider())
                .setTitle(getString(R.string.InuClearRecentChats))
                .setMessage(getString(R.string.InuClearRecentChatsAlert))
                .setPositiveButton(getString(R.string.ClearButton).uppercase()) { _, _ ->
                    scrimPopupWindowRef.getAndSet(null)?.dismiss()
                    clearRecentDialogs(currentAccount)
                }
                .setNegativeButton(getString(R.string.Cancel), null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .show()
        }
        headerView.addView(clearImageView, LayoutHelper.createFrame(24, 24, Gravity.RIGHT or Gravity.CENTER_VERTICAL))

        headerView.setPadding(dp(9f), dp(8f), dp(8f), dp(8f))
        layout.addView(headerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0f, 0f, 0f, 0f))

        val controller = MessagesController.getInstance(currentAccount)
        for (dialogId in dialogs) {
            val chat: TLRPC.Chat?
            val user: TLRPC.User?
            if (dialogId < 0) {
                chat = controller.getChat(-dialogId)
                user = null
            } else {
                chat = null
                user = controller.getUser(dialogId)
            }
            if (chat == null && user == null) continue

            val cell = FrameLayout(context)
            cell.minimumWidth = dp(200f)

            val imageView = BackupImageView(context)
            imageView.setRoundRadius(if (chat != null && chat.forum) dp(8f) else dp(16f))
            cell.addView(imageView, LayoutHelper.createFrameRelatively(32f, 32f, Gravity.START or Gravity.CENTER_VERTICAL, 13f, 0f, 0f, 0f))

            val titleView = TextView(context)
            titleView.setLines(1)
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            titleView.setTextColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuItem))
            titleView.ellipsize = TextUtils.TruncateAt.END
            cell.addView(titleView, LayoutHelper.createFrameRelatively(LayoutHelper.MATCH_PARENT.toFloat(), LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.START or Gravity.CENTER_VERTICAL, 59f, 0f, 12f, 0f))

            val avatarDrawable = AvatarDrawable()
            avatarDrawable.setScaleSize(.8f)
            var thumb: Drawable = avatarDrawable

            if (chat != null) {
                avatarDrawable.setInfo(chat)
                chat.photo?.strippedBitmap?.let { thumb = it }
                imageView.setImage(ImageLocation.getForChat(chat, ImageLocation.TYPE_SMALL), "50_50", thumb, chat)
                titleView.text = Emoji.replaceEmoji(chat.title, titleView.paint.fontMetricsInt, false)
            } else if (user != null) {
                val name: String
                user.photo?.strippedBitmap?.let { thumb = it }
                if (UserObject.isReplyUser(user)) {
                    name = getString(R.string.RepliesTitle)
                    avatarDrawable.avatarType = AvatarDrawable.AVATAR_TYPE_REPLIES
                    imageView.setImageDrawable(avatarDrawable)
                } else if (UserObject.isDeleted(user)) {
                    name = getString(R.string.HiddenName)
                    avatarDrawable.setInfo(user)
                    imageView.setImage(ImageLocation.getForUser(user, ImageLocation.TYPE_SMALL), "50_50", avatarDrawable, user)
                } else {
                    name = UserObject.getUserName(user)
                    avatarDrawable.setInfo(user)
                    imageView.setImage(ImageLocation.getForUser(user, ImageLocation.TYPE_SMALL), "50_50", thumb, user)
                }
                titleView.text = Emoji.replaceEmoji(name, titleView.paint.fontMetricsInt, false)
            }

            cell.background = Theme.getSelectorDrawable(Theme.getColor(Theme.key_listSelector), false)
            cell.setOnClickListener {
                scrimPopupWindowRef.getAndSet(null)?.dismiss()
                val bundle = Bundle()
                if (dialogId < 0) {
                    bundle.putLong("chat_id", -dialogId)
                    if (controller.isForum(dialogId)) {
                        fragment.presentFragment(TopicsFragment(bundle))
                    } else {
                        fragment.presentFragment(ChatActivity(bundle))
                    }
                } else {
                    bundle.putLong("user_id", dialogId)
                    fragment.presentFragment(ChatActivity(bundle))
                }
            }
            cell.setOnLongClickListener {
                scrimPopupWindowRef.getAndSet(null)?.dismiss()
                val bundle = Bundle()
                if (dialogId < 0) bundle.putLong("chat_id", -dialogId) else bundle.putLong("user_id", dialogId)
                fragment.presentFragment(ProfileActivity(bundle))
                true
            }
            layout.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))
        }

        val scrimPopupWindow = ActionBarPopupWindow(layout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT)
        scrimPopupWindowRef.set(scrimPopupWindow)
        scrimPopupWindow.setPauseNotifications(true)
        scrimPopupWindow.setDismissAnimationDuration(220)
        scrimPopupWindow.isOutsideTouchable = true
        scrimPopupWindow.isClippingEnabled = true
        scrimPopupWindow.animationStyle = R.style.PopupContextAnimation
        scrimPopupWindow.isFocusable = true
        layout.measure(View.MeasureSpec.makeMeasureSpec(dp(1000f), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(dp(1000f), View.MeasureSpec.AT_MOST))
        scrimPopupWindow.inputMethodMode = ActionBarPopupWindow.INPUT_METHOD_NOT_NEEDED
        scrimPopupWindow.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        scrimPopupWindow.contentView.isFocusableInTouchMode = true
        layout.setFitItems(true)

        var popupX = dp(8f) - backgroundPaddings.left
        if (AndroidUtilities.isTablet()) {
            val location = IntArray(2)
            fragmentView.getLocationInWindow(location)
            popupX += location[0]
        }
        val popupY = anchor.bottom - backgroundPaddings.top - dp(8f)
        scrimPopupWindow.showAtLocation(fragmentView, Gravity.LEFT or Gravity.TOP, popupX, popupY)
        scrimPopupWindow.dimBehind()
    }
}
