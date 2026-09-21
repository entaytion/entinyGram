package desu.inugram.helpers.chat

import desu.inugram.InuConfig
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC

object PollResultsHelper {

    @JvmStatic
    fun canShowBeforeVote(messageObject: MessageObject?): Boolean {
        if (!InuConfig.SHOW_POLL_RESULTS_BEFORE_VOTE.value) return false
        if (messageObject == null || messageObject.isVoted) return false
        val media = MessageObject.getMedia(messageObject.messageOwner) as? TLRPC.TL_messageMediaPoll ?: return false
        val poll = media.poll ?: return false
        if (poll.closed || poll.quiz) return false
        if (poll.hide_results_until_close && !poll.creator) return false
        return messageObject.hasVoteResults()
    }
}
