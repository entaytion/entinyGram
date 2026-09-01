package desu.inugram.helpers.chat

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.translate.TranslateHelper
import org.telegram.messenger.ChatObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.ui.Cells.ChatActionCell
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.ChatActivity

enum class DoubleTapContext {
    INCOMING,
    OUTGOING,
    CHANNEL,
}

enum class DoubleTapAction(
    val value: Int,
    private val labelRes: Int,
    private val allowedIn: Set<DoubleTapContext> = DoubleTapContext.entries.toSet(),
) {
    NONE(0, R.string.None),
    QUICK_REACTION(1, R.string.InuQuickReaction),
    SHOW_REACTIONS(2, R.string.InuShowReactions),
    TRANSLATE(3, R.string.TranslateMessage, setOf(DoubleTapContext.INCOMING, DoubleTapContext.OUTGOING, DoubleTapContext.CHANNEL)),
    SAVE(5, R.string.Save),
    EDIT(6, R.string.Edit, setOf(DoubleTapContext.OUTGOING, DoubleTapContext.CHANNEL)),
    DELETE(7, R.string.Delete),
    DETAILS(8, R.string.InuMessageDetails),
    ;

    fun isAllowedIn(context: DoubleTapContext): Boolean = context in allowedIn

    fun label(): CharSequence = LocaleController.getString(labelRes)

    companion object {
        fun fromValue(value: Int, context: DoubleTapContext): DoubleTapAction =
            entries.firstOrNull { it.value == value && it.isAllowedIn(context) } ?: NONE

        fun available(context: DoubleTapContext): List<DoubleTapAction> = entries.filter { it.isAllowedIn(context) }
    }
}

object DoubleTapActionHelper {
    const val INHERIT_INCOMING = -1

    private fun menuOptionForAction(action: DoubleTapAction, message: MessageObject): Int? {
        return when (action) {
            DoubleTapAction.EDIT -> ChatActivity.OPTION_EDIT
            DoubleTapAction.DELETE -> ChatActivity.OPTION_DELETE
            DoubleTapAction.TRANSLATE -> if (TranslateHelper.isManualTranslated(message)) ChatHelper.OPTION_TRANSLATE_REVERT else ChatActivity.OPTION_TRANSLATE
            DoubleTapAction.SAVE -> ChatHelper.OPTION_SAVE
            DoubleTapAction.DETAILS -> ChatHelper.OPTION_DETAILS
            else -> null
        }
    }

    private fun canPerformAction(activity: ChatActivity, message: MessageObject, action: DoubleTapAction): Boolean {
        // Reject actions on special messages
        if (message.isDateObject || message.isSending() || message.isEditing() || message.isSponsored()) {
            return false
        }

        return when (action) {
            DoubleTapAction.EDIT -> message.canEditMessage(activity.currentChat)
            DoubleTapAction.DELETE -> message.canDeleteMessage(activity.isInScheduleMode, activity.currentChat)
            DoubleTapAction.SAVE -> !message.messageOwner.noforwards &&
                message.dialogId != org.telegram.messenger.UserConfig.getInstance(activity.currentAccount).clientUserId
            DoubleTapAction.TRANSLATE, DoubleTapAction.DETAILS -> true
            else -> true
        }
    }

    @JvmStatic
    fun hasDoubleTap(activity: ChatActivity, view: View): Boolean? {
        val message = extractMessage(view) ?: return null
        val action = getAction(activity, message)

        return when (action) {
            // fallback to the default handling
            DoubleTapAction.QUICK_REACTION -> null
            DoubleTapAction.NONE -> false
            DoubleTapAction.SHOW_REACTIONS -> hasReactionMenu(activity, message)
            else -> canPerformAction(activity, message, action)
        }
    }

    @JvmStatic
    fun onDoubleTap(activity: ChatActivity, view: View, x: Float, y: Float): Boolean {
        val message = extractMessage(view) ?: return false
        val action = getAction(activity, message)

        return when (action) {
            DoubleTapAction.QUICK_REACTION -> false
            DoubleTapAction.NONE -> true
            DoubleTapAction.SHOW_REACTIONS -> {
                activity.inu_createMenuExpanded(view, x, y)
                true
            }

            else -> {
                if (!canPerformAction(activity, message, action)) return true
                val option = menuOptionForAction(action, message) ?: return true

                setSelection(activity, message)
                activity.processSelectedOption(option)

                true
            }
        }
    }
    fun getAction(activity: ChatActivity, message: MessageObject): DoubleTapAction {
        val context = getContext(activity, message)
        val value = when (context) {
            DoubleTapContext.OUTGOING -> InuConfig.DOUBLE_TAP_ACTION_OUTGOING.value
            DoubleTapContext.INCOMING -> InuConfig.DOUBLE_TAP_ACTION_INCOMING.value
            DoubleTapContext.CHANNEL -> InuConfig.DOUBLE_TAP_ACTION_CHANNEL.value
                .takeIf { it != INHERIT_INCOMING }
                ?: InuConfig.DOUBLE_TAP_ACTION_INCOMING.value
        }
        return DoubleTapAction.fromValue(value, context)
    }

    private fun getContext(activity: ChatActivity, message: MessageObject): DoubleTapContext {
        if (message.isOutOwner) return DoubleTapContext.OUTGOING
        val chat = activity.currentChat
        if (ChatObject.isChannelAndNotMegaGroup(chat) && ChatObject.canPost(chat)) {
            return DoubleTapContext.CHANNEL
        }
        return DoubleTapContext.INCOMING
    }

    private fun extractMessage(view: View): MessageObject? = when (view) {
        is ChatMessageCell -> view.primaryMessageObject
        is ChatActionCell -> view.messageObject
        else -> null
    }

    private fun hasReactionMenu(activity: ChatActivity, message: MessageObject): Boolean =
        !activity.isSecretChat &&
            !activity.isInScheduleMode &&
            activity.chatMode != ChatActivity.MODE_QUICK_REPLIES &&
            message.isReactionsAvailable

    private fun setSelection(activity: ChatActivity, message: MessageObject) {
        activity.selectedObject = message
        val group = activity.getValidGroupedMessage(message)
        activity.selectedObjectGroup = group
        activity.selectedObjectToEditCaption = group?.let(::findEditCaptionTarget)
    }

    private fun findEditCaptionTarget(group: MessageObject.GroupedMessages): MessageObject? {
        var target: MessageObject? = null
        for ((i, msg) in group.messages.withIndex()) {
            if (i == 0 || !msg.caption.isNullOrEmpty()) {
                target = msg
            }
        }
        return target
    }
}
