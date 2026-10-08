package desu.inugram.helpers.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AudioPlayerAlert
import org.telegram.ui.Components.Bulletin
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.LaunchActivity
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private fun dp(value: Float): Int = AndroidUtilities.dp(value)

private fun clamp01(v: Float): Float = max(0f, min(1f, v))

private fun str(res: Int): String = LocaleController.getString(res)

class Md3PlayerSheet(context: Context, private val rp: Theme.ResourcesProvider?) :
    BottomSheet(context, false, rp), NotificationCenter.NotificationCenterDelegate {

    companion object {
        @JvmField
        var instance: Md3PlayerSheet? = null

        private val SPEEDS = floatArrayOf(1f, 1.2f, 1.5f, 1.7f, 2f, 0.5f)
        private val EMPHASIZED = CubicBezierInterpolator(0.2, 0.0, 0.0, 1.0)

        private fun lerpRect(a: RectF, b: RectF, t: Float, out: RectF) {
            out.set(a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t, a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t)
        }

        private fun formatTime(seconds: Int): String =
            if (seconds >= 3600) String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            else String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }

    private val activity: LaunchActivity? = (AndroidUtilities.findActivity(context) as? LaunchActivity) ?: LaunchActivity.instance
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val playingAtStart: MessageObject? = MediaController.getInstance().playingMessageObject
    private val account = playingAtStart?.currentAccount ?: UserConfig.selectedAccount
    private val dark = Md3PlayerColors.isDark(rp)
    private val fallbackSeed = Md3PlayerColors.fallbackSeed(rp)
    private var colors = Md3PlayerColors.fromSeed(Md3PlayerArt.cachedSeed(playingAtStart) ?: fallbackSeed, dark)

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundRect = RectF()
    private val morphFrom = RectF()
    private val morphTo = RectF()
    private val morphCoverFrom = RectF()
    private val morphCoverTo = RectF()
    private val morphRect = RectF()
    private val morphCover = RectF()
    private val morphSrc = Rect()
    private val morphPath = Path()
    private val morphPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var miniSource: Md3MiniPlayerView? = null
    private var skipMorph = false
    private var closing = false
    private var detached = false
    private var morphAnimator: ValueAnimator? = null
    private var morphProgress = -1f
    private var morphFromRadius = 0f
    private var morphToRadius = 0f
    private var morphCoverFromRadius = 0f
    private var morphCoverToRadius = 0f
    private var morphFromColor = 0
    private var morphSnapshot: Bitmap? = null
    private var morphCoverBitmap: Bitmap? = null
    private var morphBarsState = -1
    private var underLightStatus = false
    private var underLightNav = false
    private var colorAnimator: ValueAnimator? = null
    private var current: MessageObject? = null
    private var currentKey: String? = null
    private var seeking = false
    private var seekProgress = 0f
    private var lightBars: Boolean? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragTracking = false
    private var dragActive = false
    private var coverAnimating = false

    private val bulletinDelegate = object : Bulletin.Delegate {
        override fun getBottomOffset(tag: Int): Int = getBottomInset()
    }

    private val root: FrameLayout = object : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.EXACTLY))
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (morphProgress >= 0f || closing) return true
            return super.dispatchTouchEvent(ev)
        }

        override fun setTranslationY(translationY: Float) {
            super.setTranslationY(translationY)
            invalidate()
        }

        override fun dispatchDraw(canvas: Canvas) {
            if (morphProgress < 0f) {
                super.dispatchDraw(canvas)
                return
            }
            drawMorphBackground(canvas)
            super.dispatchDraw(canvas)
            drawMorphForeground(canvas)
        }

        override fun onDraw(canvas: Canvas) {
            if (morphProgress >= 0f) return
            val r = dp(28f) * clamp01(translationY / dp(56f))
            backgroundPaint.color = colors.surface
            backgroundRect.set(0f, 0f, width.toFloat(), height + r)
            canvas.drawRoundRect(backgroundRect, r, r, backgroundPaint)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            Bulletin.addDelegate(this, bulletinDelegate)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            Bulletin.removeDelegate(this)
        }
    }

    private val layout = PlayerLayout(context)
    private val header = FrameLayout(context)
    private val collapseIcon = Md3PlayerIcon.stroke(Md3PlayerIcon.CHEVRON_DOWN, 24f)
    private val moreIcon = Md3PlayerIcon.fill(Md3PlayerIcon.MORE, 24f)
    private val collapseButton = ImageView(context)
    private val moreButton = ImageView(context)
    private val headerLabel = TextView(context)
    private val headerTitle = TextView(context)
    private val cover = Md3CoverImage(context, 120f)
    private val titleRow = LinearLayout(context)
    private val titleView = TextView(context)
    private val artistView = TextView(context)
    private val heartIcon = Md3PlayerIcon.stroke(Md3PlayerIcon.HEART, 24f)
    private val likeButton = Md3MorphButton(context, heartIcon)
    private val seekBar = Md3WavySeekBar(context)
    private val bubble = TextView(context)
    private val bubbleBg = GradientDrawable()
    private val timeRow = LinearLayout(context)
    private val timeNow = TextView(context)
    private val timeLeft = TextView(context)
    private val controls = Md3ControlsRow(context)
    private val group = LinearLayout(context)
    private val speedButton = Md3MorphButton(context, Md3PlayerIcon.stroke(Md3PlayerIcon.SPEED, 20f))
    private val queueButton = Md3MorphButton(context, Md3PlayerIcon.stroke(Md3PlayerIcon.QUEUE, 20f))

    init {
        occupyNavigationBar = true
        drawNavigationBar = false
        setApplyTopPadding(false)
        setApplyBottomPadding(false)
        currentAccount = account

        root.setWillNotDraw(false)
        containerView = root
        root.addView(layout, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        collapseButton.scaleType = ImageView.ScaleType.CENTER
        collapseButton.setImageDrawable(collapseIcon)
        collapseButton.contentDescription = str(R.string.Close)
        collapseButton.setOnClickListener { dismiss() }
        header.addView(collapseButton, FrameLayout.LayoutParams(dp(48f), dp(48f), Gravity.LEFT or Gravity.CENTER_VERTICAL))
        moreButton.scaleType = ImageView.ScaleType.CENTER
        moreButton.setImageDrawable(moreIcon)
        moreButton.contentDescription = str(R.string.AccDescrMoreOptions)
        moreButton.setOnClickListener { showMenu(it) }
        header.addView(moreButton, FrameLayout.LayoutParams(dp(48f), dp(48f), Gravity.RIGHT or Gravity.CENTER_VERTICAL))
        val headerCenter = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        headerLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
        headerLabel.letterSpacing = 0.08f
        headerLabel.isAllCaps = true
        headerLabel.setSingleLine(true)
        headerLabel.ellipsize = TextUtils.TruncateAt.END
        headerCenter.addView(headerLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        headerTitle.typeface = AndroidUtilities.bold()
        headerTitle.setSingleLine(true)
        headerTitle.ellipsize = TextUtils.TruncateAt.END
        headerCenter.addView(headerTitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(2f)
        })
        header.addView(headerCenter, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(56f)
            rightMargin = dp(56f)
        })
        layout.addView(header)

        cover.setRadius(dp(28f))
        cover.setListener(fallbackSeed) { mo, seed -> onSeed(mo, seed) }
        cover.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(28f).toFloat())
            }
        }
        cover.elevation = dp(14f).toFloat()
        cover.setOnTouchListener { v, e -> onCoverTouch(v, e) }
        layout.addView(cover)

        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26f)
        titleView.typeface = AndroidUtilities.bold()
        titleView.setSingleLine(true)
        titleView.ellipsize = TextUtils.TruncateAt.END
        titles.addView(titleView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        artistView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        artistView.setSingleLine(true)
        artistView.ellipsize = TextUtils.TruncateAt.END
        titles.addView(artistView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4f) })
        titleRow.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        likeButton.setRadius(dp(24f).toFloat(), false)
        likeButton.setOnClickListener { toggleProfile() }
        titleRow.addView(likeButton, LinearLayout.LayoutParams(dp(48f), dp(48f)).apply {
            gravity = Gravity.CENTER_VERTICAL
            leftMargin = dp(12f)
        })
        layout.addView(titleRow)

        seekBar.contentDescription = str(R.string.InuMd3PlayerSeek)
        seekBar.delegate = object : Md3WavySeekBar.Delegate {
            override fun onSeekStart() {
                seeking = true
                bubble.animate().cancel()
                bubble.visibility = View.VISIBLE
                bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start()
            }

            override fun onSeekMove(progress: Float) {
                seekProgress = progress
                updateTimes()
                layoutBubble()
            }

            override fun onSeekEnd(progress: Float, commit: Boolean) {
                seeking = false
                val mo = current
                if (commit && mo != null) {
                    MediaController.getInstance().seekToProgress(mo, progress)
                    mo.audioProgress = progress
                    mo.audioProgressSec = (mo.duration * progress).toInt()
                }
                bubble.animate().cancel()
                bubble.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(150).withEndAction { bubble.visibility = View.GONE }.start()
                updateProgress()
            }
        }
        layout.addView(seekBar)

        timeRow.orientation = LinearLayout.HORIZONTAL
        timeNow.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        timeNow.fontFeatureSettings = "tnum"
        timeRow.addView(timeNow, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        timeLeft.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        timeLeft.fontFeatureSettings = "tnum"
        timeLeft.gravity = Gravity.RIGHT
        timeRow.addView(timeLeft, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        layout.addView(timeRow)

        controls.shuffle.setOnClickListener {
            MediaController.getInstance().setPlaybackOrderType(if (SharedConfig.shuffleMusic) 0 else 2)
            updateModes(true)
        }
        controls.repeat.setOnClickListener {
            val mode = SharedConfig.repeatMode
            SharedConfig.setRepeatMode(if (mode == 0) 1 else if (mode == 1) 2 else 0)
            updateModes(true)
        }
        controls.prev.setOnClickListener { MediaController.getInstance().playPreviousMessage() }
        controls.next.setOnClickListener { MediaController.getInstance().playNextMessage() }
        controls.play.setOnClickListener { Md3PlayerHelper.togglePlay() }
        controls.shuffle.contentDescription = str(R.string.ShuffleList)
        controls.prev.contentDescription = str(R.string.AccDescrPrevious)
        controls.next.contentDescription = str(R.string.Next)
        layout.addView(controls)

        group.orientation = LinearLayout.HORIZONTAL
        speedButton.setRadius(dp(26f).toFloat(), dp(8f).toFloat(), false)
        speedButton.setOnClickListener { cycleSpeed() }
        group.addView(speedButton, LinearLayout.LayoutParams(0, dp(52f), 1f))
        queueButton.setText(str(R.string.InuMd3PlayerQueue))
        queueButton.setRadius(dp(8f).toFloat(), dp(26f).toFloat(), false)
        queueButton.setOnClickListener { openClassic() }
        group.addView(queueButton, LinearLayout.LayoutParams(0, dp(52f), 1f).apply { leftMargin = dp(4f) })
        layout.addView(group)

        bubble.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        bubble.typeface = AndroidUtilities.bold()
        bubble.fontFeatureSettings = "tnum"
        bubble.setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        bubbleBg.cornerRadius = dp(16f).toFloat()
        bubble.background = bubbleBg
        bubble.visibility = View.GONE
        bubble.alpha = 0f
        layout.addView(bubble)

        applyColors(colors)
        playingAtStart?.let { bind(it, false) }
    }

    override fun canDismissWithSwipe(): Boolean = morphProgress < 0f && !closing

    override fun show() {
        super.show()
        instance = this
        val nc = NotificationCenter.getInstance(account)
        nc.addObserver(this, NotificationCenter.messagePlayingDidReset)
        nc.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged)
        nc.addObserver(this, NotificationCenter.messagePlayingDidStart)
        nc.addObserver(this, NotificationCenter.messagePlayingProgressDidChanged)
        nc.addObserver(this, NotificationCenter.fileLoaded)
        nc.addObserver(this, NotificationCenter.musicIdsLoaded)
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.messagePlayingSpeedChanged)
        applySystemBars(true)
    }

    fun setTransitionSource(source: Md3MiniPlayerView?) {
        miniSource = source
    }

    fun dismissImmediately() {
        skipMorph = true
        dismiss()
    }

    override fun dismiss() {
        if (closing) return
        val mini = miniSource
        if (!skipMorph && mini != null && !isDismissed) {
            if (morphProgress >= 0f) {
                closing = true
                detach()
                startMorph(morphProgress, 0f, 300L) { finishMorphClose() }
                return
            }
            val ty = root.translationY
            root.translationY = 0f
            if (prepareMorph()) {
                closing = true
                detach()
                cancelSheetAnimation()
                morphTo.set(0f, ty, root.width.toFloat(), root.height + ty)
                morphToRadius = dp(28f) * clamp01(ty / dp(56f))
                morphCoverTo.offset(0f, ty)
                startMorph(1f, 0f, 380L) { finishMorphClose() }
                return
            }
            root.translationY = ty
        }
        detach()
        morphAnimator?.let {
            it.removeAllListeners()
            it.cancel()
        }
        morphAnimator = null
        if (morphProgress >= 0f) {
            cover.visibility = View.VISIBLE
            clearMorph()
        }
        miniSource?.setTransitionHidden(false)
        super.dismiss()
    }

    private fun finishMorphClose() {
        miniSource?.setTransitionHidden(false)
        root.visibility = View.INVISIBLE
        backDrawable.setAlpha(0)
        skipDismissAnimation()
        super.dismiss()
    }

    override fun dismissInternal() {
        miniSource?.setTransitionHidden(false)
        detach()
        super.dismissInternal()
    }

    override fun onCustomOpenAnimation(): Boolean {
        root.translationY = 0f
        if (!prepareMorph()) return false
        morphTo.set(0f, 0f, root.width.toFloat(), root.height.toFloat())
        morphToRadius = 0f
        startMorph(0f, 1f, 420L) {
            clearMorph()
            cover.visibility = View.VISIBLE
            cover.translationZ = -cover.elevation
            cover.animate().translationZ(0f).setDuration(250).start()
            morphBarsState = -1
            applySystemBars(true)
            onOpenAnimationEnd()
            delegate?.onOpenAnimationEnd()
        }
        return true
    }

    private fun rectInRoot(view: View, out: RectF) {
        var x = 0f
        var y = 0f
        var v: View? = view
        while (v != null && v !== root) {
            x += v.left + v.translationX
            y += v.top + v.translationY
            v = v.parent as? View
        }
        val px = view.pivotX
        val py = view.pivotY
        val sx = view.scaleX
        val sy = view.scaleY
        out.set(x + px * (1f - sx), y + py * (1f - sy), x + px + (view.width - px) * sx, y + py + (view.height - py) * sy)
    }

    private fun prepareMorph(): Boolean {
        val mini = miniSource
        if (skipMorph || mini == null || !mini.canTransition() || root.width == 0 || !root.isAttachedToWindow) return false
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        mini.getCardRect(morphFrom)
        morphFrom.offset(-loc[0].toFloat(), -loc[1].toFloat())
        mini.getCoverRect(morphCoverFrom)
        morphCoverFrom.offset(-loc[0].toFloat(), -loc[1].toFloat())
        morphFromRadius = mini.cardRadius
        morphCoverFromRadius = mini.coverRadius
        morphFromColor = mini.cardColor
        recycleSnapshot()
        morphSnapshot = mini.captureCard()
        morphCoverBitmap = cover.imageReceiver.bitmap ?: mini.coverBitmap
        rectInRoot(cover, morphCoverTo)
        morphCoverToRadius = dp(28f) * cover.scaleX
        val decor = activity?.window?.decorView
        if (decor != null) {
            @Suppress("DEPRECATION")
            val flags = decor.systemUiVisibility
            underLightStatus = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR != 0
            underLightNav = Build.VERSION.SDK_INT >= 26 && flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR != 0
        }
        morphBarsState = -1
        mini.setTransitionHidden(true)
        cover.visibility = View.INVISIBLE
        layout.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        return true
    }

    private fun startMorph(from: Float, to: Float, duration: Long, onEnd: () -> Unit) {
        morphAnimator?.let {
            it.removeAllListeners()
            it.cancel()
        }
        morphProgress = from
        applyMorph()
        morphAnimator = ValueAnimator.ofFloat(from, to).apply {
            addUpdateListener {
                morphProgress = it.animatedValue as Float
                applyMorph()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    morphAnimator = null
                    onEnd()
                }
            })
            this.duration = duration
            interpolator = EMPHASIZED
            start()
        }
    }

    private fun applyMorph() {
        val p = morphProgress
        lerpRect(morphFrom, morphTo, p, morphRect)
        val s = morphRect.width() / max(1, root.width)
        layout.pivotX = 0f
        layout.pivotY = 0f
        layout.scaleX = s
        layout.scaleY = s
        layout.translationX = morphRect.left
        layout.translationY = morphRect.top
        layout.alpha = clamp01((p - 0.35f) / 0.65f)
        backDrawable.setAlpha(if (dimBehind) (dimBehindAlpha * clamp01(p)).toInt() else 0)
        updateMorphBars()
        root.invalidate()
    }

    private fun clearMorph() {
        morphProgress = -1f
        layout.scaleX = 1f
        layout.scaleY = 1f
        layout.translationX = 0f
        layout.translationY = 0f
        layout.alpha = 1f
        layout.setLayerType(View.LAYER_TYPE_NONE, null)
        recycleSnapshot()
        morphCoverBitmap = null
        root.invalidate()
    }

    private fun recycleSnapshot() {
        morphSnapshot?.recycle()
        morphSnapshot = null
    }

    private fun updateMorphBars() {
        val state = if (morphProgress > 0.5f) 1 else 0
        if (state == morphBarsState) return
        morphBarsState = state
        if (state == 1) {
            val light = colors.lightStatusBar()
            setBars(light, light)
        } else {
            setBars(underLightStatus, underLightNav)
        }
    }

    private fun drawMorphBackground(canvas: Canvas) {
        val p = morphProgress
        lerpRect(morphFrom, morphTo, p, morphRect)
        val r = morphFromRadius + (morphToRadius - morphFromRadius) * p
        morphPath.rewind()
        morphPath.addRoundRect(morphRect, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(morphPath)
        backgroundPaint.color = ColorUtils.blendARGB(morphFromColor, colors.surface, clamp01(p / 0.5f))
        canvas.drawRect(morphRect, backgroundPaint)
    }

    private fun drawMorphForeground(canvas: Canvas) {
        val p = morphProgress
        val snapshot = morphSnapshot
        if (snapshot != null && !snapshot.isRecycled) {
            val a = 1f - clamp01(p / 0.3f)
            if (a > 0f) {
                canvas.save()
                val s = morphRect.width() / snapshot.width
                canvas.translate(morphRect.left, morphRect.top)
                canvas.scale(s, s)
                morphPaint.alpha = (255 * a).toInt()
                canvas.drawBitmap(snapshot, 0f, 0f, morphPaint)
                canvas.restore()
            }
        }
        canvas.restore()
        lerpRect(morphCoverFrom, morphCoverTo, p, morphCover)
        val r = morphCoverFromRadius + (morphCoverToRadius - morphCoverFromRadius) * p
        morphPath.rewind()
        morphPath.addRoundRect(morphCover, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(morphPath)
        val bitmap = morphCoverBitmap
        if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            val bw = bitmap.width
            val bh = bitmap.height
            val ratio = morphCover.width() / max(1f, morphCover.height())
            if (bw / bh.toFloat() > ratio) {
                val w = (bh * ratio).toInt()
                val x = (bw - w) / 2
                morphSrc.set(x, 0, x + w, bh)
            } else {
                val h = (bw / ratio).toInt()
                val y = (bh - h) / 2
                morphSrc.set(0, y, bw, y + h)
            }
            morphPaint.alpha = 255
            canvas.drawBitmap(bitmap, morphSrc, morphCover, morphPaint)
        } else {
            backgroundPaint.color = colors.primaryContainer
            canvas.drawRect(morphCover, backgroundPaint)
        }
        canvas.restore()
    }

    private fun detach() {
        if (detached) return
        detached = true
        if (instance === this) instance = null
        val nc = NotificationCenter.getInstance(account)
        nc.removeObserver(this, NotificationCenter.messagePlayingDidReset)
        nc.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged)
        nc.removeObserver(this, NotificationCenter.messagePlayingDidStart)
        nc.removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged)
        nc.removeObserver(this, NotificationCenter.fileLoaded)
        nc.removeObserver(this, NotificationCenter.musicIdsLoaded)
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.messagePlayingSpeedChanged)
        colorAnimator?.cancel()
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        when (id) {
            NotificationCenter.messagePlayingDidStart, NotificationCenter.messagePlayingDidReset -> {
                val mo = MediaController.getInstance().playingMessageObject
                if (mo == null || !mo.isMusic) dismissImmediately() else bind(mo, true)
            }
            NotificationCenter.messagePlayingPlayStateChanged -> {
                updatePlayState(true)
                updateProgress()
            }
            NotificationCenter.messagePlayingProgressDidChanged -> updateProgress()
            NotificationCenter.fileLoaded -> {
                val mo = current
                if (mo?.document != null && TextUtils.equals(args.getOrNull(0) as? String, FileLoader.getAttachFileName(mo.document))) {
                    cover.bind(mo)
                }
            }
            NotificationCenter.musicIdsLoaded -> updateLike(true)
            NotificationCenter.messagePlayingSpeedChanged -> updateSpeed()
        }
    }

    private fun bind(mo: MessageObject, animated: Boolean) {
        val key = Md3PlayerArt.key(mo)
        val changed = !TextUtils.equals(key, currentKey)
        current = mo
        currentKey = key
        titleView.text = mo.musicTitle
        artistView.text = mo.musicAuthor
        cover.bind(mo)
        if (changed) {
            val seed = Md3PlayerArt.cachedSeed(mo)
            if (seed != null) {
                animateColors(Md3PlayerColors.fromSeed(seed, dark), animated)
            } else if (Md3PlayerArt.fileCover(mo) == null && Md3PlayerArt.fullLocation(mo) == null && Md3PlayerArt.thumbLocation(mo) == null) {
                animateColors(Md3PlayerColors.fromSeed(fallbackSeed, dark), animated)
            }
        }
        updateHeader()
        updateLike(animated)
        updateModes(animated)
        updateSpeed()
        updatePlayState(animated)
        updateProgress()
    }

    private fun onSeed(mo: MessageObject, seed: Int) {
        if (mo === current) animateColors(Md3PlayerColors.fromSeed(seed, dark), true)
    }

    private fun updateHeader() {
        val mo = current ?: return
        val saved = MediaController.getInstance().currentSavedMusicList
        if (saved != null) {
            headerLabel.text = str(R.string.InuMd3PlayerFromProfile)
            headerTitle.text = DialogObject.getShortName(saved.currentAccount, saved.dialogId)
        } else if (MediaController.getInstance().currentPlaylistIsGlobalSearch()) {
            headerLabel.text = str(R.string.InuMd3PlayerFromSearch)
            headerTitle.text = str(R.string.AttachMusic)
        } else {
            val did = mo.dialogId
            headerLabel.text = str(R.string.InuMd3PlayerFromChat)
            headerTitle.text = if (did == UserConfig.getInstance(account).getClientUserId()) str(R.string.SavedMessages) else DialogObject.getName(account, did)
        }
    }

    private fun updateLike(animated: Boolean) {
        val mo = current
        val visible = mo?.document != null && !Md3PlayerActions.noForwards(mo)
        likeButton.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible || mo == null) return
        val saved = Md3PlayerActions.isSavedToProfile(mo)
        likeButton.setActive(saved, animated)
        likeButton.setRadius(dp(if (saved) 14f else 24f).toFloat(), animated)
        heartIcon.setFillStroke(saved)
        likeButton.contentDescription = str(if (saved) R.string.ProfilePlaylistRemoveFromProfile else R.string.AudioSaveToMyProfile)
    }

    private fun toggleProfile() {
        val mo = current ?: return
        if (Md3PlayerActions.isProfileSavePending(mo)) return
        val save = !Md3PlayerActions.isSavedToProfile(mo)
        likeButton.setActive(save, true)
        likeButton.setRadius(dp(if (save) 14f else 24f).toFloat(), true)
        heartIcon.setFillStroke(save)
        Md3PlayerActions.saveToProfile(mo, save) { error ->
            val provider = colors.provider(rp)
            if (error != null) {
                updateLike(true)
                BulletinFactory.of(root, provider).showForError(error)
                return@saveToProfile
            }
            val list = MediaController.getInstance().currentSavedMusicList
            if (!save && list != null && list.dialogId == UserConfig.getInstance(mo.currentAccount).getClientUserId()) {
                list.remove(mo)
                if (list.list.isEmpty()) {
                    MediaController.getInstance().cleanupPlayer(true, true)
                    dismissImmediately()
                    return@saveToProfile
                }
                NotificationCenter.getInstance(mo.currentAccount).postNotificationName(NotificationCenter.musicListLoaded, list)
            }
            updateLike(true)
            BulletinFactory.of(root, provider).createSimpleBulletin(
                if (save) R.raw.saved_messages else R.raw.ic_delete,
                str(if (save) R.string.AudioSaveToMyProfileSaved else R.string.AudioSaveToMyProfileUnsaved),
            ).show()
        }
    }

    private fun updateModes(animated: Boolean) {
        val mode = SharedConfig.repeatMode
        controls.setModes(SharedConfig.shuffleMusic, mode == 1 || mode == 2, mode == 2, animated)
        controls.repeat.contentDescription = str(if (mode == 2) R.string.AccDescrRepeatOne else if (mode == 1) R.string.AccDescrRepeatList else R.string.AccDescrRepeatOff)
    }

    private fun updateSpeed() {
        val speed = MediaController.getInstance().getPlaybackSpeed(true)
        val locale = LocaleController.getInstance().getCurrentLocale() ?: Locale.getDefault()
        val label = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(locale)).format(speed.toDouble()) + "×"
        speedButton.setText(label)
        speedButton.contentDescription = str(R.string.InuMd3PlayerSpeed) + " " + label
    }

    private fun cycleSpeed() {
        val speed = MediaController.getInstance().getPlaybackSpeed(true)
        val index = SPEEDS.indexOfFirst { abs(it - speed) < 0.01f }
        val next = if (index < 0) 1f else SPEEDS[(index + 1) % SPEEDS.size]
        MediaController.getInstance().setPlaybackSpeed(true, next)
        updateSpeed()
    }

    private fun updatePlayState(animated: Boolean) {
        val playing = !MediaController.getInstance().isMessagePaused
        controls.setPlaying(playing, animated)
        controls.play.contentDescription = str(if (playing) R.string.AccActionPause else R.string.AccActionPlay)
        seekBar.setPlaying(playing)
        val scale = if (playing) 1f else 0.92f
        if (cover.scaleX != scale) {
            if (animated) {
                cover.animate().scaleX(scale).scaleY(scale).setDuration(450).setInterpolator(EMPHASIZED).start()
            } else {
                cover.scaleX = scale
                cover.scaleY = scale
            }
        }
    }

    private fun displayProgress(): Float = if (seeking) seekProgress else current?.audioProgress ?: 0f

    private fun updateProgress() {
        val mo = current ?: return
        if (!seeking) seekBar.setProgress(mo.audioProgress)
        updateTimes()
    }

    private fun updateTimes() {
        val mo = current ?: return
        val duration = mo.duration.roundToInt()
        val now = if (seeking) (duration * seekProgress).toInt() else min(duration, mo.audioProgressSec)
        val a = formatTime(now)
        val b = "−" + formatTime(max(0, duration - now))
        if (!TextUtils.equals(timeNow.text, a)) {
            timeNow.text = a
            bubble.text = a
        }
        if (!TextUtils.equals(timeLeft.text, b)) timeLeft.text = b
    }

    private fun layoutBubble() {
        if (bubble.width == 0) {
            bubble.post { layoutBubble() }
            return
        }
        val left = dp(3f).toFloat()
        val right = (seekBar.width - dp(3f)).toFloat()
        val x = seekBar.left + left + displayProgress() * (right - left)
        val tx = x - bubble.left - bubble.width / 2f
        val minX = -bubble.left + dp(8f).toFloat()
        val maxX = (layout.width - bubble.left - bubble.width - dp(8f)).toFloat()
        bubble.translationX = max(minX, min(maxX, tx))
    }

    private fun openClassic() {
        val a = activity
        dismissImmediately()
        if (a != null) AudioPlayerAlert(a, rp).show()
    }

    private fun showMenu(anchor: View) {
        val mo = current ?: return
        val a = activity ?: return
        val noForwards = Md3PlayerActions.noForwards(mo)
        val provider = colors.provider(rp)
        val o = ItemOptions.makeOptions(container, provider, anchor, true)
        if (!noForwards) {
            val sub = o.makeSwipeback()
            sub.add(R.drawable.ic_ab_back, str(R.string.Back)) { o.closeSwipeback() }
            sub.addGap()
            sub.addIf(!Md3PlayerActions.isSavedToProfile(mo), R.drawable.left_status_profile, str(R.string.AudioSaveToMyProfile)) {
                o.dismiss()
                toggleProfile()
            }
            sub.addIf(mo.id > 0, R.drawable.msg_saved, str(R.string.AudioSaveToSavedMessages)) {
                o.dismiss()
                Md3PlayerActions.saveToSavedMessages(mo)
            }
            sub.add(R.drawable.menu_download_round, str(R.string.AudioSaveToMusicFolder)) {
                o.dismiss()
                Md3PlayerActions.saveToMusic(a, mo, root, provider)
            }
            o.add(R.drawable.msg_stories_save, str(R.string.AudioSaveTo)) { o.openSwipeback(sub) }
            o.getLast()?.setRightIcon(R.drawable.msg_arrowright)
        } else {
            o.add(R.drawable.menu_download_round, str(R.string.AudioSaveToMusicFolder)) {
                o.dismiss()
                Md3PlayerActions.saveToMusic(a, mo, root, provider)
            }
        }
        o.add(R.drawable.msg_shareout, str(R.string.ShareFile)) {
            o.dismiss()
            Md3PlayerActions.share(a, mo)
        }
        o.addIf(mo.id > 0, R.drawable.msg_message, str(R.string.ShowInChat)) {
            o.dismiss()
            dismissImmediately()
            Md3PlayerActions.showInChat(a, mo)
        }
        o.addGap()
        o.add(R.drawable.msg_filled_data_music, str(R.string.InuMd3PlayerClassic)) {
            o.dismiss()
            openClassic()
        }
        o.setGravity(if (LocaleController.isRTL) Gravity.LEFT else Gravity.RIGHT)
        o.show()
    }

    private fun onCoverTouch(v: View, e: MotionEvent): Boolean {
        if (coverAnimating) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = e.rawX
                dragStartY = e.rawY
                dragTracking = true
                dragActive = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragTracking) return false
                var dx = e.rawX - dragStartX
                val dy = e.rawY - dragStartY
                if (!dragActive) {
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.2f) {
                        dragActive = true
                        dragStartX = e.rawX
                        dx = 0f
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (abs(dy) > touchSlop) {
                        dragTracking = false
                        return false
                    }
                }
                if (dragActive) setCoverDrag(dx)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = e.rawX - dragStartX
                val wasActive = dragActive
                dragTracking = false
                dragActive = false
                if (wasActive) finishCoverDrag(if (e.actionMasked == MotionEvent.ACTION_UP) dx else 0f)
                return true
            }
        }
        return false
    }

    private fun setCoverDrag(dx: Float) {
        cover.translationX = dx
        cover.rotation = dx / dp(40f)
        cover.alpha = 1f - min(abs(dx) / dp(420f), 0.4f)
    }

    private fun finishCoverDrag(dx: Float) {
        if (abs(dx) < dp(70f)) {
            cover.animate().translationX(0f).rotation(0f).alpha(1f).setDuration(450).setInterpolator(EMPHASIZED).start()
            return
        }
        val next = dx < 0
        val out = (if (next) -1 else 1) * layout.width.toFloat()
        coverAnimating = true
        cover.animate().translationX(out).rotation(out / dp(40f) / 4f).alpha(0f).setDuration(180).setInterpolator(CubicBezierInterpolator.EASE_IN).withEndAction {
            if (next) {
                MediaController.getInstance().playNextMessage()
            } else {
                MediaController.getInstance().playingMessageObject?.audioProgressSec = 0
                MediaController.getInstance().playPreviousMessage()
            }
            cover.translationX = -out * 0.35f
            cover.rotation = 0f
            cover.animate().translationX(0f).alpha(1f).setDuration(450).setInterpolator(EMPHASIZED).withEndAction { coverAnimating = false }.start()
        }.start()
    }

    private fun animateColors(target: Md3PlayerColors, animated: Boolean) {
        colorAnimator?.cancel()
        colorAnimator = null
        if (!animated) {
            applyColors(target)
            return
        }
        val from = colors
        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            addUpdateListener { applyColors(Md3PlayerColors.lerp(from, target, it.animatedValue as Float)) }
            duration = 600
            interpolator = EMPHASIZED
            start()
        }
    }

    private fun applyColors(c: Md3PlayerColors) {
        colors = c
        root.invalidate()
        collapseIcon.setColor(c.onSurface)
        moreIcon.setColor(c.onSurface)
        collapseButton.background = Theme.createSelectorDrawable(c.ripple(), Theme.RIPPLE_MASK_CIRCLE_20DP)
        moreButton.background = Theme.createSelectorDrawable(c.ripple(), Theme.RIPPLE_MASK_CIRCLE_20DP)
        headerLabel.setTextColor(c.onSurfaceVariant)
        headerTitle.setTextColor(c.onSurface)
        cover.setColors(c.primaryContainer, c.onPrimaryContainer)
        if (Build.VERSION.SDK_INT >= 28) {
            cover.outlineSpotShadowColor = c.shadow()
            cover.outlineAmbientShadowColor = c.shadow()
        }
        titleView.setTextColor(c.onSurface)
        artistView.setTextColor(c.onSurfaceVariant)
        likeButton.setColors(0, c.onSurfaceVariant, c.primaryContainer, c.onPrimaryContainer)
        seekBar.setColors(c.primary, c.secondaryContainer)
        timeNow.setTextColor(c.onSurfaceVariant)
        timeLeft.setTextColor(c.onSurfaceVariant)
        bubbleBg.setColor(c.onSurface)
        bubble.setTextColor(c.surface)
        controls.shuffle.setColors(0, c.onSurfaceVariant, c.secondaryContainer, c.onSecondaryContainer)
        controls.repeat.setColors(0, c.onSurfaceVariant, c.secondaryContainer, c.onSecondaryContainer)
        controls.repeat.setBadgeColors(c.primary, c.onPrimary)
        controls.prev.setColors(c.primaryContainer, c.onPrimaryContainer, c.primaryContainer, c.onPrimaryContainer)
        controls.next.setColors(c.primaryContainer, c.onPrimaryContainer, c.primaryContainer, c.onPrimaryContainer)
        controls.play.setColors(c.primary, c.onPrimary, c.primary, c.onPrimary)
        speedButton.setColors(c.surfaceHigh, c.onSurface, c.surfaceHigh, c.onSurface)
        queueButton.setColors(c.surfaceHigh, c.onSurface, c.surfaceHigh, c.onSurface)
        applySystemBars(false)
    }

    private fun applySystemBars(force: Boolean) {
        if (morphProgress >= 0f || closing) return
        val light = colors.lightStatusBar()
        if (!force && lightBars == light) return
        lightBars = light
        setBars(light, light)
    }

    private fun setBars(lightStatus: Boolean, lightNav: Boolean) {
        window?.let {
            AndroidUtilities.setLightStatusBar(it, lightStatus)
            AndroidUtilities.setLightNavigationBar(this, lightNav)
        }
        if (Build.VERSION.SDK_INT >= 26) {
            AndroidUtilities.setLightStatusBar(container as View, lightStatus)
            AndroidUtilities.setLightNavigationBar(container as View, lightNav)
        }
    }

    private inner class PlayerLayout(context: Context) : ViewGroup(context) {
        private var coverSize = 0

        init {
            clipChildren = false
            clipToPadding = false
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            val top = getStatusBarHeight()
            val bottom = getBottomInset()
            val side = dp(24f)
            val contentW = max(0, width - side * 2)
            val exactW = MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY)
            val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            header.measure(MeasureSpec.makeMeasureSpec(contentW + dp(24f), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(56f), MeasureSpec.EXACTLY))
            seekBar.measure(exactW, MeasureSpec.makeMeasureSpec(dp(40f), MeasureSpec.EXACTLY))
            timeRow.measure(exactW, unspecified)
            controls.measure(exactW, unspecified)
            group.measure(exactW, MeasureSpec.makeMeasureSpec(dp(52f), MeasureSpec.EXACTLY))
            titleRow.measure(exactW, unspecified)
            bubble.measure(unspecified, unspecified)
            val fixed = dp(56f) + dp(6f) + dp(40f) + timeRow.measuredHeight + dp(12f) + controls.measuredHeight + dp(52f) + dp(28f) + dp(16f)
            val available = height - top - bottom - fixed
            coverSize = max(dp(96f), min(contentW, available - dp(16f) - dp(26f) - titleRow.measuredHeight))
            val coverSpec = MeasureSpec.makeMeasureSpec(coverSize, MeasureSpec.EXACTLY)
            cover.measure(coverSpec, coverSpec)
            setMeasuredDimension(width, height)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val width = r - l
            val height = b - t
            val bottom = getBottomInset()
            val side = dp(24f)
            val contentW = max(0, width - side * 2)
            var y = getStatusBarHeight()
            header.layout(side - dp(12f), y, side - dp(12f) + header.measuredWidth, y + dp(56f))
            y += dp(56f)
            val coverLeft = (width - coverSize) / 2
            cover.layout(coverLeft, y + dp(16f), coverLeft + coverSize, y + dp(16f) + coverSize)
            val titleTop = y + dp(16f) + coverSize + dp(26f)
            titleRow.layout(side, titleTop, side + contentW, titleTop + titleRow.measuredHeight)
            y = titleTop + titleRow.measuredHeight + dp(6f)
            seekBar.layout(side, y, side + contentW, y + dp(40f))
            val bubbleTop = y - dp(36f)
            bubble.layout(side, bubbleTop, side + bubble.measuredWidth, bubbleTop + bubble.measuredHeight)
            y += dp(40f)
            timeRow.layout(side, y, side + contentW, y + timeRow.measuredHeight)
            y += timeRow.measuredHeight + dp(12f)
            controls.layout(side, y, side + contentW, y + controls.measuredHeight)
            val groupTop = height - bottom - dp(28f) - dp(52f)
            group.layout(side, groupTop, side + contentW, groupTop + dp(52f))
            if (seeking) layoutBubble()
        }
    }
}
