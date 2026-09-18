package desu.inugram.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import desu.inugram.helpers.chat.SavedMessagesHelper
import desu.inugram.helpers.chat.SavedMessagesHelper.EditEntry
import java.io.File
import java.util.ArrayList
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ActionBar.ThemeDescription
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.SizeNotifierFrameLayout

class AyuMessageHistoryActivity(
    private val targetMessageObject: MessageObject
) : BaseFragment() {

    private val historyEntries = ArrayList<EditEntry>()
    private val messageObjects = ArrayList<MessageObject?>()
    private var listView: RecyclerListView? = null
    private var emptyView: View? = null
    private var loaded = false

    // entiny: checkLayout and generateLayout return early if peer_id is null so synthesized messages need a fallback peer
    private val peer: TLRPC.Peer?
        get() = targetMessageObject.messageOwner?.peer_id
            ?: messagesController?.getPeer(targetMessageObject.getDialogId())

    private fun loadHistory() {
        val dialogId = targetMessageObject.getDialogId()
        val msgId = targetMessageObject.id
        SavedMessagesHelper.getEditHistoryAsync(currentAccount, dialogId, msgId) { list ->
            historyEntries.clear()
            for (entry in list) {
                val media = entry.mediaPath?.takeIf { it.isNotBlank() }
                if (entry.text.isBlank() && media == null) continue
                val last = historyEntries.lastOrNull()
                if (last != null && last.text.trim() == entry.text.trim() && last.mediaPath == media) continue
                historyEntries.add(EditEntry(entry.timestamp, entry.text, media, entry.entities, entry.media))
            }

            // entiny: use raw message rather than messageText because entity offsets address the unformatted raw string
            val owner = targetMessageObject.messageOwner
            val rawCurrent = owner?.message ?: ""
            val currentText = if (rawCurrent.isNotEmpty()) rawCurrent else targetMessageObject.messageText?.toString().orEmpty()
            val currentEntities = if (currentText == rawCurrent) owner?.entities else null
            val editDate = owner?.edit_date ?: 0
            val currentDate = when {
                editDate != 0 -> editDate.toLong()
                (owner?.date ?: 0) != 0 -> owner!!.date.toLong()
                else -> System.currentTimeMillis() / 1000
            }
            currentVersionIndex = -1
            if (currentText.isNotBlank()) {
                val alreadyPresent = historyEntries.any { it.text.trim() == currentText.trim() && it.mediaPath == null }
                if (!alreadyPresent) {
                    currentVersionIndex = historyEntries.size
                    historyEntries.add(EditEntry(currentDate, currentText, null, currentEntities, owner?.media))
                }
            }

            loaded = true
            rebuildMessageObjects()
            listView?.adapter?.notifyDataSetChanged()
            updateEmptyView()
            if (historyEntries.isNotEmpty()) {
                listView?.scrollToPosition(historyEntries.size - 1)
            }
        }
    }

    private fun rebuildMessageObjects() {
        messageObjects.clear()
        for (i in historyEntries.indices) {
            messageObjects.add(null)
        }
    }

    private fun isStoredRevision(position: Int): Boolean =
        position >= 0 && position < historyEntries.size && position != currentVersionIndex

    private var currentVersionIndex = -1
    private var diffItem: org.telegram.ui.ActionBar.ActionBarMenuSubItem? = null

    override fun createView(context: Context): View {
        val dialogId = targetMessageObject.getDialogId()
        val peerObject = messagesController.getUserOrChat(dialogId)
        val name = when (peerObject) {
            is TLRPC.User -> peerObject.first_name ?: ""
            is TLRPC.Chat -> peerObject.title ?: ""
            else -> LocaleController.getString(R.string.InuEditHistory)
        }

        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(true)
        actionBar.setTitle(name)
        actionBar.setSubtitle("#${targetMessageObject.id}")
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                when (id) {
                    -1 -> finishFragment()
                    MENU_TOGGLE_DIFF -> {
                        val enabled = InuConfig.SHOW_EDIT_HISTORY_DIFF.toggle()
                        diffItem?.setChecked(enabled)
                        rebuildMessageObjects()
                        listView?.adapter?.notifyDataSetChanged()
                    }
                }
            }
        })
        diffItem = actionBar.createMenu()
            .addItem(MENU_MAIN, R.drawable.ic_ab_other)
            .addSubItem(MENU_TOGGLE_DIFF, R.drawable.msg_customize, LocaleController.getString(R.string.InuEditHistoryDiff), true)
        diffItem?.setChecked(InuConfig.SHOW_EDIT_HISTORY_DIFF.value)

        val frameLayout = object : SizeNotifierFrameLayout(context) {
            override fun isActionBarVisible(): Boolean = false
            override fun isStatusBarVisible(): Boolean = false
            override fun useRootView(): Boolean = false
        }
        frameLayout.setOccupyStatusBar(false)
        // entiny: set solid color fallback because setBackgroundImage(null) no-ops when theme has no cached wallpaper
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
        frameLayout.setBackgroundImage(Theme.getCachedWallpaper(), Theme.isWallpaperMotion())

        val recycler = RecyclerListView(context).apply {
            setItemAnimator(null)
            setLayoutAnimation(null)
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            setVerticalScrollBarEnabled(true)
            clipToPadding = false
            setPadding(0, AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(8f))
            adapter = HistoryAdapter(context)
        }
        listView = recycler
        frameLayout.addView(recycler, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))

        val empty = object : TextView(context) {
            private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)

            override fun onDraw(canvas: Canvas) {
                backgroundPaint.color = Theme.getColor(Theme.key_chat_serviceBackground)
                AndroidUtilities.rectTmp.set(0f, 0f, width.toFloat(), height.toFloat())
                canvas.drawRoundRect(AndroidUtilities.rectTmp, AndroidUtilities.dp(14f).toFloat(), AndroidUtilities.dp(14f).toFloat(), backgroundPaint)
                super.onDraw(canvas)
            }
        }
        empty.text = LocaleController.getString(R.string.InuNoEditHistory)
        empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        empty.typeface = AndroidUtilities.bold()
        empty.setTextColor(Theme.getColor(Theme.key_chat_serviceText))
        empty.gravity = Gravity.CENTER
        empty.setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(6f), AndroidUtilities.dp(16f), AndroidUtilities.dp(8f))
        empty.visibility = View.GONE
        emptyView = empty
        frameLayout.addView(empty, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))

        fragmentView = frameLayout
        if (!loaded) {
            loadHistory()
        } else {
            updateEmptyView()
        }
        return frameLayout
    }

    private fun updateEmptyView() {
        emptyView?.visibility = if (loaded && historyEntries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showRowMenu(cell: AyuHistoryMessageCell) {
        val entry = cell.currentEntry() ?: return
        val position = cell.currentPosition()
        ItemOptions.makeOptions(this, cell)
            .addIf(!TextUtils.isEmpty(entry.text), R.drawable.msg_copy, LocaleController.getString(R.string.Copy)) {
                AndroidUtilities.addToClipboard(entry.text)
                BulletinFactory.of(this)
                    .createCopyBulletin(LocaleController.getString(R.string.MessageCopied))
                    .show()
            }
            .addIf(isStoredRevision(position), R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true) {
                deleteRevision(position)
            }
            .setGravity(if (cell.messageObject?.isOutOwner == true) Gravity.RIGHT else Gravity.LEFT)
            .show()
    }

    private fun deleteRevision(position: Int) {
        if (position < 0 || position >= historyEntries.size) return
        val entry = historyEntries.removeAt(position)
        if (currentVersionIndex > position) {
            currentVersionIndex--
        }
        if (position < messageObjects.size) {
            messageObjects.removeAt(position)
        }
        if (position < messageObjects.size) {
            messageObjects[position] = null
        }
        listView?.adapter?.notifyDataSetChanged()
        updateEmptyView()
        SavedMessagesHelper.deleteEditHistoryEntry(
            currentAccount,
            targetMessageObject.getDialogId(),
            targetMessageObject.id,
            entry.timestamp,
        )
    }

    override fun getThemeDescriptions(): ArrayList<ThemeDescription> {
        return ArrayList()
    }

    private inner class HistoryAdapter(private val context: Context) : RecyclerListView.SelectionAdapter() {

        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = true

        override fun getItemCount(): Int = historyEntries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val cell = AyuHistoryMessageCell(context)
            return RecyclerListView.Holder(cell)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val cell = holder.itemView as AyuHistoryMessageCell
            val entry = historyEntries[position]
            var syntheticMsg = messageObjects.getOrNull(position)
            if (syntheticMsg == null) {
                val prevEntry = if (position > 0) historyEntries[position - 1] else null
                syntheticMsg = createMessageObjectForEntry(entry, prevEntry, position == currentVersionIndex)
                if (position < messageObjects.size) {
                    messageObjects[position] = syntheticMsg
                }
            }
            cell.setEditEntry(entry, syntheticMsg, position)
        }

        private fun createMessageObjectForEntry(entry: EditEntry, prevEntry: EditEntry?, isLive: Boolean): MessageObject {
            val diff: CharSequence? = if (InuConfig.SHOW_EDIT_HISTORY_DIFF.value && prevEntry != null) {
                computeDiff(prevEntry.text, entry.text)
            } else {
                null
            }

            val owner = targetMessageObject.messageOwner
            val msg = TLRPC.TL_message().apply {
                id = targetMessageObject.id
                dialog_id = targetMessageObject.getDialogId()
                date = entry.timestamp.toInt()
                message = entry.text
                from_id = owner?.from_id
                peer_id = this@AyuMessageHistoryActivity.peer
                out = owner?.out == true
                post = owner?.post == true
                edit_hide = true
            }

            val entities = if (diff == null) entry.entities else null
            if (!entities.isNullOrEmpty()) {
                msg.entities = ArrayList(entities)
                msg.flags = msg.flags or TLRPC.MESSAGE_FLAG_HAS_ENTITIES
            }

            // entiny: only stored revisions carry media so text revisions stay TYPE_TEXT and build their text layout
            val savedFile = entry.mediaPath?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
            val storedMedia = entry.media
            if (storedMedia != null && storedMedia !is TLRPC.TL_messageMediaEmpty) {
                msg.media = storedMedia
                if (!isLive) {
                    // entiny: clear ttl on archived copies so one-time media does not render as an expired placeholder
                    storedMedia.ttl_seconds = 0
                }
                msg.flags = msg.flags or TLRPC.MESSAGE_FLAG_HAS_MEDIA
                if (savedFile != null) {
                    msg.attachPath = savedFile.absolutePath
                }
            } else if (savedFile != null) {
                msg.media = TLRPC.TL_messageMediaPhoto().apply { photo = TLRPC.TL_photo() }
                msg.flags = msg.flags or TLRPC.MESSAGE_FLAG_HAS_MEDIA
                msg.attachPath = savedFile.absolutePath
            }

            if (targetMessageObject.replyMessageObject != null) {
                msg.replyMessage = targetMessageObject.replyMessageObject.messageOwner
                msg.reply_to = owner?.reply_to
            }

            val msgObj = MessageObject(currentAccount, msg, false, true)
            if (savedFile != null) {
                msgObj.attachPathExists = true
                msgObj.mediaExists = true
            }
            if (diff != null) {
                msgObj.messageText = diff
            }
            msgObj.checkLayout()
            // entiny: mark as history preview so revisions sharing message id do not inherit deleted styling
            SavedMessagesHelper.markAsHistoryPreview(msgObj)
            return msgObj
        }
    }

    private inner class AyuHistoryMessageCell(context: Context) : ChatMessageCell(context, currentAccount) {
        private var entry: EditEntry? = null
        private var entryPosition = -1

        init {
            setFullyDraw(true)
            isChat = false
            // entiny: empty delegate keeps canPerformActions false so row click listener handles touches
            setDelegate(object : ChatMessageCellDelegate {})

            setOnClickListener {
                val currentEntry = entry ?: return@setOnClickListener
                val msgObj = messageObject

                val path: String? = currentEntry.mediaPath
                if (!path.isNullOrEmpty()) {
                    val file = File(path)
                    if (file.exists()) {
                        AndroidUtilities.openForView(file, file.name, null, parentActivity, null, false)
                        return@setOnClickListener
                    }
                }

                if (msgObj != null && msgObj.messageOwner?.media != null) {
                    AndroidUtilities.openForView(msgObj, parentActivity, null, false)
                } else if (!TextUtils.isEmpty(currentEntry.text)) {
                    copyTextToClipboard(currentEntry.text)
                }
            }

            setOnLongClickListener {
                showRowMenu(this)
                true
            }
        }

        fun setEditEntry(editEntry: EditEntry, msgObj: MessageObject, position: Int) {
            this.entry = editEntry
            this.entryPosition = position
            setMessageObject(msgObj, null, false, false, false)
        }

        fun currentEntry(): EditEntry? = entry

        fun currentPosition(): Int = entryPosition

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            val currentEntry = entry ?: return
            // entiny: legacy fallback points photo image at local file when revision predates media blob
            if (currentEntry.media != null) return
            val path: String? = currentEntry.mediaPath
            if (!path.isNullOrEmpty()) {
                val file = File(path)
                if (file.exists()) {
                    getPhotoImage()?.setImage(file.absolutePath, null, null, null, 0)
                }
            }
        }

        private fun copyTextToClipboard(text: String) {
            AndroidUtilities.addToClipboard(text)
            BulletinFactory.of(this@AyuMessageHistoryActivity)
                .createCopyBulletin(LocaleController.getString(R.string.MessageCopied))
                .show()
        }
    }

    companion object {
        private const val MENU_MAIN = 1
        private const val MENU_TOGGLE_DIFF = 2

        private fun computeDiff(oldText: String, newText: String): CharSequence {
            if (oldText == newText) return newText
            val oldWords = oldText.split(Regex("(?<=\\s)|(?=\\s)"))
            val newWords = newText.split(Regex("(?<=\\s)|(?=\\s)"))
            val lcs = getLcs(oldWords, newWords)

            val builder = SpannableStringBuilder()
            var i = 0
            var j = 0
            var k = 0

            while (i < oldWords.size || j < newWords.size) {
                if (k < lcs.size && i < oldWords.size && j < newWords.size && oldWords[i] == lcs[k] && newWords[j] == lcs[k]) {
                    builder.append(newWords[j])
                    i++
                    j++
                    k++
                } else {
                    val delStart = builder.length
                    val delBuffer = StringBuilder()
                    while (i < oldWords.size && (k >= lcs.size || oldWords[i] != lcs[k])) {
                        delBuffer.append(oldWords[i])
                        i++
                    }
                    if (delBuffer.isNotEmpty()) {
                        builder.append(delBuffer)
                        val delEnd = builder.length
                        builder.setSpan(ForegroundColorSpan(0xFFE53935.toInt()), delStart, delEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        builder.setSpan(StrikethroughSpan(), delStart, delEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }

                    val insBuffer = StringBuilder()
                    while (j < newWords.size && (k >= lcs.size || newWords[j] != lcs[k])) {
                        insBuffer.append(newWords[j])
                        j++
                    }
                    if (insBuffer.isNotEmpty()) {
                        if (delBuffer.isNotEmpty() && !delBuffer.last().isWhitespace() && !insBuffer.first().isWhitespace()) {
                            builder.append(" ")
                        }
                        val insStart = builder.length
                        builder.append(insBuffer)
                        val insEnd = builder.length
                        builder.setSpan(ForegroundColorSpan(0xFF4CAF50.toInt()), insStart, insEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            return builder
        }

        private fun getLcs(a: List<String>, b: List<String>): List<String> {
            val m = a.size
            val n = b.size
            val dp = Array(m + 1) { IntArray(n + 1) }
            for (i in 0 until m) {
                for (j in 0 until n) {
                    if (a[i] == b[j]) {
                        dp[i + 1][j + 1] = dp[i][j] + 1
                    } else {
                        dp[i + 1][j + 1] = maxOf(dp[i + 1][j], dp[i][j + 1])
                    }
                }
            }
            val result = ArrayList<String>()
            var i = m
            var j = n
            while (i > 0 && j > 0) {
                if (a[i - 1] == b[j - 1]) {
                    result.add(a[i - 1])
                    i--
                    j--
                } else if (dp[i - 1][j] > dp[i][j - 1]) {
                    i--
                } else {
                    j--
                }
            }
            result.reverse()
            return result
        }
    }
}
