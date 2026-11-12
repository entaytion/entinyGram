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
import android.view.View
import android.widget.FrameLayout
import desu.inugram.InuConfig
import desu.inugram.helpers.chat.WidePostLayout
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

@SuppressLint("ViewConstructor")
class WideChannelPostsPreviewCell(context: Context, fragment: BaseFragment) : FrameLayout(context) {

    private val resourcesProvider = fragment.resourceProvider
    private val regularCell = createCell(context, false)
    private val wideCell = createCell(context, true)
    private val shadowDrawable: Drawable = Theme.getThemedDrawable(
        context, R.drawable.greydivider_bottom,
        Theme.getColor(Theme.key_windowBackgroundGrayShadow, resourcesProvider),
    )

    private var backgroundGradientDisposable: BackgroundGradientDrawable.Disposable? = null
    private var animator: ValueAnimator? = null
    private var progress = if (InuConfig.WIDE_CHANNEL_POSTS.value) 1f else 0f
    private var previewWidth = 0

    init {
        setWillNotDraw(false)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        contentDescription = getString(R.string.InuWideChannelPosts)
        addView(regularCell)
        addView(wideCell)
    }

    private fun createCell(context: Context, wide: Boolean): ChatMessageCell {
        val cell = object : ChatMessageCell(context, UserConfig.selectedAccount, false, null, resourcesProvider) {
            override fun getParentWidth(): Int = if (previewWidth > 0) previewWidth else super.getParentWidth()
        }
        cell.setDelegate(object : ChatMessageCell.ChatMessageCellDelegate {
            override fun canPerformActions(): Boolean = false
        })
        cell.isChat = false
        cell.setFullyDraw(true)
        cell.setMessageObject(createMessage(wide), null, false, false, false)
        return cell
    }

    private fun createMessage(wide: Boolean): MessageObject {
        val account = UserConfig.selectedAccount
        val date = (System.currentTimeMillis() / 1000).toInt() - 3600
        val message = TLRPC.TL_message().apply {
            this.date = date
            dialog_id = -1
            flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID or TLRPC.MESSAGE_FLAG_HAS_VIEWS or TLRPC.MESSAGE_FLAG_REPLY
            id = if (wide) 2 else 1
            this.message = getString(R.string.InuWideChannelPostsPreviewText)
            media = TLRPC.TL_messageMediaEmpty()
            reply_to = TLRPC.TL_messageReplyHeader().apply {
                flags = flags or 16
                reply_to_msg_id = 10
            }
            views = 1240
            forwards = 18
            from_id = TLRPC.TL_peerChannel().apply { channel_id = 1 }
            peer_id = TLRPC.TL_peerChannel().apply { channel_id = 1 }
            post = true
        }
        val messageObject = PreviewMessageObject(account, message, wide) { previewWidth }
        messageObject.customReplyName = getString(R.string.InuWideChannelPostsPreviewReplyLabel)
        messageObject.replyMessageObject = createReplyMessage(account, date)
        messageObject.viewsReloaded = true
        messageObject.resetLayout()
        return messageObject
    }

    private fun createReplyMessage(account: Int, date: Int): MessageObject {
        val reply = TLRPC.TL_message().apply {
            this.date = date - 60
            dialog_id = -1
            flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID
            id = 10
            message = getString(R.string.InuWideChannelPostsPreviewReply)
            media = TLRPC.TL_messageMediaEmpty()
            from_id = TLRPC.TL_peerChannel().apply { channel_id = 1 }
            peer_id = TLRPC.TL_peerChannel().apply { channel_id = 1 }
            post = true
        }
        return MessageObject(account, reply, true, false)
    }

    fun refreshInset() {
        WidePostLayout.invalidate()
        wideCell.forceResetMessageObject()
        requestLayout()
        invalidate()
    }

    fun setWide(wide: Boolean) {
        val target = if (wide) 1f else 0f
        animator?.cancel()
        if (!isAttachedToWindow || !SharedConfig.animationsEnabled()) {
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
        if (width > 0 && previewWidth != width) {
            previewWidth = width
            for (cell in arrayOf(regularCell, wideCell)) {
                cell.messageObject?.resetLayout()
                cell.forceResetMessageObject()
            }
        }
        val childWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        regularCell.measure(childWidthSpec, childHeightSpec)
        wideCell.measure(childWidthSpec, childHeightSpec)
        val height = dp(20f) + maxOf(regularCell.measuredHeight, wideCell.measuredHeight)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (cell in arrayOf(regularCell, wideCell)) {
            cell.layout(0, dp(10f), cell.measuredWidth, dp(10f) + cell.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val drawable = Theme.getCachedWallpaperNonBlocking()
        if (drawable == null) {
            canvas.drawColor(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider))
        } else if (drawable is ColorDrawable || drawable is GradientDrawable || drawable is MotionBackgroundDrawable) {
            drawable.alpha = 255
            drawable.setBounds(0, 0, measuredWidth, measuredHeight)
            if (drawable is BackgroundGradientDrawable) {
                backgroundGradientDisposable = drawable.drawExactBoundsSize(canvas, this)
            } else {
                drawable.draw(canvas)
            }
        } else if (drawable is BitmapDrawable) {
            drawable.alpha = 255
            canvas.save()
            if (drawable.tileModeX == Shader.TileMode.REPEAT) {
                val scale = 2f / AndroidUtilities.density
                canvas.scale(scale, scale)
                drawable.setBounds(0, 0, Math.ceil((measuredWidth / scale).toDouble()).toInt(), Math.ceil((measuredHeight / scale).toDouble()).toInt())
            } else {
                val scale = maxOf(measuredWidth / drawable.intrinsicWidth.toFloat(), measuredHeight / drawable.intrinsicHeight.toFloat())
                val w = Math.ceil((drawable.intrinsicWidth * scale).toDouble()).toInt()
                val h = Math.ceil((drawable.intrinsicHeight * scale).toDouble()).toInt()
                val x = (measuredWidth - w) / 2
                val y = (measuredHeight - h) / 2
                canvas.clipRect(0, 0, measuredWidth, measuredHeight)
                drawable.setBounds(x, y, x + w, y + h)
            }
            drawable.draw(canvas)
            canvas.restore()
        }
        shadowDrawable.setBounds(0, 0, measuredWidth, measuredHeight)
        shadowDrawable.draw(canvas)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (progress < 1f) drawChild(canvas, regularCell, drawingTime)
        if (progress <= 0f) return
        if (progress >= 1f) {
            drawChild(canvas, wideCell, drawingTime)
            return
        }
        val reveal = Math.round(width * progress)
        canvas.save()
        if (LocaleController.isRTL) canvas.clipRect(0, 0, reveal, height) else canvas.clipRect(width - reveal, 0, width, height)
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

    private class PreviewMessageObject(
        account: Int,
        message: TLRPC.Message,
        private val wide: Boolean,
        private val width: (() -> Int)?,
    ) : MessageObject(account, message, true, false) {
        override fun inu_isWide(): Boolean = wide

        override fun getParentWidth(): Int {
            val w = width?.invoke() ?: 0
            return if (w > 0) w else super.getParentWidth()
        }
    }
}
