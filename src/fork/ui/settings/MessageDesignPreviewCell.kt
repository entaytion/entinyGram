package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.helpers.chat.ChatHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.LayoutHelper
import java.util.Calendar

@SuppressLint("ViewConstructor")
class MessageDesignPreviewCell(context: Context, fragment: BaseFragment) :
    MessagesPreviewCell(context, fragment, buildMessages(fragment.resourceProvider), isGroupPreview = true) {

    private val input = TextView(context).apply {
        text = getString(R.string.TypeMessage)
        setTextColor(Theme.getColor(Theme.key_chat_messagePanelHint, fragment.resourceProvider))
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    init {
        val provider = fragment.resourceProvider
        val stickerSender = TextView(context).apply {
            text = getString(R.string.InuMessageDesignStickerSender)
            textSize = 12f
            typeface = AndroidUtilities.bold()
            setTextColor(Theme.getColor(Theme.key_chat_messageTextIn, provider))
            background = Theme.createRoundRectDrawable(
                dp(8f), Theme.getColor(Theme.key_chat_inBubble, provider)
            )
            setPadding(dp(8f), dp(1f), dp(8f), dp(1f))
        }
        addView(
            stickerSender, 2,
            LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 52f, 4f, 0f, 0f
            )
        )
        val composer = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = if (LocaleController.isRTL) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            minimumHeight = dp(44f)
            setPaddingRelative(dp(14f), dp(5f), dp(12f), dp(5f))
            setBackgroundColor(Theme.getColor(Theme.key_chat_messagePanelBackground, provider))
            addView(input, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.input_mic)
                setColorFilter(Theme.getColor(Theme.key_chat_messagePanelIcons, provider))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutHelper.createLinear(28, 28))
        }
        setPadding(0, dp(8f), 0, 0)
        addView(composer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        updateInputTextSize()
    }

    fun updateInputTextSize() {
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, ChatHelper.getChatInputTextSize())
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawMessageAvatars(canvas)
    }

    companion object {
        private fun buildMessages(provider: Theme.ResourcesProvider?): Array<MessageObject> {
            val account = UserConfig.selectedAccount
            val selfId = UserConfig.getInstance(account).clientUserId
            val start = (System.currentTimeMillis() / 1000).toInt() - 180
            val group = TLRPC.TL_peerChat().apply { chat_id = Long.MAX_VALUE }
            val forwarder = getString(R.string.InuMessageDesignForwardSender)
            val stickerSender = getString(R.string.InuMessageDesignStickerSender)

            val forwardedTlMessage = TLRPC.TL_message().apply {
                message = getString(R.string.InuMiscPreviewForwardedMessage)
                date = start
                flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID or TLRPC.MESSAGE_FLAG_FWD
                id = 1
                forwards = 12
                from_id = TLRPC.TL_peerUser().apply { user_id = -1 }
                fwd_from = TLRPC.TL_messageFwdHeader().apply {
                    flags = flags or 32
                    from_name = getString(R.string.InuMiscPreviewForwardedFrom)
                    date = Calendar.getInstance().apply {
                        add(Calendar.DAY_OF_YEAR, -1)
                        set(Calendar.HOUR_OF_DAY, 19)
                        set(Calendar.MINUTE, 42)
                        set(Calendar.SECOND, 0)
                    }.let { (it.timeInMillis / 1000).toInt() }
                }
                media = TLRPC.TL_messageMediaEmpty()
                peer_id = group
            }

            val editedTlMessage = TLRPC.TL_message().apply {
                message = getString(R.string.InuMiscPreviewEditedMessage)
                date = start + 60
                flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID or TLRPC.MESSAGE_FLAG_EDITED
                id = 2
                edit_date = start + 120
                from_id = TLRPC.TL_peerUser().apply { user_id = selfId }
                media = TLRPC.TL_messageMediaEmpty()
                out = true
                peer_id = group
            }

            val stickerTlMessage = TLRPC.TL_message().apply {
                message = ""
                date = start + 120
                flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID or TLRPC.MESSAGE_FLAG_HAS_MEDIA
                id = 3
                from_id = TLRPC.TL_peerUser().apply { user_id = -2 }
                peer_id = group
                media = TLRPC.TL_messageMediaDocument().apply {
                    flags = 1
                    document = TLRPC.TL_document().apply {
                        mime_type = "image/webp"
                        file_reference = ByteArray(0)
                        date = start
                        attributes.add(TLRPC.TL_documentAttributeSticker().apply { alt = "💥🐗" })
                        attributes.add(TLRPC.TL_documentAttributeImageSize().apply {
                            h = 512
                            w = 512
                        })
                    }
                }
            }

            return arrayOf(
                MessageObject(account, forwardedTlMessage, true, false).apply {
                    customName = forwarder
                    customAvatarDrawable = AvatarDrawable(provider).apply { setInfo(1L, forwarder, null) }
                },
                MessageObject(account, editedTlMessage, true, false),
                MessageObject(account, stickerTlMessage, true, false).apply {
                    useCustomPhoto = true
                    customAvatarDrawable = AvatarDrawable(provider).apply { setInfo(2L, stickerSender, null) }
                },
            )
        }
    }
}
