package desu.inugram.helpers.chat

import androidx.recyclerview.widget.RecyclerView
import desu.inugram.InuConfig
import org.telegram.ui.ChatActivity

object PinnedReturnHelper {
    // entiny: the id goes to scrollToMessageId as the message the down button returns to; 0 means stock behavior
    @JvmStatic
    fun returnId(chat: ChatActivity): Int {
        if (!InuConfig.PINNED_JUMP_RETURN.value) return 0
        val manager = chat.chatLayoutManager ?: return 0
        val adapter = chat.chatAdapter ?: return 0
        if (adapter.isFiltered || chat.isSecretChat) return 0
        val first = manager.findFirstVisibleItemPosition()
        val last = manager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return 0
        if (chat.forwardEndReached[0] && first <= adapter.messagesStartRow) return 0
        val messages = adapter.messages
        for (i in first..last) {
            if (i < adapter.messagesStartRow || i >= adapter.messagesEndRow) continue
            val index = i - adapter.messagesStartRow
            if (index >= messages.size) break
            val message = messages[index]
            if (message.id > 0 && !message.isSponsored && message.dialogId == chat.dialogId) return message.id
        }
        return 0
    }
}
