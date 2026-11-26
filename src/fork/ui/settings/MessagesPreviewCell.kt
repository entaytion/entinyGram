package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.Components.BackgroundGradientDrawable
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.MotionBackgroundDrawable
import kotlin.math.ceil
import kotlin.math.max

@SuppressLint("ViewConstructor")
open class MessagesPreviewCell(
    context: Context?,
    fragment: BaseFragment,
    private val messageObjects: Array<MessageObject>,
    private val isGroupPreview: Boolean = false,
) : LinearLayout(context) {
    private var backgroundGradientDisposable: BackgroundGradientDrawable.Disposable? = null

    private val cells: Array<ChatMessageCell>
    private val shadowDrawable: Drawable
    private var previewWidth = 0

    init {
        val resourcesProvider = fragment.getResourceProvider()

        setWillNotDraw(false)
        orientation = VERTICAL
        setPadding(0, AndroidUtilities.dp(11f), 0, AndroidUtilities.dp(11f))

        shadowDrawable = Theme.getThemedDrawable(
            context,
            R.drawable.greydivider_bottom,
            Theme.getColor(Theme.key_windowBackgroundGrayShadow, resourcesProvider)
        )

        cells = Array(messageObjects.size) { i ->
            val cell = if (isGroupPreview) {
                object : ChatMessageCell(context, org.telegram.messenger.UserConfig.selectedAccount, false, null, resourcesProvider) {
                    override fun getParentWidth(): Int = if (previewWidth > 0) previewWidth else super.getParentWidth()
                }
            } else {
                ChatMessageCell(context, org.telegram.messenger.UserConfig.selectedAccount, false, null, resourcesProvider)
            }
            cell.apply {
                delegate = object : ChatMessageCell.ChatMessageCellDelegate {
                    override fun canPerformActions() = true

                    override fun didPressImage(cell: ChatMessageCell?, x: Float, y: Float, fullPreview: Boolean) {
                        BulletinFactory.of(fragment)
                            .createErrorBulletin(getString(R.string.InuWoof), resourcesProvider)
                            .show()
                    }
                }
                isChat = isGroupPreview
                setFullyDraw(true)
                setMessageObject(messageObjects[i], null, false, false, false)
            }.also {
                addView(it, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (isGroupPreview && width > 0 && width != previewWidth) {
            previewWidth = width
            cells.forEach { it.forceResetMessageObject() }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun invalidate() {
        super.invalidate()
        cells.forEachIndexed { i, cell ->
            messageObjects[i].forceUpdate = true
            cell.setMessageObject(messageObjects[i], null, false, false, false)
            cell.invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val drawable = Theme.getCachedWallpaperNonBlocking() ?: return
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
                val scale = 2.0f / AndroidUtilities.density
                canvas.scale(scale, scale)
                drawable.setBounds(
                    0,
                    0,
                    ceil((measuredWidth / scale).toDouble()).toInt(),
                    ceil((measuredHeight / scale).toDouble()).toInt()
                )
            } else {
                val viewHeight = measuredHeight
                val scaleX = measuredWidth.toFloat() / drawable.intrinsicWidth.toFloat()
                val scaleY = (viewHeight).toFloat() / drawable.intrinsicHeight.toFloat()
                val scale = max(scaleX, scaleY)
                val width = ceil((drawable.intrinsicWidth * scale).toDouble()).toInt()
                val height = ceil((drawable.intrinsicHeight * scale).toDouble()).toInt()
                val x = (measuredWidth - width) / 2
                val y = (viewHeight - height) / 2
                canvas.save()
                canvas.clipRect(0, 0, width, measuredHeight)
                drawable.setBounds(x, y, x + width, y + height)
            }
            drawable.draw(canvas)
            canvas.restore()
        }
        shadowDrawable.setBounds(0, 0, measuredWidth, measuredHeight)
        shadowDrawable.draw(canvas)
    }

    protected fun drawMessageAvatars(canvas: Canvas) {
        for (i in 0 until childCount) {
            val cell = getChildAt(i) as? ChatMessageCell ?: continue
            if (!cell.isAvatarVisible) continue
            val receiver = cell.avatarImage ?: continue
            val top = cell.top + cell.paddingTop
            val bottom = minOf(top + cell.layoutHeight, cell.bottom)
            val y = maxOf(bottom, top + AndroidUtilities.dp(48f))
            receiver.setImageY((y - AndroidUtilities.dp(44f)).toFloat())
            receiver.setAlpha(1f)
            receiver.setVisible(true, false)
            receiver.draw(canvas)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (backgroundGradientDisposable != null) {
            backgroundGradientDisposable!!.dispose()
            backgroundGradientDisposable = null
        }
    }

    override fun dispatchSetPressed(pressed: Boolean) {
    }
}
