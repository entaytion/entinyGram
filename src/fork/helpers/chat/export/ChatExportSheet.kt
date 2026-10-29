package desu.inugram.helpers.chat.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.SeekBar

internal class ChatExportSheet(
    context: Context,
    chatTitle: String,
    private val onExport: (ExportOptions) -> Unit,
) : BottomSheet(context, false) {

    private val options = ExportOptions()

    init {
        setApplyBottomPadding(false)
        setApplyTopPadding(false)
        fixNavigationBar(getThemedColor(Theme.key_windowBackgroundWhite))

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(18f), AndroidUtilities.dp(16f), AndroidUtilities.dp(12f))
        }

        root.addView(TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            typeface = AndroidUtilities.bold()
            text = LocaleController.getString(R.string.InuChatExportOptions)
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4f, 0f, 4f, 0f))
        root.addView(TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextGray2))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            text = chatTitle
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4f, 2f, 4f, 14f))

        val mediaChips = ArrayList<Chip>()
        val limitLabel = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(Theme.getColor(Theme.key_dialogTextGray2))
        }
        fun formatLimit(mb: Int): String = LocaleController.formatString(R.string.InuChatExportSizeLimit, mb)
        val slider = LimitSlider(context, progressForMb(options.sizeLimitMb)) { p ->
            val mb = mbForProgress(p)
            options.sizeLimitMb = mb
            limitLabel.text = formatLimit(mb)
        }
        limitLabel.text = formatLimit(options.sizeLimitMb)

        fun applyFormat(html: Boolean) {
            options.html = html
            mediaChips.forEach { it.lock(!html) }
            slider.isEnabled = html
            slider.alpha = if (html) 1f else 0.4f
            limitLabel.alpha = if (html) 1f else 0.4f
        }

        val htmlChip = Chip(context, LocaleController.getString(R.string.InuChatExportHtml), options.html)
        val jsonChip = Chip(context, "JSON", !options.html)
        htmlChip.onClick = { htmlChip.set(true); jsonChip.set(false); applyFormat(true) }
        jsonChip.onClick = { htmlChip.set(false); jsonChip.set(true); applyFormat(false) }
        addRow(root, htmlChip, jsonChip, 0f)

        fun media(label: Int, initial: Boolean, apply: (Boolean) -> Unit): Chip =
            Chip(context, LocaleController.getString(label), initial).also { chip ->
                chip.onClick = { chip.set(!chip.checked); apply(chip.checked) }
                mediaChips.add(chip)
            }

        val photo = media(R.string.InuChatExportPhotos, options.photo) { options.photo = it }
        val video = media(R.string.InuChatExportVideos, options.video) { options.video = it }
        val voice = media(R.string.InuChatExportVoice, options.voice) { options.voice = it }
        val round = media(R.string.InuChatExportVideoMessages, options.round) { options.round = it }
        val sticker = media(R.string.InuChatExportStickers, options.sticker) { options.sticker = it }
        val gif = media(R.string.InuChatExportGifs, options.gif) { options.gif = it }
        val document = media(R.string.InuChatExportFiles, options.document) { options.document = it }
        addRow(root, photo, video, 8f)
        addRow(root, voice, round, 8f)
        addRow(root, sticker, gif, 8f)
        addRow(root, document, null, 8f)

        root.addView(limitLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4f, 14f, 4f, 0f))
        root.addView(slider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4f, 0f, 4f, 0f))

        root.addView(TextView(context).apply {
            text = LocaleController.getString(R.string.InuChatExportGo)
            isAllCaps = false
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            typeface = AndroidUtilities.bold()
            setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText))
            background = Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(12f),
                Theme.getColor(Theme.key_featuredStickers_addButton),
                ColorUtils.setAlphaComponent(Color.WHITE, 60),
            )
            setOnClickListener {
                dismiss()
                onExport(options)
            }
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 4f, 16f, 4f, 0f))

        setCustomView(root)
    }

    private fun addRow(parent: LinearLayout, first: Chip, second: Chip?, topMargin: Float) {
        val row = LinearLayout(parent.context).apply { orientation = LinearLayout.HORIZONTAL }
        val gap = AndroidUtilities.dp(4f)
        row.addView(first, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f).apply { setMargins(gap, 0, gap, 0) })
        val right: View = second ?: View(parent.context)
        row.addView(right, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f).apply { setMargins(gap, 0, gap, 0) })
        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, topMargin, 0f, 0f))
    }

    private class Chip(context: Context, label: CharSequence, selected: Boolean) : TextView(context) {
        var checked = selected
            private set
        var onClick: () -> Unit = {}
        private var locked = false

        init {
            text = label
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(12f), AndroidUtilities.dp(10f), AndroidUtilities.dp(12f))
            setOnClickListener { if (!locked) onClick() }
            render()
        }

        fun set(value: Boolean) {
            checked = value
            render()
        }

        fun lock(value: Boolean) {
            locked = value
            alpha = if (value) 0.4f else 1f
        }

        private fun render() {
            val accent = Theme.getColor(Theme.key_featuredStickers_addButton)
            background = GradientDrawable().apply {
                cornerRadius = AndroidUtilities.dp(12f).toFloat()
                if (checked) {
                    setColor(ColorUtils.setAlphaComponent(accent, 40))
                    setStroke(AndroidUtilities.dp(1.5f), accent)
                } else {
                    setColor(Theme.getColor(Theme.key_dialogBackgroundGray))
                    setStroke(AndroidUtilities.dp(1.5f), 0)
                }
            }
            setTextColor(if (checked) accent else Theme.getColor(Theme.key_dialogTextBlack))
            typeface = if (checked) AndroidUtilities.bold() else Typeface.DEFAULT
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
    private val onChanged: (Float) -> Unit,
) : View(context) {
    private val seekBar = SeekBar(this)

    init {
        seekBar.setColors(
            Theme.getColor(Theme.key_player_progressBackground),
            Theme.getColor(Theme.key_player_progressCachedBackground),
            Theme.getColor(Theme.key_player_progress),
            Theme.getColor(Theme.key_player_progress),
            Theme.getColor(Theme.key_player_progressBackground),
        )
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
