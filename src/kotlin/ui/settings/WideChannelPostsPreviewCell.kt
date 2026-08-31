package desu.inugram.ui.settings

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.Components.BackgroundGradientDrawable
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.MotionBackgroundDrawable

/**
 * Live before/after preview for InuConfig.WIDE_CHANNEL_POSTS -- ported from exteraless
 * (https://github.com/exteraless/exteraless), app/exteraless/chats/WideChannelPostsPreviewCell.java.
 */
@SuppressLint("ViewConstructor")
class WideChannelPostsPreviewCell(context: Context, fragment: BaseFragment) : FrameLayout(context) {

    companion object {
        private const val HORIZONTAL_PADDING_DP = 12
        private const val VERTICAL_PADDING_DP = 10
    }

    private val resourcesProvider = fragment.resourceProvider
    private val regularCell: ChatMessageCell
    private val wideCell: ChatMessageCell
    private val shadowDrawable: Drawable

    private var backgroundGradientDisposable: BackgroundGradientDrawable.Disposable? = null
    private var animator: ValueAnimator? = null
    private var progress: Float
    private var previewContentWidth = 0

    init {
        setWillNotDraw(false)
        clipChildren = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        contentDescription = getString(R.string.InuWideChannelPosts)
        isFocusable = false
        isClickable = false

        shadowDrawable = Theme.getThemedDrawable(
            context, R.drawable.greydivider_bottom,
            Theme.getColor(Theme.key_windowBackgroundGrayShadow, resourcesProvider),
        )

        regularCell = createCell(context, createMessage(false))
        wideCell = createCell(context, createMessage(true))
        addView(regularCell)
        addView(wideCell)

        progress = if (InuConfig.WIDE_CHANNEL_POSTS.value) 1f else 0f
    }

    private fun createCell(context: Context, messageObject: MessageObject): ChatMessageCell {
        val cell = object : ChatMessageCell(context, UserConfig.selectedAccount, false, null, resourcesProvider) {
            override fun getParentWidth(): Int {
                val width = previewContentWidth
                return if (width > 0) width else super.getParentWidth()
            }
        }
        cell.setDelegate(object : ChatMessageCell.ChatMessageCellDelegate {
            override fun canPerformActions(): Boolean = false
        })
        cell.isChat = false
        cell.hasDiscussion = true
        cell.linkedChatId = 2
        cell.setFullyDraw(true)
        cell.setMessageObject(messageObject, null, false, false, false)
        return cell
    }

    private fun createMessage(wide: Boolean): MessageObject {
        val account = UserConfig.selectedAccount
        val date = (System.currentTimeMillis() / 1000).toInt() - 3600

        val message = TLRPC.TL_message()
        message.date = date
        message.dialog_id = -1
        message.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID or TLRPC.MESSAGE_FLAG_HAS_VIEWS or TLRPC.MESSAGE_FLAG_REPLY
        message.id = if (wide) 2 else 1
        message.message = getString(R.string.InuWideChannelPostsPreviewText)
        message.media = TLRPC.TL_messageMediaEmpty()
        message.reply_to = TLRPC.TL_messageReplyHeader()
        message.reply_to.flags = message.reply_to.flags or 16
        message.reply_to.reply_to_msg_id = 10
        message.views = 1240
        message.forwards = 18
        message.replies = TLRPC.TL_messageReplies()
        message.replies.comments = true
        message.replies.channel_id = 2
        message.replies.replies = 3

        message.from_id = TLRPC.TL_peerChannel()
        message.from_id.channel_id = 1
        message.peer_id = TLRPC.TL_peerChannel()
        message.peer_id.channel_id = 1
        message.out = false
        message.post = true

        val messageObject = PreviewMessageObject(account, message, wide)
        messageObject.customReplyName = getString(R.string.InuWideChannelPostsPreviewReplyLabel)
        messageObject.replyMessageObject = createReplyMessage(account, date)
        messageObject.viewsReloaded = true
        messageObject.resetLayout()
        return messageObject
    }

    private fun createReplyMessage(account: Int, date: Int): MessageObject {
        val reply = TLRPC.TL_message()
        reply.date = date - 60
        reply.dialog_id = -1
        reply.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID
        reply.id = 10
        reply.message = getString(R.string.InuWideChannelPostsPreviewReply)
        reply.media = TLRPC.TL_messageMediaEmpty()
        reply.from_id = TLRPC.TL_peerChannel()
        reply.from_id.channel_id = 1
        reply.peer_id = TLRPC.TL_peerChannel()
        reply.peer_id.channel_id = 1
        reply.post = true
        return MessageObject(account, reply, true, false)
    }

