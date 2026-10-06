package desu.inugram.helpers.feed

import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

object FeedRequestNormalizer {

    private const val TL_PACKAGE_PREFIX = "org.telegram.tgnet."

    private val EMPTY_FIELDS = arrayOf<Field>()
    private val EMPTY_METADATA = ClassMetadata(null, null, null, null, EMPTY_FIELDS)

    private val metadataCache = ConcurrentHashMap<Class<*>, ClassMetadata>()

    private class ClassMetadata(
        val requestPeerField: Field?,
        val peerField: Field?,
        val channelField: Field?,
        val invoiceField: Field?,
        val messageIdFields: Array<Field>,
    )

    private fun buildMetadata(requestClass: Class<*>): ClassMetadata {
        val fields: Array<Field> = try {
            requestClass.fields
        } catch (ignored: Exception) {
            EMPTY_FIELDS
        }
        var fromPeerField: Field? = null
        var peerField: Field? = null
        var channelField: Field? = null
        var invoiceField: Field? = null
        var messageIdFields: ArrayList<Field>? = null
        for (field in fields) {
            val name = field.name
            if ("from_peer" == name && fromPeerField == null) {
                fromPeerField = field
            } else if ("peer" == name && peerField == null) {
                peerField = field
            } else if ("channel" == name && channelField == null) {
                channelField = field
            } else if ("invoice" == name && invoiceField == null) {
                invoiceField = field
            }
            if (isMessageIdField(field)) {
                if (messageIdFields == null) {
                    messageIdFields = ArrayList()
                }
                messageIdFields.add(field)
            }
        }
        return ClassMetadata(
            fromPeerField ?: peerField,
            peerField,
            channelField,
            invoiceField,
            messageIdFields?.toTypedArray() ?: EMPTY_FIELDS
        )
    }

    private fun getMetadata(request: Any?): ClassMetadata {
        if (request == null) {
            return EMPTY_METADATA
        }
        return metadataCache.computeIfAbsent(request.javaClass) { buildMetadata(it) }
    }

    private fun isMessageIdField(field: Field?): Boolean {
        if (field == null || Modifier.isStatic(field.modifiers)) {
            return false
        }
        val name = field.name
        return "id" == name || "msg_id" == name || name.endsWith("_msg_id")
    }

    private fun getFieldValue(field: Field?, request: Any?): Any? {
        if (field == null) {
            return null
        }
        return try {
            field.get(request)
        } catch (ignored: Exception) {
            null
        }
    }

    private fun getDialogId(field: Field?, request: Any?): Long {
        if (field == null) {
            return 0L
        }
        try {
            val value = field.get(request)
            if (value is TLRPC.InputPeer) {
                return DialogObject.getPeerDialogId(value)
            }
        } catch (ignored: Exception) {
        }
        return 0L
    }

    private fun getChannelDialogId(field: Field?, request: Any?): Long {
        val value = getFieldValue(field, request)
        if (value is TLRPC.InputChannel) {
            return getInputChannelDialogId(value)
        }
        return 0L
    }

    private fun getInputChannelDialogId(inputChannel: TLRPC.InputChannel?): Long {
        if (inputChannel == null || inputChannel.channel_id == 0L) {
            return 0L
        }
        return -inputChannel.channel_id
    }

    private fun mergeResolvedDialogIds(current: Long, resolved: Long): Long {
        if (current == 0L) {
            return resolved
        }
        if (resolved == 0L || current == resolved) {
            return current
        }
        return 0L
    }

    @JvmStatic
    fun normalize(currentAccount: Int, request: TLObject?): TLObject? {
        if (request == null) {
            return request
        }
        val feedController = FeedController.peekInstance(currentAccount)
        if (feedController == null || feedController.hasNoSyntheticIds()) {
            return request
        }
        if (!request.javaClass.name.startsWith(TL_PACKAGE_PREFIX)) {
            return request
        }
        val metadata = getMetadata(request)
        if (metadata.messageIdFields.isNotEmpty() || metadata.invoiceField != null) {
            normalizeMessageIds(currentAccount, feedController, request, metadata)
            normalizeInvoice(currentAccount, feedController, getFieldValue(metadata.invoiceField, request))
        }
        return request
    }

