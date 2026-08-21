package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Region
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import desu.inugram.InuConfig
import desu.inugram.helpers.theme.AvatarShapeHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedTextView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.SeekBarView

@SuppressLint("ViewConstructor")
class AvatarCornerPreviewCell(
    context: Context,
    private val onValueChanged: Runnable? = null
) : FrameLayout(context) {

    private val rect = RectF()
    private val onlineCutoutPath = Path()
    private val mockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val brightMockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seekBar: AltSeekbar
    private val preview: FrameLayout
    private var committedAvatarCorners: Float = InuConfig.AVATAR_CORNERS.value
    private var previewAvatarCorners: Float = committedAvatarCorners

    init {
        setWillNotDraw(false)
        if (!InuConfig.M3_SECTIONS_STYLE.value) {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        }

        seekBar = AltSeekbar(
            context = context,
            onDrag = { value, stop ->
                previewAvatarCorners = value
                InuConfig.AVATAR_CORNERS.value = value
                invalidate()
                val valueChanged = committedAvatarCorners.compareTo(value) != 0
                if (stop && valueChanged) {
                    committedAvatarCorners = value
                    onValueChanged?.run()
                }
            },
            min = 0,
            max = 28,
            header = LocaleController.getString(R.string.InuAvatarCorners),
            leftText = LocaleController.getString(R.string.InuAvatarCornersLeft),
            rightText = LocaleController.getString(R.string.InuAvatarCornersRight)
        )
        seekBar.setProgress(previewAvatarCorners / 28.0f)
        addView(seekBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat()))

        preview = object : FrameLayout(context) {
            override fun onDraw(canvas: Canvas) {
                drawMock(canvas, measuredWidth.toFloat())
            }
        }.apply {
            setWillNotDraw(false)
            background = PreviewCardDrawable()
            minimumHeight = AndroidUtilities.dp(83f)
        }
        addView(
            preview,
            LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT.toFloat(),
                Gravity.CENTER_HORIZONTAL or Gravity.TOP,
                21f,
                114f,
                21f,
                21f
            )
        )
    }

    fun updatePreview() {
        committedAvatarCorners = InuConfig.AVATAR_CORNERS.value
        previewAvatarCorners = committedAvatarCorners
        if (!InuConfig.M3_SECTIONS_STYLE.value) {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        } else {
            background = null
        }
        seekBar.setProgress(previewAvatarCorners / 28.0f)
        seekBar.updateColors()
        invalidate()
    }

    private fun getAvatarSquareness(): Float {
        return AvatarShapeHelper.getAvatarSquareness(previewAvatarCorners)
    }

    private fun getOnlineDotOffset(baseDp: Float, outerRadius: Float): Float {
        return AvatarShapeHelper.getOnlineDotOffset(baseDp, outerRadius, getAvatarSquareness())
    }

    private fun drawMock(canvas: Canvas, width: Float) {
        val dp1 = AndroidUtilities.dp(1f).toFloat()
        val left = AndroidUtilities.dp(15f).toFloat()
        val textStart = AndroidUtilities.dp(83f).toFloat()
        val avatarSize = AndroidUtilities.dp(56f).toFloat()
        val avatarTop = AndroidUtilities.dp(12f).toFloat() + dp1
        val avatarRight = left + avatarSize
        val avatarBottom = avatarTop + avatarSize

        mockPaint.color = Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), 0.2f)
        brightMockPaint.color = Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), 0.4f)

        val squareness = getAvatarSquareness()
        val outerRadius = AndroidUtilities.dpf2(2f * squareness + 7f)
        val innerRadius = AndroidUtilities.dpf2(squareness + 5f)
        val onlineCx = avatarRight - getOnlineDotOffset(AndroidUtilities.dpf2(8f), outerRadius)
        val onlineCy = avatarBottom - getOnlineDotOffset(AndroidUtilities.dpf2(7.5f), outerRadius)

        canvas.save()
        onlineCutoutPath.reset()
        onlineCutoutPath.addCircle(onlineCx, onlineCy, outerRadius, Path.Direction.CCW)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipOutPath(onlineCutoutPath)
        } else {
            @Suppress("DEPRECATION")
            canvas.clipPath(onlineCutoutPath, Region.Op.DIFFERENCE)
        }
        rect.set(left, avatarTop, avatarRight, avatarBottom)
        val avatarCornerRadius = AvatarShapeHelper.getAvatarRoundRadius(
            56f,
            previewAvatarCorners,
            forum = false,
            storyCompensation = false,
            singleCornerRadius = InuConfig.UNIFIED_AVATAR_RADIUS.value
        ).toFloat()
        canvas.drawRoundRect(rect, avatarCornerRadius, avatarCornerRadius, brightMockPaint)
        canvas.restore()

        Theme.dialogs_onlineCirclePaint.color = Theme.getColor(Theme.key_chats_onlineCircle)
        canvas.drawCircle(onlineCx, onlineCy, innerRadius, Theme.dialogs_onlineCirclePaint)

        val rowRadius = AndroidUtilities.dp(4f).toFloat()
        rect.set(
            textStart + AndroidUtilities.dp(6f),
            AndroidUtilities.dp(16f) + dp1,
            textStart + AndroidUtilities.dp(6f) + 0.4f * width,
            AndroidUtilities.dp(24.33f) + dp1
        )
        canvas.drawRoundRect(rect, rowRadius, rowRadius, brightMockPaint)

        rect.set(
            textStart + AndroidUtilities.dp(6f),
            AndroidUtilities.dp(38f) + dp1,
            textStart + AndroidUtilities.dp(6f) + 0.5f * width,
            AndroidUtilities.dp(46.33f) + dp1
        )
        canvas.drawRoundRect(rect, rowRadius, rowRadius, mockPaint)

        rect.set(
            textStart + AndroidUtilities.dp(6f),
            AndroidUtilities.dp(56f) + dp1,
            textStart + AndroidUtilities.dp(6f) + 0.36f * width,
            AndroidUtilities.dp(64.33f) + dp1
        )
        canvas.drawRoundRect(rect, rowRadius, rowRadius, mockPaint)

        rect.set(
            width - AndroidUtilities.dp(16f) - AndroidUtilities.dp(43f),
            AndroidUtilities.dp(16f) + dp1,
            width - AndroidUtilities.dp(16f),
            AndroidUtilities.dp(24.33f) + dp1
        )
        canvas.drawRoundRect(rect, rowRadius, rowRadius, mockPaint)
    }

    private class PreviewCardDrawable : Drawable() {
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = AndroidUtilities.dpf2(0.5f)
        }
        private val rectF = RectF()
        private val radius = AndroidUtilities.dp(12f).toFloat()

        override fun draw(canvas: Canvas) {
            val baseAlpha = if (Theme.isCurrentThemeDark()) 0.05f else 0.035f
            val textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText)
            backgroundPaint.color = Theme.multAlpha(textColor, baseAlpha)
            strokePaint.color = Theme.multAlpha(textColor, baseAlpha + 0.085f)
            val sw = strokePaint.strokeWidth / 2f
            rectF.set(
                bounds.left + sw,
                bounds.top + sw,
                bounds.right - sw,
                bounds.bottom - sw
            )
            canvas.drawRoundRect(rectF, radius, radius, backgroundPaint)
            canvas.drawRoundRect(rectF, radius, radius, strokePaint)
        }

        override fun setAlpha(alpha: Int) {
            backgroundPaint.alpha = alpha
            strokePaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            backgroundPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
            invalidateSelf()
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    override fun invalidate() {
        super.invalidate()
        preview.invalidate()
        seekBar.invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!InuConfig.M3_SECTIONS_STYLE.value) {
            canvas.drawLine(
                if (LocaleController.isRTL) 0.0f else AndroidUtilities.dp(21.0f).toFloat(),
                (measuredHeight - 1).toFloat(),
                measuredWidth - (if (LocaleController.isRTL) AndroidUtilities.dp(21.0f).toFloat() else 0f),
                (measuredHeight - 1).toFloat(),
                Theme.dividerPaint
            )
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(222.0f), MeasureSpec.EXACTLY)
        )
    }

    class AltSeekbar(
        context: Context,
        private val onDrag: (value: Float, stop: Boolean) -> Unit,
        private val min: Int,
        private val max: Int,
        header: String,
        private val leftText: String,
        private val rightText: String
    ) : FrameLayout(context) {

        private val headerTextView: TextView
        private val headerValue: AnimatedTextView
        private val leftTextView: TextView
        private val rightTextView: TextView
        val seekBarView: SeekBarView

        private var currentValue: Float = 0f
        private var roundedValue: Int = -1
        private var vibro: Int = -1

        init {
            val headerLayout = LinearLayout(context).apply {
                gravity = Gravity.START
            }

            headerTextView = TextView(context).apply {
                textSize = 15.0f
                typeface = AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM)
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader))
                gravity = Gravity.START
                text = header
            }
            headerLayout.addView(
                headerTextView,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL)
            )

            headerValue = object : AnimatedTextView(context, false, true, true) {
                private val backgroundDrawable = Theme.createRoundRectDrawable(
                    AndroidUtilities.dp(4.0f),
                    Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader), 0.15f)
                )

                override fun onDraw(canvas: Canvas) {
                    val w = getDrawable().currentWidth
                    backgroundDrawable.setBounds(
                        0,
                        0,
                        (paddingLeft + w + paddingRight).toInt(),
                        measuredHeight
                    )
                    backgroundDrawable.draw(canvas)
                    super.onDraw(canvas)
                }
            }.apply {
                setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM))
                setPadding(
                    AndroidUtilities.dp(5.33f),
                    AndroidUtilities.dp(2.0f),
                    AndroidUtilities.dp(5.33f),
                    AndroidUtilities.dp(2.0f)
                )
                setTextSize(AndroidUtilities.dp(12.0f).toFloat())
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader))
            }
            headerLayout.addView(
                headerValue,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 17, Gravity.CENTER_VERTICAL, 6f, 1f, 0f, 0f)
            )

            addView(
                headerLayout,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.START or Gravity.TOP, 21f, 17f, 21f, 0f)
            )

            seekBarView = SeekBarView(context, true, null).apply {
                setReportChanges(true)
                delegate = object : SeekBarView.SeekBarViewDelegate {
                    override fun onSeekBarDrag(stop: Boolean, progress: Float) {
                        val value = min + ((max - min) * progress)
                        onDrag(value, stop)
                        val newRounded = Math.round(value)
                        if (newRounded != roundedValue) {
                            roundedValue = newRounded
                            currentValue = value
                            headerValue.setText(getTextForHeader(), false)
                            headerValue.invalidate()
                            if ((roundedValue == min || roundedValue == max) && roundedValue != vibro) {
                                vibro = roundedValue
                                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                            } else if (roundedValue > min && roundedValue < max) {
                                vibro = -1
                            }
                        } else {
                            currentValue = value
                        }
                        updateValues()
                    }

                    override fun onSeekBarPressed(pressed: Boolean) {}
                }
            }
            addView(
                seekBarView,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44f, Gravity.START or Gravity.BOTTOM, 6f, 68f, 6f, 0f)
            )

            val labelsLayout = FrameLayout(context)

            leftTextView = TextView(context).apply {
                textSize = 13.0f
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
                gravity = Gravity.START
                text = leftText
            }
            labelsLayout.addView(
                leftTextView,
                LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.START or Gravity.TOP)
            )

            rightTextView = TextView(context).apply {
                textSize = 13.0f
                setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
                gravity = Gravity.END
                text = rightText
            }
            labelsLayout.addView(
                rightTextView,
                LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.END or Gravity.TOP)
            )

            addView(
                labelsLayout,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.START or Gravity.TOP, 21f, 52f, 21f, 0f)
            )
        }

        fun setProgress(progress: Float) {
            val value = min + ((max - min) * progress)
            currentValue = value
            roundedValue = Math.round(value)
            seekBarView.progress = progress
            headerValue.setText(getTextForHeader(), false)
            headerValue.invalidate()
            if ((roundedValue == min || roundedValue == max) && roundedValue != vibro) {
                vibro = roundedValue
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
            } else if (roundedValue > min && roundedValue < max) {
                vibro = -1
            }
            updateValues()
        }

        private fun updateValues() {
            val gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText)
            val blue = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText)
            val center = ((max - min) / 2.0f) + min
            val leftThreshold = (center + min) * 0.5f
            val rightThreshold = (center + max) * 0.5f
            if (currentValue >= rightThreshold) {
                val progress = clamp01((currentValue - rightThreshold) / (max - rightThreshold))
                rightTextView.setTextColor(ColorUtils.blendARGB(gray, blue, progress))
                leftTextView.setTextColor(gray)
            } else if (currentValue <= leftThreshold) {
                val progress = clamp01((leftThreshold - currentValue) / (leftThreshold - min))
                leftTextView.setTextColor(ColorUtils.blendARGB(gray, blue, progress))
                rightTextView.setTextColor(gray)
            } else {
                leftTextView.setTextColor(gray)
                rightTextView.setTextColor(gray)
            }
        }

        fun updateColors() {
            headerTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader))
            headerValue.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader))
            headerValue.invalidate()
            updateValues()
        }

        private fun getTextForHeader(): CharSequence {
            val text: CharSequence = when (roundedValue) {
                min -> leftText
                max -> rightText
                else -> roundedValue.toString()
            }
            return text.toString().uppercase()
        }

        private fun clamp01(value: Float): Float {
            return Math.max(0.0f, Math.min(1.0f, value))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(112.0f), MeasureSpec.EXACTLY)
            )
        }
    }
}
