package desu.inugram.helpers.restrictions

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.telegram.messenger.FileRefController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC

// entiny: sends copies by server reference (no download, no re-upload) and leaves the rest to the download path, idea from NagramXTurbo (@temporaryna)
internal object RestrictedReferenceSender {
    private const val TAG = "InuRefForward"
    private const val REQUEST_TIMEOUT_MS = 60_000L
    private const val REFRESH_TIMEOUT_MS = 30_000L
    private const val REJECT_TTL_MS = 10 * 60 * 1000L
    private const val MAX_ALBUM = 10

    private val rejectedSources = ConcurrentHashMap<String, Long>()
    private val failedMessages = ConcurrentHashMap.newKeySet<String>()

    private class Outcome(val sent: Boolean, val errorText: String?)

    /** sends leading units by reference and returns the messages that still need the download path */
    fun sendLeading(
        account: Int,
        batch: List<MessageObject>,
        did: Long,
        notify: Boolean,
        scheduleDate: Int,
        scheduleRepeatPeriod: Int,
        threadMsg: MessageObject?,
        mono: Long,
        hideCaption: Boolean,
    ): List<MessageObject> {
        if (scheduleRepeatPeriod != 0) return batch
        val peer = MessagesController.getInstance(account).getInputPeer(did) ?: return batch
        val units = splitUnits(batch)
        for ((index, unit) in units.withIndex()) {
            // entiny: one unit falling back sends the rest through the download path too, so the order stays
            if (!isEligible(account, unit) || !sendUnit(account, unit, peer, notify, scheduleDate, threadMsg, mono, hideCaption)) {
                return units.drop(index).flatten()
            }
        }
        return emptyList()
    }

    private fun splitUnits(batch: List<MessageObject>): List<List<MessageObject>> {
        val units = ArrayList<List<MessageObject>>()
        var index = 0
        while (index < batch.size) {
            val groupId = batch[index].groupId
            var end = index + 1
            if (groupId != 0L) {
                while (end < batch.size && batch[end].groupId == groupId && end - index < MAX_ALBUM) end++
            }
            units.add(batch.subList(index, end))
            index = end
        }
        return units
    }

    private fun sourceKey(account: Int, message: MessageObject) = "$account:${message.dialogId}"

    private fun messageKey(account: Int, message: MessageObject) = "$account:${message.dialogId}:${message.id}"

    private fun isEligible(account: Int, unit: List<MessageObject>): Boolean {
        val now = SystemClock.uptimeMillis()
        return unit.all { msg ->
            val media = msg.messageOwner?.media
            val rejectedAt = rejectedSources[sourceKey(account, msg)]
            (rejectedAt == null || now - rejectedAt > REJECT_TTL_MS) &&
                !failedMessages.contains(messageKey(account, msg)) &&
                !msg.isAnyKindOfSticker && !msg.isVoiceOnce && !msg.isRoundOnce &&
                media != null && media.ttl_seconds == 0 && referenceInput(msg) != null
        }
    }

    private fun referenceInput(message: MessageObject): TLRPC.InputMedia? {
        val media = message.messageOwner?.media ?: return null
        val photo = media.photo as? TLRPC.TL_photo
        if (photo != null) {
            if (photo.id == 0L || photo.access_hash == 0L || photo.file_reference == null || photo.file_reference.isEmpty()) return null
            return TLRPC.TL_inputMediaPhoto().apply {
                id = TLRPC.TL_inputPhoto().also {
                    it.id = photo.id
                    it.access_hash = photo.access_hash
                    it.file_reference = photo.file_reference
                }
                spoiler = message.hasMediaSpoilers()
            }
        }
        val document = media.document as? TLRPC.TL_document ?: return null
        if (document.id == 0L || document.access_hash == 0L || document.file_reference == null || document.file_reference.isEmpty()) return null
        return TLRPC.TL_inputMediaDocument().apply {
            id = TLRPC.TL_inputDocument().also {
                it.id = document.id
                it.access_hash = document.access_hash
                it.file_reference = document.file_reference
            }
            spoiler = message.hasMediaSpoilers()
        }
    }

    private fun inputOf(media: TLRPC.InputMedia): TLObject? = when (media) {
        is TLRPC.TL_inputMediaPhoto -> media.id
        is TLRPC.TL_inputMediaDocument -> media.id
        else -> null
    }