    private fun normalizeInvoice(currentAccount: Int, feedController: FeedController, invoice: Any?) {
        if (invoice is TLRPC.TL_inputInvoiceMessage) {
            normalizeMessageIds(currentAccount, feedController, invoice)
        }
    }

    private fun normalizeMessageIds(currentAccount: Int, feedController: FeedController, request: Any?) {
        normalizeMessageIds(currentAccount, feedController, request, getMetadata(request))
    }

    private fun normalizeMessageIds(currentAccount: Int, feedController: FeedController, request: Any?, metadata: ClassMetadata) {
        val requestPeerField = metadata.requestPeerField
        var requestDialogId = getDialogId(requestPeerField, request)
        if (requestDialogId == 0L) {
            requestDialogId = getDialogId(metadata.peerField, request)
        }
        if (requestDialogId == 0L) {
            requestDialogId = getChannelDialogId(metadata.channelField, request)
        }
        val resolvedDialogId = normalizeMessageIdFields(feedController, request, metadata)
        if (resolvedDialogId == 0L || resolvedDialogId == requestDialogId) {
            return
        }
        if (requestPeerField != null) {
            setInputPeer(currentAccount, requestPeerField, request, resolvedDialogId)
        } else if (metadata.channelField != null) {
            setInputChannel(currentAccount, metadata.channelField, request, resolvedDialogId)
        }
    }

    private fun normalizeMessageIdFields(feedController: FeedController, request: Any?, metadata: ClassMetadata): Long {
        if (request == null) {
            return 0L
        }
        var resolvedDialogId = 0L
        for (field in metadata.messageIdFields) {
            resolvedDialogId = mergeResolvedDialogIds(resolvedDialogId, normalizeMessageIdField(feedController, request, field))
        }
        return resolvedDialogId
    }

    private fun normalizeMessageIdField(feedController: FeedController, request: Any, field: Field): Long {
        try {
            val value = field.get(request)
            if (value is Int) {
                val dialogId = feedController.resolveRealDialogId(value)
                if (dialogId == 0L) {
                    return 0L
                }
                field.setInt(request, feedController.resolveRealMessageId(dialogId, value))
                return dialogId
            }
            if (value !is ArrayList<*>) {
                return 0L
            }
            var resolvedDialogId = 0L
            for (i in value.indices) {
                try {
                    val id = value[i] as? Int ?: continue
                    val dialogId = feedController.resolveRealDialogId(id)
                    if (dialogId != 0L) {
                        setListInteger(value, i, feedController.resolveRealMessageId(dialogId, id))
                        resolvedDialogId = mergeResolvedDialogIds(resolvedDialogId, dialogId)
                    }
                } catch (ignored: Exception) {
                    return resolvedDialogId
                }
            }
            return resolvedDialogId
        } catch (ignored: Exception) {
            return 0L
        }
    }

    private fun setInputChannel(currentAccount: Int, field: Field, request: Any?, dialogId: Long) {
        if (currentAccount < 0 || dialogId >= 0) {
            return
        }
        try {
            val inputChannel = MessagesController.getInstance(currentAccount).getInputChannel(-dialogId)
            if (inputChannel != null) {
                field.set(request, inputChannel)
            }
        } catch (ignored: Exception) {
        }
    }

    private fun setInputPeer(currentAccount: Int, field: Field, request: Any?, dialogId: Long) {
        if (currentAccount < 0) {
            return
        }
        try {
            val inputPeer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId)
            if (inputPeer != null) {
                field.set(request, inputPeer)
            }
        } catch (ignored: Exception) {
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setListInteger(list: ArrayList<*>, index: Int, value: Int) {
        (list as ArrayList<Int>)[index] = value
    }
}