    fun setWide(wide: Boolean, animated: Boolean) {
        val target = if (wide) 1f else 0f
        animator?.cancel()
        animator = null
        val canAnimate = animated && isAttachedToWindow && width > 0 && SharedConfig.animationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled())
        if (!canAnimate || Math.abs(progress - target) < 0.001f) {
            progress = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 280
            interpolator = CubicBezierInterpolator.EASE_OUT_QUINT
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val contentWidth = maxOf(dp(1f), width - dp((HORIZONTAL_PADDING_DP * 2).toFloat()))
        if (previewContentWidth != contentWidth) {
            previewContentWidth = contentWidth
            regularCell.forceResetMessageObject()
            wideCell.forceResetMessageObject()
        }
        val childWidthSpec = MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        regularCell.measure(childWidthSpec, childHeightSpec)
        wideCell.measure(childWidthSpec, childHeightSpec)

        val height = dp((VERTICAL_PADDING_DP * 2).toFloat()) + maxOf(regularCell.measuredHeight, wideCell.measuredHeight)
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        layoutCell(regularCell, width)
        layoutCell(wideCell, width)
    }

    private fun layoutCell(cell: ChatMessageCell, width: Int) {
        val left = if (LocaleController.isRTL) width - dp(HORIZONTAL_PADDING_DP.toFloat()) - cell.measuredWidth else dp(HORIZONTAL_PADDING_DP.toFloat())
        val top = dp(VERTICAL_PADDING_DP.toFloat())
        cell.layout(left, top, left + cell.measuredWidth, top + cell.measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val drawable = Theme.getCachedWallpaperNonBlocking()
        if (drawable == null) {
            canvas.drawColor(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider))
        } else {
            drawable.alpha = 255
            if (drawable is ColorDrawable || drawable is GradientDrawable || drawable is MotionBackgroundDrawable) {
                drawable.setBounds(0, 0, measuredWidth, measuredHeight)
                if (drawable is BackgroundGradientDrawable) {
                    backgroundGradientDisposable = drawable.drawExactBoundsSize(canvas, this)
                } else {
                    drawable.draw(canvas)
                }
            } else if (drawable is BitmapDrawable) {
                if (drawable.tileModeX == Shader.TileMode.REPEAT) {
                    canvas.save()
                    val scale = 2f / AndroidUtilities.density
                    canvas.scale(scale, scale)
                    drawable.setBounds(0, 0, Math.ceil((measuredWidth / scale).toDouble()).toInt(), Math.ceil((measuredHeight / scale).toDouble()).toInt())
                } else {
                    val scale = maxOf(measuredWidth / drawable.intrinsicWidth.toFloat(), measuredHeight / drawable.intrinsicHeight.toFloat())
                    val w = Math.ceil((drawable.intrinsicWidth * scale).toDouble()).toInt()
                    val h = Math.ceil((drawable.intrinsicHeight * scale).toDouble()).toInt()
                    val x = (measuredWidth - w) / 2
                    val y = (measuredHeight - h) / 2
                    canvas.save()
                    canvas.clipRect(0, 0, measuredWidth, measuredHeight)
                    drawable.setBounds(x, y, x + w, y + h)
                }
                drawable.draw(canvas)
                canvas.restore()
            }
        }
        shadowDrawable.setBounds(0, 0, measuredWidth, measuredHeight)
        shadowDrawable.draw(canvas)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val drawingTime = drawingTime
        if (progress < 1f) {
            drawChild(canvas, regularCell, drawingTime)
        }
        if (progress <= 0f) return
        if (progress >= 1f) {
            drawChild(canvas, wideCell, drawingTime)
            return
        }
        val revealWidth = Math.round(width * progress)
        canvas.save()
        if (LocaleController.isRTL) {
            canvas.clipRect(0, 0, revealWidth, height)
        } else {
            canvas.clipRect(width - revealWidth, 0, width, height)
        }
        drawChild(canvas, wideCell, drawingTime)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        backgroundGradientDisposable?.dispose()
        backgroundGradientDisposable = null
        super.onDetachedFromWindow()
    }

    private class PreviewMessageObject(account: Int, message: TLRPC.Message, private val wide: Boolean) :
        MessageObject(account, message, true, false) {
        override fun isWideChannelPost(): Boolean = wide
    }
}
