package desu.inugram.helpers.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaController
import org.telegram.messenger.R
import org.telegram.ui.Components.RadialProgressView
import org.telegram.ui.Components.RecyclerListView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private fun dp(value: Float): Int = AndroidUtilities.dp(value)

class Md3LyricsView(context: Context) : FrameLayout(context) {
    companion object {
        const val STATE_LYRICS = 0
        const val STATE_LOADING = 1
        const val STATE_OFFER = 2
        const val STATE_NOT_FOUND = 3
        const val STATE_ERROR = 4
        const val STATE_INSTRUMENTAL = 5

        private const val FOLLOW_RESUME_MS = 750L
        private const val MAX_BLUR_DISTANCE = 4
        private const val BLUR_PER_LINE_DP = 1.25f

        private const val ROLE_PAST = 0
        private const val ROLE_ACTIVE = 1
        private const val ROLE_FUTURE = 2
        private const val ROLE_PLAIN = 3
    }

    interface Delegate {
        fun onAction(state: Int)
        fun onSeek(ms: Long)
    }

    private val list = RecyclerListView(context)
    private val layoutManager = LinearLayoutManager(context)
    private val adapter = Adapter()
    private val empty = LinearLayout(context)
    private val tileIcon = Md3PlayerIcon.stroke(Md3PlayerIcon.NOTE, 28f)
    private val tileBg = GradientDrawable()
    private val buttonBg = GradientDrawable()
    private val tile = object : FrameLayout(context) {
        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            tileIcon.setBounds(0, 0, width, height)
            tileIcon.draw(canvas)
        }
    }
    private val emptyTitle = TextView(context)
    private val emptyText = TextView(context)
    private val actionButton = TextView(context)
    private val progress = RadialProgressView(context)
    private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private var topFade: LinearGradient? = null
    private var bottomFade: LinearGradient? = null
    var delegate: Delegate? = null
    var lyrics: Md3Lyrics? = null
        private set
    var state = -1
        private set
    private var active = -1
    private var userScrollAt = 0L
    private var userDragging = false
    private var colorActive = 0
    private var positionMs = 0L
    private var positionAt = 0L
    private val resumeFollow = Runnable {
        userScrollAt = 0L
        updateBlur()
        scrollToActive(true)
    }

    init {
        list.layoutManager = layoutManager
        list.adapter = adapter
        list.itemAnimator = null
        list.clipToPadding = false
        list.isVerticalScrollBarEnabled = false
        list.overScrollMode = OVER_SCROLL_NEVER
        list.setSelectorDrawableColor(0)
        list.setOnItemClickListener { _, position ->
            val l = lyrics
            if (l != null && l.synced && position >= 0 && position < l.lines.size) {
                delegate?.onSeek(l.lines[position].time)
                userScrollAt = 0L
            }
        }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                userDragging = newState == RecyclerView.SCROLL_STATE_DRAGGING
                if (userDragging) {
                    userScrollAt = SystemClock.elapsedRealtime()
                    removeCallbacks(resumeFollow)
                    updateBlur()
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE && userScrollAt != 0L) {
                    userScrollAt = SystemClock.elapsedRealtime()
                    removeCallbacks(resumeFollow)
                    postDelayed(resumeFollow, FOLLOW_RESUME_MS)
                }
            }
        })
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        empty.orientation = LinearLayout.VERTICAL
        empty.gravity = Gravity.CENTER_HORIZONTAL
        tile.setWillNotDraw(false)
        tileBg.cornerRadius = dp(22f).toFloat()
        tile.background = tileBg
        empty.addView(tile, LinearLayout.LayoutParams(dp(64f), dp(64f)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        progress.setSize(dp(36f))
        empty.addView(progress, LinearLayout.LayoutParams(dp(48f), dp(48f)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
        emptyTitle.typeface = AndroidUtilities.bold()
        emptyTitle.gravity = Gravity.CENTER
        empty.addView(emptyTitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12f)
        })
        emptyText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        emptyText.setLineSpacing(0f, 1.2f)
        emptyText.gravity = Gravity.CENTER
        emptyText.maxWidth = dp(260f)
        empty.addView(emptyText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12f)
        })
        actionButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        actionButton.typeface = AndroidUtilities.bold()
        actionButton.gravity = Gravity.CENTER
        actionButton.setPadding(dp(20f), 0, dp(20f), 0)
        buttonBg.cornerRadius = dp(22f).toFloat()
        actionButton.background = buttonBg
        actionButton.setOnClickListener { delegate?.onAction(state) }
        empty.addView(actionButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(16f)
        })
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(16f)
            rightMargin = dp(16f)
        })
    }

    fun setColors(c: Md3PlayerColors) {
        colorActive = c.onSurface
        tileBg.setColor(c.secondaryContainer)
        tileIcon.setColor(c.onSecondaryContainer)
        tile.invalidate()
        emptyTitle.setTextColor(c.onSurface)
        emptyText.setTextColor(c.onSurfaceVariant)
        actionButton.setTextColor(c.primary)
        buttonBg.setStroke(dp(1f), c.outlineVariant)
        buttonBg.setColor(0)
        progress.setProgressColor(c.primary)
        for (i in 0 until list.childCount) list.getChildAt(i).invalidate()
    }

    fun canScrollUp(): Boolean = list.visibility == VISIBLE && list.canScrollVertically(-1)

    fun showState(newState: Int) {
        state = newState
        if (newState == STATE_LYRICS) {
            list.visibility = VISIBLE
            empty.visibility = GONE
            return
        }
        lyrics = null
        active = -1
        adapter.notifyDataSetChanged()
        list.visibility = GONE
        empty.visibility = VISIBLE
        val loading = newState == STATE_LOADING
        progress.visibility = if (loading) VISIBLE else GONE
        tile.visibility = if (loading) GONE else VISIBLE
        var title: String? = null
        val text: String
        var action: String? = null
        when (newState) {
            STATE_LOADING -> text = LocaleController.getString(R.string.InuMd3PlayerLyricsSearching)
            STATE_OFFER -> {
                title = LocaleController.getString(R.string.InuMd3PlayerNoLyrics)
                text = LocaleController.getString(R.string.InuMd3PlayerNoLyricsInfo)
                action = LocaleController.getString(R.string.InuMd3PlayerFindLyrics)
            }
            STATE_NOT_FOUND -> {
                title = LocaleController.getString(R.string.InuMd3PlayerLyricsNotFound)
                text = LocaleController.getString(R.string.InuMd3PlayerLyricsNotFoundInfo)
            }
            STATE_ERROR -> {
                title = LocaleController.getString(R.string.InuMd3PlayerLyricsError)
                text = LocaleController.getString(R.string.InuMd3PlayerLyricsErrorInfo)
                action = LocaleController.getString(R.string.InuMd3PlayerRetry)
            }
            else -> {
                title = LocaleController.getString(R.string.InuMd3PlayerInstrumental)
                text = LocaleController.getString(R.string.InuMd3PlayerInstrumentalInfo)
            }
        }
        emptyTitle.text = title
        emptyTitle.visibility = if (title == null) GONE else VISIBLE
        emptyText.text = text
        actionButton.text = action
        actionButton.visibility = if (action == null) GONE else VISIBLE
    }

    fun setLyrics(value: Md3Lyrics, positionMs: Long) {
        lyrics = value
        state = STATE_LYRICS
        list.visibility = VISIBLE
        empty.visibility = GONE
        active = value.indexAt(positionMs)
        this.positionMs = positionMs
        positionAt = SystemClock.elapsedRealtime()
        userScrollAt = 0L
        applyPadding()
        adapter.notifyDataSetChanged()
        list.post { scrollToActive(false) }
    }

    fun setPosition(ms: Long) {
        val l = lyrics ?: return
        if (!l.synced) return
        positionMs = ms
        positionAt = SystemClock.elapsedRealtime()
        val index = l.indexAt(ms)
        if (index == active) return
        val old = active
        active = index
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (child is LineView) child.setRole(roleFor(list.getChildAdapterPosition(child)), true)
        }
        val following = !userDragging && (userScrollAt == 0L || SystemClock.elapsedRealtime() - userScrollAt > FOLLOW_RESUME_MS)
        if (following) {
            userScrollAt = 0L
            scrollToActive(old >= 0 && abs(index - old) <= 3)
        }
        updateBlur()
    }

    private fun currentMs(): Long {
        if (MediaController.getInstance().isMessagePaused) return positionMs
        return positionMs + min(2000L, SystemClock.elapsedRealtime() - positionAt)
    }

    private fun blurFor(position: Int): Float {
        val l = lyrics
        if (l == null || !l.synced || active < 0 || userDragging || userScrollAt != 0L) return 0f
        val distance = min(abs(position - active), MAX_BLUR_DISTANCE)
        return dp(BLUR_PER_LINE_DP) * distance.toFloat()
    }

    private fun updateBlur() {
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (child is LineView) child.setBlur(blurFor(list.getChildAdapterPosition(child)))
        }
    }

    private fun roleFor(position: Int): Int {
        val l = lyrics
        if (l == null || !l.synced) return ROLE_PLAIN
        if (position == active) return ROLE_ACTIVE
        return if (position < active) ROLE_PAST else ROLE_FUTURE
    }

    private fun scrollToActive(smooth: Boolean) {
        val l = lyrics ?: return
        if (list.height == 0 || !l.synced) return
        val target = max(active, 0)
        if (target >= adapter.itemCount) return
        if (smooth && layoutManager.findViewByPosition(target) != null) {
            val scroller = object : LinearSmoothScroller(context) {
                override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int = boxStart - viewStart

                override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float = 400f / displayMetrics.densityDpi

                override fun calculateTimeForDeceleration(dx: Int): Int = max(380, min(700, super.calculateTimeForDeceleration(dx)))
            }
            scroller.targetPosition = target
            layoutManager.startSmoothScroll(scroller)
        } else {
            list.stopScroll()
            layoutManager.scrollToPositionWithOffset(target, 0)
        }
    }

    private fun applyPadding() {
        val h = height
        val l = lyrics
        if (l != null && !l.synced) {
            list.setPadding(0, dp(8f), 0, dp(24f))
        } else if (h > 0) {
            val offset = min(dp(108f), (h * 0.3f).toInt())
            list.setPadding(0, offset, 0, max(0, h - offset - dp(72f)))
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val top = topFade
        val bottom = bottomFade
        if (list.visibility != VISIBLE || width == 0 || height == 0 || top == null || bottom == null) {
            super.dispatchDraw(canvas)
            return
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val save = canvas.saveLayer(0f, 0f, w, h, null)
        super.dispatchDraw(canvas)
        fadePaint.shader = top
        canvas.drawRect(0f, 0f, w, dp(32f).toFloat(), fadePaint)
        fadePaint.shader = bottom
        canvas.drawRect(0f, h - dp(40f), w, h, fadePaint)
        canvas.restoreToCount(save)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        topFade = LinearGradient(0f, 0f, 0f, dp(32f).toFloat(), -0x1000000, 0, Shader.TileMode.CLAMP)
        bottomFade = LinearGradient(0f, (h - dp(40f)).toFloat(), 0f, h.toFloat(), 0, -0x1000000, Shader.TileMode.CLAMP)
        if (h != oldh) {
            applyPadding()
            list.post { scrollToActive(false) }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(resumeFollow)
    }

    private inner class Adapter : RecyclerListView.SelectionAdapter() {
        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = lyrics?.synced == true

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = LineView(parent.context)
            view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return RecyclerListView.Holder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val l = lyrics ?: return
            val view = holder.itemView as LineView
            val text = l.lines[position].text
            view.index = position
            view.setText(if (TextUtils.isEmpty(text)) (if (l.synced) "" else "♪") else text, !l.synced)
            view.setRole(roleFor(position), false)
            view.setBlur(blurFor(position))
        }

        override fun getItemCount(): Int = lyrics?.lines?.size ?: 0
    }

    private inner class LineView(context: Context) : View(context) {
        private val opacityFuture = 0.51f
        private val opacityPast = 0.5f
        private val unfilledAlpha = 0.45f
        private val activeScale = 1.05f

        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = AndroidUtilities.bold() }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fillMatrix = Matrix()
        private val opacity = Md3Spring(opacityFuture, 260f, 1f, 0.004f)
        private val scale = Md3Spring(1f, 320f, 0.75f, 0.0005f)
        var index = -1
        private var text: String? = null
        private var plain = false
        private var textLayout: StaticLayout? = null
        private var layoutWidth = 0
        private var role = ROLE_FUTURE
        private var lastFrame = 0L
        private var blur = -1f

        fun setText(value: String, isPlain: Boolean) {
            if (!TextUtils.equals(text, value) || plain != isPlain) {
                text = value
                plain = isPlain
                textLayout = null
                requestLayout()
            }
        }

        fun setBlur(value: Float) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || value == blur) return
            blur = value
            setRenderEffect(if (value > 0f) RenderEffect.createBlurEffect(value, value, Shader.TileMode.DECAL) else null)
        }

        fun setRole(value: Int, animated: Boolean) {
            role = value
            val targetOpacity = when (value) {
                ROLE_PAST -> opacityPast
                ROLE_FUTURE -> opacityFuture
                else -> 1f
            }
            val targetScale = if (value == ROLE_ACTIVE) activeScale else 1f
            if (animated) {
                opacity.target = targetOpacity
                scale.target = targetScale
                lastFrame = 0L
            } else {
                opacity.snap(targetOpacity)
                scale.snap(targetScale)
            }
            invalidate()
        }

        private fun isInterlude(): Boolean = !plain && TextUtils.isEmpty(text)

        private fun textSize(): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, if (plain) 20f else 28f, resources.displayMetrics)

        private fun ensureLayout(width: Int): StaticLayout {
            val current = textLayout
            if (current != null && layoutWidth == width) return current
            layoutWidth = width
            paint.textSize = textSize()
            val value = text ?: ""
            val built = StaticLayout.Builder.obtain(value, 0, value.length, paint, max(1, (width / activeScale).toInt()))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.18f)
                .setIncludePad(false)
                .build()
            textLayout = built
            return built
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val built = ensureLayout(width)
            val pad = if (plain) dp(6f) else dp(10f)
            val content = if (isInterlude()) (textSize() * 0.9f).toInt() else built.height
            setMeasuredDimension(width, content + pad * 2)
        }

        private fun lineProgress(): Float {
            val l = lyrics ?: return 0f
            if (index < 0 || index >= l.lines.size) return 0f
            val start = l.lines[index].time
            val end = if (index + 1 < l.lines.size) l.lines[index + 1].time else start + 4000
            if (end <= start) return 1f
            return max(0f, min(1f, (currentMs() - start) / (end - start).toFloat()))
        }

        private fun glow(t: Float): Float = when {
            t < 0.15f -> t / 0.15f
            t < 0.6f -> 1f
            else -> max(0f, 1f - (t - 0.6f) / 0.4f)
        }

        override fun onDraw(canvas: Canvas) {
            val now = SystemClock.elapsedRealtime()
            val dt = if (lastFrame == 0L) 0.016f else (now - lastFrame) / 1000f
            lastFrame = now
            var animating = opacity.step(dt)
            animating = scale.step(dt) || animating
            val built = ensureLayout(width)
            val alpha = max(0f, min(1f, opacity.value))
            val baseAlpha = colorActive ushr 24
            val live = role == ROLE_ACTIVE && !plain
            val progress = if (live) lineProgress() else 0f
            val pad = if (plain) dp(6f) else dp(10f)
            canvas.save()
            canvas.translate(0f, pad.toFloat())
            canvas.scale(scale.value, scale.value, 0f, (height - pad * 2) / 2f)
            if (isInterlude()) {
                drawInterlude(canvas, live, progress, alpha, now)
            } else if (live) {
                val filled = ColorUtils.setAlphaComponent(colorActive, (alpha * baseAlpha).toInt())
                val unfilled = ColorUtils.setAlphaComponent(colorActive, (alpha * baseAlpha * unfilledAlpha).toInt())
                val h = built.height.toFloat()
                val fill = LinearGradient(0f, 0f, 0f, 1f, filled, unfilled, Shader.TileMode.CLAMP)
                fillMatrix.setScale(1f, max(1f, h * 0.2f))
                fillMatrix.postTranslate(0f, (progress * 1.2f - 0.2f) * h)
                fill.setLocalMatrix(fillMatrix)
                paint.shader = fill
                paint.color = -0x1
                val g = glow(progress)
                if (g > 0.01f) {
                    paint.setShadowLayer(dp(4f + 2f * g).toFloat(), 0f, 0f, ColorUtils.setAlphaComponent(colorActive, (g * 0.35f * baseAlpha).toInt()))
                } else {
                    paint.clearShadowLayer()
                }
                built.draw(canvas)
                paint.shader = null
                paint.clearShadowLayer()
            } else {
                paint.shader = null
                paint.clearShadowLayer()
                paint.color = ColorUtils.setAlphaComponent(colorActive, (alpha * baseAlpha).toInt())
                built.draw(canvas)
            }
            canvas.restore()
            if (animating || (live && !MediaController.getInstance().isMessagePaused)) {
                postInvalidateOnAnimation()
            } else {
                lastFrame = 0L
            }
        }

        private fun drawInterlude(canvas: Canvas, live: Boolean, progress: Float, alpha: Float, now: Long) {
            val size = textSize() * 0.32f
            val gap = size * 0.55f
            val cy = textSize() * 0.45f
            val breathe = if (live) 1f + 0.06f * sin(now / 1600.0 * Math.PI * 2).toFloat() else 1f
            canvas.save()
            canvas.scale(breathe, breathe, size * 1.5f + gap, cy)
            val baseAlpha = colorActive ushr 24
            for (i in 0 until 3) {
                val lit = if (live) max(0f, min(1f, progress * 3f - i)) else 0f
                val dotAlpha = alpha * (0.35f + 0.65f * lit)
                dotPaint.color = ColorUtils.setAlphaComponent(colorActive, (dotAlpha * baseAlpha).toInt())
                if (lit > 0f) {
                    dotPaint.setShadowLayer(dp(4f + 2f * lit).toFloat(), 0f, 0f, ColorUtils.setAlphaComponent(colorActive, (lit * 0.35f * baseAlpha).toInt()))
                } else {
                    dotPaint.clearShadowLayer()
                }
                val cx = size / 2f + i * (size + gap)
                canvas.drawCircle(cx, cy, size / 2f * (0.9f + 0.1f * lit), dotPaint)
            }
            canvas.restore()
        }
    }
}
