package desu.inugram.helpers.feed

import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC
import java.util.concurrent.ConcurrentHashMap

internal class FeedMessageIdentityMap {

    private val generatedIds = HashMap<MessageCompositeID, Int>()
    private val realIdsByGeneratedId = ConcurrentHashMap<Int, MessageCompositeID>()
    private val messagesByRealId = HashMap<MessageCompositeID, MessageObject>()
    private val primaryByGroup = HashMap<GroupKey, MessageObject>()

    private var lastGeneratedId = FIRST_GENERATED_ID

    data class GroupKey(val dialogId: Long, val groupedId: Long)

    data class MessageCompositeID(val dialogId: Long, val id: Int) {
        constructor(message: TLRPC.Message) : this(MessageObject.getDialogId(message), message.id)
    }

    private fun updatePrimaryGroupFlag(messageObject: MessageObject, dialogId: Long, realId: Int) {
        if (!messageObject.hasValidGroupId()) {
            messageObject.isPrimaryGroupMessage = false
            return
        }
        val groupKey = GroupKey(dialogId, messageObject.messageOwner.grouped_id)
        val previousPrimary = primaryByGroup[groupKey]
        if (previousPrimary != null && realId <= previousPrimary.realId) {
            messageObject.isPrimaryGroupMessage = false
            return
        }
        messageObject.isPrimaryGroupMessage = true
        if (previousPrimary != null) {
            previousPrimary.isPrimaryGroupMessage = false
        }
        primaryByGroup[groupKey] = messageObject
    }

    fun clear() {
        generatedIds.clear()
        realIdsByGeneratedId.clear()
        messagesByRealId.clear()
        primaryByGroup.clear()
        lastGeneratedId = FIRST_GENERATED_ID
    }

    fun getByAnyId(dialogId: Long, id: Int): MessageObject? {
        val byRealId = messagesByRealId[MessageCompositeID(dialogId, id)]
        if (byRealId != null) {
            return byRealId
        }
        val resolvedId = resolveRealMessageId(dialogId, id)
        if (resolvedId != id) {
            return messagesByRealId[MessageCompositeID(dialogId, resolvedId)]
        }
        return null
    }

    fun getByRealId(dialogId: Long, id: Int): MessageObject? =
        messagesByRealId[MessageCompositeID(dialogId, id)]

    fun isEmpty(): Boolean = realIdsByGeneratedId.isEmpty()

    fun purge(messageObject: MessageObject) {
        val compositeId = MessageCompositeID(messageObject.dialogId, messageObject.realId)
        generatedIds.remove(compositeId)
        messagesByRealId.remove(compositeId)
        realIdsByGeneratedId.remove(messageObject.id)
        if (messageObject.hasValidGroupId()) {
            val groupKey = GroupKey(compositeId.dialogId, messageObject.messageOwner.grouped_id)
            if (primaryByGroup[groupKey] === messageObject) {
                primaryByGroup.remove(groupKey)
            }
        }
    }

    fun register(messageObject: MessageObject): Boolean {
        messageObject.reactionsLastCheckTime = Long.MAX_VALUE
        val compositeId = MessageCompositeID(messageObject.messageOwner)
        val realId = messageObject.messageOwner.id
        var generatedId = generatedIds[compositeId]
        if (generatedId == null) {
            generatedId = lastGeneratedId--
            generatedIds[compositeId] = generatedId
        }
        realIdsByGeneratedId[generatedId] = compositeId

        val added: Boolean
        if (messagesByRealId.containsKey(compositeId)) {
            added = false
        } else {
            updatePrimaryGroupFlag(messageObject, compositeId.dialogId, realId)
            messagesByRealId[compositeId] = messageObject
            added = true
        }

        val message = messageObject.messageOwner
        message.realId = realId
        message.id = generatedId
        return added
    }

    fun releaseRow(messageObject: MessageObject) {
        messagesByRealId.remove(MessageCompositeID(messageObject.dialogId, messageObject.realId))
        if (messageObject.hasValidGroupId()) {
            val groupKey = GroupKey(messageObject.dialogId, messageObject.messageOwner.grouped_id)
            if (primaryByGroup[groupKey] === messageObject) {
                primaryByGroup.remove(groupKey)
            }
        }
    }

    fun replace(messageObject: MessageObject) {
        messageObject.reactionsLastCheckTime = Long.MAX_VALUE
        val compositeId = MessageCompositeID(messageObject.dialogId, messageObject.realId)
        generatedIds[compositeId] = messageObject.id
        realIdsByGeneratedId[messageObject.id] = compositeId
        val previous = messagesByRealId.put(compositeId, messageObject)
        if (messageObject.hasValidGroupId()) {
            val groupKey = GroupKey(compositeId.dialogId, messageObject.messageOwner.grouped_id)
            if (previous != null && primaryByGroup[groupKey] === previous) {
                primaryByGroup[groupKey] = messageObject
            }
        }
    }

    fun resolveRealDialogId(generatedId: Int): Long {
        val compositeId = realIdsByGeneratedId[generatedId]
        return compositeId?.dialogId ?: 0L
    }

    fun resolveRealMessageId(dialogId: Long, generatedId: Int): Int {
        val compositeId = realIdsByGeneratedId[generatedId]
        if (compositeId == null || compositeId.dialogId != dialogId) {
            return generatedId
        }
        return compositeId.id
    }

    companion object {
        private const val FIRST_GENERATED_ID = Int.MAX_VALUE - 10
    }
}