    private fun replyTo(account: Int, threadMsg: MessageObject?, mono: Long): TLRPC.InputReplyTo? {
        val controller = MessagesController.getInstance(account)
        if (mono != 0L) {
            val monoPeer = controller.getInputPeer(mono)
            if (threadMsg != null) {
                return TLRPC.TL_inputReplyToMessage().apply {
                    reply_to_msg_id = threadMsg.id
                    monoforum_peer_id = monoPeer
                    flags = flags or 32
                }
            }
            return TLRPC.TL_inputReplyToMonoForum().apply { monoforum_peer_id = monoPeer }
        }
        return threadMsg?.let { SendMessagesHelper.getInstance(account).createReplyInput(it.id) }
    }

    private fun sendUnit(
        account: Int,
        unit: List<MessageObject>,
        peer: TLRPC.InputPeer,
        notify: Boolean,
        scheduleDate: Int,
        threadMsg: MessageObject?,
        mono: Long,
        hideCaption: Boolean,
    ): Boolean {
        val inputs = unit.mapNotNull { referenceInput(it) }
        if (inputs.size != unit.size) return false
        val helper = SendMessagesHelper.getInstance(account)
        val invert = unit[0].messageOwner?.invert_media == true
        val reply = replyTo(account, threadMsg, mono)
        val request: TLObject = if (unit.size == 1) {
            TLRPC.TL_messages_sendMedia().also {
                it.peer = peer
                it.media = inputs[0]
                it.random_id = helper.nextRandomId
                it.silent = !notify
                it.invert_media = invert
                it.message = if (hideCaption) "" else unit[0].messageOwner?.message.orEmpty()
                val entities = unit[0].messageOwner?.entities
                if (!hideCaption && !entities.isNullOrEmpty()) {
                    it.entities = entities
                    it.flags = it.flags or 8
                }
                if (reply != null) {
                    it.reply_to = reply
                    it.flags = it.flags or 1
                }
                if (scheduleDate != 0) {
                    it.schedule_date = scheduleDate
                    it.flags = it.flags or 1024
                }
            }
        } else {
            TLRPC.TL_messages_sendMultiMedia().also {
                it.peer = peer
                it.silent = !notify
                it.invert_media = invert
                unit.forEachIndexed { i, msg ->
                    it.multi_media.add(
                        TLRPC.TL_inputSingleMedia().also { single ->
                            single.media = inputs[i]
                            single.random_id = helper.nextRandomId
                            single.message = if (hideCaption) "" else msg.messageOwner?.message.orEmpty()
                            val entities = msg.messageOwner?.entities
                            if (!hideCaption && !entities.isNullOrEmpty()) {
                                single.entities = entities
                                single.flags = single.flags or 1
                            }
                        }
                    )
                }
                if (reply != null) {
                    it.reply_to = reply
                    it.flags = it.flags or 1
                }
                if (scheduleDate != 0) {
                    it.schedule_date = scheduleDate
                    it.flags = it.flags or 1024
                }
            }
        }

        var outcome = send(account, request)
        if (!outcome.sent && outcome.errorText != null && FileRefController.isFileRefError(outcome.errorText)) {
            if (refreshReferences(account, unit, inputs)) outcome = send(account, request)
        }
        if (outcome.sent) return true
        Log.d(TAG, "reference send refused: ${outcome.errorText}")
        if (outcome.errorText != null && FileRefController.isFileRefError(outcome.errorText)) {
            unit.forEach { failedMessages.add(messageKey(account, it)) }
        } else {
            // entiny: any other refusal usually means the whole source chat is protected, so stop trying it for a while
            rejectedSources[sourceKey(account, unit[0])] = SystemClock.uptimeMillis()
        }
        return false
    }

    private fun send(account: Int, request: TLObject): Outcome {
        val latch = CountDownLatch(1)
        var sent = false
        var errorText: String? = null
        ConnectionsManager.getInstance(account).sendRequest(request) { response, error ->
            if (error == null && response is TLRPC.Updates) {
                MessagesController.getInstance(account).processUpdates(response, false)
                sent = true
            } else {
                errorText = error?.text ?: "NO_RESPONSE"
            }
            latch.countDown()
        }
        if (!latch.await(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return Outcome(false, "TIMEOUT")
        return Outcome(sent, errorText)
    }

    private fun refreshReferences(account: Int, unit: List<MessageObject>, inputs: List<TLRPC.InputMedia>): Boolean {
        val latch = CountDownLatch(inputs.size)
        inputs.forEachIndexed { i, media ->
            val input = inputOf(media)
            if (input == null) {
                latch.countDown()
            } else {
                FileRefController.getInstance(account).requestReference(unit[minOf(i, unit.size - 1)], input, Runnable { latch.countDown() })
            }
        }
        return latch.await(REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }
}
