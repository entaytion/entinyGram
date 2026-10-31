package desu.inugram.helpers.chat.export

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.SeekBar
import org.telegram.ui.Components.Switch

internal class ChatExportSheet(
    context: Context,
    chatTitle: String,
    private val onExport: (ExportOptions) -> Unit,
) : BottomSheet(context, false) {

    private val options = ExportOptions()

    private val sheetColor = getThemedColor(Theme.key_dialogBackground)
    private val textColor = getThemedColor(Theme.key_dialogTextBlack)
    private val grayColor = getThemedColor(Theme.key_dialogTextGray2)
    private val accent = getThemedColor(Theme.key_featuredStickers_addButton)
    private val onAccent = getThemedColor(Theme.key_featuredStickers_buttonText)
    private val surface = ColorUtils.blendARGB(sheetColor, textColor, 0.07f)
    private val tonal = ColorUtils.blendARGB(sheetColor, accent, 0.16f)

    private val mediaRows = ArrayList<ToggleRow>()
    private lateinit var formatPill: TextView
    private lateinit var mediaPill: TextView
    private lateinit var limitPill: TextView
    private lateinit var formatInfo: TextView
    private lateinit var selectAll: TextView
    private lateinit var limitCard: View
    private lateinit var limitValue: TextView
    private lateinit var limitSlider: LimitSlider

    init {
        setApplyBottomPadding(false)
        setApplyTopPadding(false)
        fixNavigationBar(sheetColor)

        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(8f))
        }
        content.addView(buildHeader(context, chatTitle), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        content.addView(sectionTitle(context, R.string.InuChatExportSectionFormat, null), sectionParams())
        val formatCard = card(context)
        formatCard.addView(
            Segmented(
                context,
                listOf(LocaleController.getString(R.string.InuChatExportHtml), "JSON"),
                if (options.html) 0 else 1,
            ) { applyFormat(it == 0) },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 8f, 8f, 8f, 8f),
        )
        content.addView(formatCard, cardParams())
        formatInfo = infoText(context)
        content.addView(formatInfo, infoParams())

        selectAll = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            typeface = AndroidUtilities.bold()
            setTextColor(accent)
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            background = ripple(0, 8f, ColorUtils.setAlphaComponent(accent, 40))
            setOnClickListener {
                val enable = mediaRows.any { !it.checked }
                mediaRows.forEach { it.set(enable) }
                updateSummary()
            }
        }
        content.addView(sectionTitle(context, R.string.InuChatExportSectionMedia, selectAll), sectionParams())
        val mediaCard = card(context)
        fun media(label: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
            if (mediaRows.isNotEmpty()) mediaCard.addView(divider(context))
            val row = ToggleRow(context, LocaleController.getString(label), get()) { value ->
                set(value)
                updateSummary()
            }
            mediaRows.add(row)
            mediaCard.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56))
        }
        media(R.string.InuChatExportPhotos, { options.photo }) { options.photo = it }
        media(R.string.InuChatExportVideos, { options.video }) { options.video = it }
        media(R.string.InuChatExportVoice, { options.voice }) { options.voice = it }
        media(R.string.InuChatExportVideoMessages, { options.round }) { options.round = it }
        media(R.string.InuChatExportStickers, { options.sticker }) { options.sticker = it }
        media(R.string.InuChatExportGifs, { options.gif }) { options.gif = it }
        media(R.string.InuChatExportFiles, { options.document }) { options.document = it }
        content.addView(mediaCard, cardParams())
        content.addView(
            infoText(context).apply { text = LocaleController.getString(R.string.InuChatExportSectionMediaInfo) },
            infoParams(),
        )

        content.addView(sectionTitle(context, R.string.InuChatExportSectionLimit, null), sectionParams())
        limitCard = card(context).also { limit ->
            val head = FrameLayout(context)
            head.addView(TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
                setTextColor(textColor)
                text = LocaleController.getString(R.string.InuChatExportLimitTitle)
            }, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, -2f, Gravity.START or Gravity.CENTER_VERTICAL, 16f, 0f, 16f, 0f))
            limitValue = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
                typeface = AndroidUtilities.bold()
                setTextColor(accent)
            }
            head.addView(limitValue, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, -2f, Gravity.END or Gravity.CENTER_VERTICAL, 16f, 0f, 16f, 0f))
            limit.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52))
            limitSlider = LimitSlider(context, progressForMb(options.sizeLimitMb), surface, accent) { p ->
                options.sizeLimitMb = mbForProgress(p)
                updateSummary()
            }
            limit.addView(
                limitSlider,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 10f, 0f, 10f, 8f),
            )
        }
        content.addView(limitCard, cardParams())
        content.addView(
            infoText(context).apply { text = LocaleController.getString(R.string.InuChatExportSectionLimitInfo) },
            infoParams(),
        )

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        root.addView(buildActions(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        applyFormat(options.html)
        setCustomView(root)
    }

    private fun applyFormat(html: Boolean) {
        options.html = html
        mediaRows.forEach { it.lock(!html) }
        limitCard.alpha = if (html) 1f else 0.4f
        limitSlider.isEnabled = html
        formatInfo.text = LocaleController.getString(
            if (html) R.string.InuChatExportFormatHtmlInfo else R.string.InuChatExportFormatJsonInfo
        )
        updateSummary()
    }

    private fun updateSummary() {
        val enabled = mediaRows.count { it.checked }
        formatPill.text = if (options.html) "HTML" else "JSON"
        limitValue.text = LocaleController.formatString(R.string.InuChatExportMb, options.sizeLimitMb)
        limitPill.text = limitValue.text
        limitPill.visibility = if (options.html) View.VISIBLE else View.GONE
        mediaPill.visibility = if (options.html) View.VISIBLE else View.GONE
        mediaPill.text = when (enabled) {
            0 -> LocaleController.getString(R.string.InuChatExportMediaNone)
            mediaRows.size -> LocaleController.getString(R.string.InuChatExportMediaAll)
            else -> LocaleController.formatString(R.string.InuChatExportMediaSome, enabled, mediaRows.size)
        }
        selectAll.text = LocaleController.getString(
            if (enabled == mediaRows.size) R.string.InuChatExportClear else R.string.InuChatExportSelectAll
        )
        selectAll.alpha = if (options.html) 1f else 0.4f
        selectAll.isEnabled = options.html
    }

    private fun buildHeader(context: Context, chatTitle: String): View {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            background = rounded(tonal, 28f)
        }

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.msg_download)
            colorFilter = PorterDuffColorFilter(onAccent, PorterDuff.Mode.MULTIPLY)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
        }, LayoutHelper.createLinear(52, 52))

        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(context).apply {
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            typeface = AndroidUtilities.bold()
            text = LocaleController.getString(R.string.InuChatExportOptions)
        })
        titles.addView(TextView(context).apply {
            setTextColor(grayColor)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            text = chatTitle
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))
        top.addView(titles, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 14, 0, 8, 0))

        top.addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.msg_close)
            colorFilter = PorterDuffColorFilter(textColor, PorterDuff.Mode.MULTIPLY)
            background = ripple(ColorUtils.blendARGB(tonal, textColor, 0.1f), 18f, ColorUtils.setAlphaComponent(textColor, 40))
            setOnClickListener { dismiss() }
        }, LayoutHelper.createLinear(36, 36))
        header.addView(top)

        val pills = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        formatPill = pill(context)
        mediaPill = pill(context)
        limitPill = pill(context)
        pills.addView(formatPill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 8f, 0f))
        pills.addView(mediaPill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 8f, 0f))
        pills.addView(limitPill)
        header.addView(pills, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 14f, 0f, 0f))
        return header
    }

    private fun buildActions(context: Context): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            setBackgroundColor(sheetColor)
        }
        bar.addView(View(context).apply {
            setBackgroundColor(Theme.getColor(Theme.key_divider))
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0f, -12f, 0f, 12f))

        bar.addView(button(context, R.string.InuChatExportGo, onAccent, accent, ColorUtils.setAlphaComponent(onAccent, 60)) {
            dismiss()
            onExport(options)
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52))
        bar.addView(button(context, R.string.InuChatExportMakeDefault, accent, tonal, ColorUtils.setAlphaComponent(accent, 50)) {
            options.saveAsDefault()
            dismiss()
            onExport(options)
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 0f, 8f, 0f, 0f))
        return bar
    }

    private fun button(context: Context, label: Int, color: Int, fill: Int, rippleColor: Int, click: () -> Unit) =
        TextView(context).apply {
            text = LocaleController.getString(label)
            isAllCaps = false
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            typeface = AndroidUtilities.bold()
            setTextColor(color)
            background = ripple(fill, 26f, rippleColor)
            setOnClickListener { click() }
        }

    private fun pill(context: Context) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        typeface = AndroidUtilities.bold()
        setTextColor(accent)
        gravity = Gravity.CENTER
        setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
        background = rounded(ColorUtils.blendARGB(tonal, accent, 0.14f), 100f)
    }

    private fun sectionTitle(context: Context, title: Int, action: View?): View {
        val row = FrameLayout(context)
        row.addView(TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            typeface = AndroidUtilities.bold()
            setTextColor(accent)
            text = LocaleController.getString(title)
        }, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, -2f, Gravity.START or Gravity.CENTER_VERTICAL, 8f, 0f, 8f, 0f))
        if (action != null) {
            row.addView(action, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        }
        return row
    }

    private fun sectionParams() = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0f, 14f, 0f, 2f)

    private fun cardParams() = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)

    private fun infoParams() = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8f, 6f, 8f, 0f)

    private fun infoText(context: Context) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        setTextColor(grayColor)
    }

    private fun card(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(surface, 22f)
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    private fun divider(context: Context) = View(context).apply {
        setBackgroundColor(ColorUtils.setAlphaComponent(textColor, 22))
    }.also { it.layoutParams = LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, 1).apply { leftMargin = dp(16f) } }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun ripple(color: Int, radiusDp: Float, rippleColor: Int) =
        Theme.createSimpleSelectorRoundRectDrawable(dp(radiusDp), color, rippleColor)

    private fun dp(value: Float) = AndroidUtilities.dp(value)

    private inner class ToggleRow(
        context: Context,
        label: String,
        initial: Boolean,
        private val onChange: (Boolean) -> Unit,
    ) : FrameLayout(context) {
        var checked = initial
            private set
        private var locked = false
        private val toggle = Switch(context).apply {
            setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite)
            setChecked(initial, false)
        }

        init {
            foreground = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2)
            addView(TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
                setTextColor(textColor)
                text = label
            }, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, -2f, Gravity.START or Gravity.CENTER_VERTICAL, 16f, 0f, 72f, 0f))
            addView(toggle, LayoutHelper.createFrame(37, 20f, Gravity.END or Gravity.CENTER_VERTICAL, 0f, 0f, 16f, 0f))
            setOnClickListener {
                if (locked) return@setOnClickListener
                set(!checked)
            }
        }

        fun set(value: Boolean) {
            checked = value
            toggle.setChecked(value, true)
            onChange(value)
        }

        fun lock(value: Boolean) {
            locked = value
            alpha = if (value) 0.4f else 1f
        }
    }

    private inner class Segmented(
        context: Context,
        private val labels: List<String>,
        selected: Int,
        private val onSelect: (Int) -> Unit,
    ) : View(context) {
        private var index = selected
        private var position = selected.toFloat()
        private val rect = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = AndroidUtilities.dp(15f).toFloat()
            typeface = AndroidUtilities.bold()
            textAlign = Paint.Align.CENTER
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> return true
                MotionEvent.ACTION_UP -> {
                    val target = (event.x / width * labels.size).toInt().coerceIn(0, labels.size - 1)
                    if (target != index) select(target)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun select(target: Int) {
            index = target
            onSelect(target)
            ValueAnimator.ofFloat(position, target.toFloat()).apply {
                duration = 280
                interpolator = CubicBezierInterpolator.EASE_OUT_QUINT
                addUpdateListener {
                    position = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val radius = height / 2f
            paint.color = ColorUtils.blendARGB(surface, textColor, 0.06f)
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, paint)

            val segment = width / labels.size.toFloat()
            val inset = AndroidUtilities.dp(4f).toFloat()
            paint.color = accent
            rect.set(position * segment + inset, inset, (position + 1) * segment - inset, height - inset)
            canvas.drawRoundRect(rect, radius - inset, radius - inset, paint)

            val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            labels.forEachIndexed { i, label ->
                val near = (1f - Math.abs(position - i)).coerceIn(0f, 1f)
                textPaint.color = ColorUtils.blendARGB(textColor, onAccent, near)
                canvas.drawText(label, segment * (i + 0.5f), baseline, textPaint)
            }
        }
    }
}

// log scale: progress 0..1 -> 1..1024 MB
private fun mbForProgress(p: Float): Int =
    Math.round(Math.pow(2.0, (p.coerceIn(0f, 1f) * 10).toDouble())).toInt().coerceIn(1, 1024)

private fun progressForMb(mb: Int): Float =
    (Math.log((mb.coerceIn(1, 1024)).toDouble()) / Math.log(2.0) / 10.0).toFloat()

private class LimitSlider(
    context: Context,
    initialProgress: Float,
    track: Int,
    accent: Int,
    private val onChanged: (Float) -> Unit,
) : View(context) {
    private val seekBar = SeekBar(this)

    init {
        val trackColor = ColorUtils.blendARGB(track, Theme.getColor(Theme.key_dialogTextBlack), 0.18f)
        seekBar.setColors(trackColor, trackColor, accent, accent, trackColor)
        seekBar.setProgress(initialProgress)
        seekBar.setDelegate(object : SeekBar.SeekBarDelegate {
            override fun onSeekBarDrag(progress: Float) {
                seekBar.setProgress(progress)
                onChanged(progress)
            }

            override fun onSeekBarContinuousDrag(progress: Float) {
                onChanged(progress)
            }
        })
        isClickable = true
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthSpec),
            AndroidUtilities.dp(32f),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        seekBar.setSize(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        seekBar.draw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val handled = seekBar.onTouch(event.actionMasked, event.x, event.y)
        if (handled) {
            invalidate()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return handled || super.onTouchEvent(event)
    }
}
