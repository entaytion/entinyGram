package desu.inugram.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ActionBar.ThemeDescription
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView

class AyuMessageHistoryActivity(
    private val targetMessageObject: MessageObject
) : BaseFragment() {

    private val historyEntries = ArrayList<EditEntry>()
    private var listView: RecyclerListView? = null

    init {
        loadHistory()
    }

    private fun loadHistory() {
        val dialogId = targetMessageObject.getDialogId()
        val msgId = targetMessageObject.id
        val list = SavedMessagesHelper.getEditHistory(currentAccount, dialogId, msgId)
        historyEntries.clear()
        
        for (entry in list) {
            val text = entry.text.trim()
            val media = entry.mediaPath
            if (text.isEmpty() && media.isNullOrBlank()) continue
            if (historyEntries.isEmpty() || historyEntries.last().text.trim() != text || historyEntries.last().mediaPath != media) {
                historyEntries.add(EditEntry(entry.timestamp, text, media))
            }
        }

        // Include current version if available and not already in history
        val currentText = (targetMessageObject.messageText?.toString() ?: targetMessageObject.messageOwner?.message ?: "").trim()
        val currentDate = if (targetMessageObject.messageOwner?.edit_date != null && targetMessageObject.messageOwner.edit_date != 0) {
            targetMessageObject.messageOwner.edit_date.toLong()
        } else {
            targetMessageObject.messageOwner?.date?.toLong() ?: (System.currentTimeMillis() / 1000)
        }
        val currentMedia = targetMessageObject.messageOwner?.attachPath

        if (currentText.isNotEmpty() || !currentMedia.isNullOrBlank()) {
            val isDuplicateOfLast = historyEntries.isNotEmpty() &&
                historyEntries.last().text.trim() == currentText &&
                historyEntries.last().mediaPath == currentMedia
            val alreadyPresent = historyEntries.any { it.text.trim() == currentText && it.mediaPath == currentMedia }

            if (!isDuplicateOfLast && !alreadyPresent) {
                historyEntries.add(EditEntry(currentDate, currentText, currentMedia))
            }
        }
    }

    override fun createView(context: Context): View {
        val dialogId = targetMessageObject.getDialogId()
        val peer = messagesController.getUserOrChat(dialogId)
        val name = when (peer) {
            is TLRPC.User -> peer.first_name ?: ""
            is TLRPC.Chat -> peer.title ?: ""
            else -> LocaleController.getString("InuEditHistory", R.string.InuEditHistory)
        }

        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(true)
        actionBar.setTitle(name)
        actionBar.setSubtitle("#${targetMessageObject.id}")
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) {
                    finishFragment()
                }
            }
        })

        val frameLayout = FrameLayout(context).apply {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
        }

        val recycler = RecyclerListView(context).apply {
            setItemAnimator(null)
            setLayoutAnimation(null)
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            setVerticalScrollBarEnabled(true)
            adapter = HistoryAdapter(context)
        }
        listView = recycler

        frameLayout.addView(recycler, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        fragmentView = frameLayout
        return frameLayout
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
            val prevEntry = if (position > 0) historyEntries[position - 1] else null
            val syntheticMsg = createMessageObjectForEntry(entry, prevEntry)
            cell.setEditEntry(entry, syntheticMsg)
        }

        private fun createMessageObjectForEntry(entry: EditEntry, prevEntry: EditEntry?): MessageObject {
            val formattedMessage: CharSequence = if (InuConfig.SHOW_EDIT_HISTORY_DIFF.value && prevEntry != null) {
                computeDiff(prevEntry.text, entry.text)
            } else {
                entry.text
            }

            val msg = TLRPC.TL_message().apply {
                id = targetMessageObject.id
                dialog_id = targetMessageObject.getDialogId()
                date = entry.timestamp.toInt()
                message = entry.text
                from_id = targetMessageObject.messageOwner?.from_id
                peer_id = targetMessageObject.messageOwner?.peer_id
                edit_hide = true
            }

            if (targetMessageObject.messageOwner?.media != null) {
                msg.media = targetMessageObject.messageOwner.media
            } else if (!entry.mediaPath.isNullOrBlank()) {
                msg.media = TLRPC.TL_messageMediaPhoto().apply {
                    photo = TLRPC.TL_photo()
                }
            }

            if (!entry.mediaPath.isNullOrBlank()) {
                msg.attachPath = entry.mediaPath
            }

            if (targetMessageObject.replyMessageObject != null) {
                msg.replyMessage = targetMessageObject.replyMessageObject.messageOwner
                msg.reply_to = targetMessageObject.messageOwner?.reply_to
            }

            val msgObj = MessageObject(currentAccount, msg, false, true)
            if (formattedMessage is SpannableStringBuilder) {
                msgObj.messageText = formattedMessage
            }
            return msgObj
        }
    }

    private inner class AyuHistoryMessageCell(context: Context) : ChatMessageCell(context, currentAccount) {
        private var entry: EditEntry? = null

        init {
            setFullyDraw(true)
            isChat = false
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
                val currentEntry = entry ?: return@setOnLongClickListener false
                if (!TextUtils.isEmpty(currentEntry.text)) {
                    copyTextToClipboard(currentEntry.text)
                }
                true
            }
        }

        fun setEditEntry(editEntry: EditEntry, msgObj: MessageObject) {
            this.entry = editEntry
            setMessageObject(msgObj, null, false, false, false)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            val currentEntry = entry ?: return
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
                .createCopyBulletin(LocaleController.getString("MessageCopied", R.string.MessageCopied))
                .show()
        }
    }

    companion object {
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
