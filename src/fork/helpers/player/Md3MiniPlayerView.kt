package desu.inugram.helpers.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.CubicBezierInterpolator
import org.telegram.ui.MainTabsActivity

private fun dp(value: Float): Int = AndroidUtilities.dp(value)

class Md3MiniPlayerView(
    context: Context,
    val host: MainTabsActivity,
    private val resourcesProvider: Theme.ResourcesProvider?,
) : FrameLayout(context), NotificationCenter.NotificationCenterDelegate {

    companion object {
        const val HEIGHT_DP = 64
    }

    private var dark = Md3PlayerColors.isDark(resourcesProvider)
    private var seed = Md3PlayerColors.fallbackSeed(resourcesProvider)
    private var colors = Md3PlayerColors.fromSeed(seed, dark)
    private val card = LinearLayout(context)
    private val cardBg = GradientDrawable()
    private val cover = Md3CoverImage(context, 22f)
    private val titleView = TextView(context)
    private val artistView = TextView(context)
    private val playButton = Md3RingPlayButton(context, 20f, 3f, 22f, 1.3f, 12)
    private val nextButton = ImageView(context)
    private val closeButton = ImageView(context)
    private val nextIcon = Md3PlayerIcon.fill(Md3PlayerIcon.NEXT, 24f)
    private val closeIcon = Md3PlayerIcon.stroke(Md3PlayerIcon.CLOSE, 22f)
    private var showAnimator: ValueAnimator? = null
    private var current: MessageObject? = null
    private var shown = false
    private var showProgress = 0f
    private var hostFactor = 1f
    private var baseTranslation = 0f
    var offsetListener: (() -> Unit)? = null

    init {
        clipChildren = false
        clipToPadding = false

        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(10f), 0, dp(6f), 0)
        cardBg.cornerRadius = dp(20f).toFloat()
        card.background = cardBg
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(20f).toFloat())
            }
        }
        card.clipToOutline = true
        card.elevation = dp(6f).toFloat()
        card.setOnClickListener { Md3PlayerHelper.open(host, this) }
        card.contentDescription = LocaleController.getString(R.string.InuMd3PlayerOpen)
        addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(HEIGHT_DP.toFloat()), Gravity.BOTTOM).apply {
            leftMargin = dp(12f)
            rightMargin = dp(12f)
            bottomMargin = dp(12f)
        })

        cover.setRadius(dp(12f))
        card.addView(cover, LinearLayout.LayoutParams(dp(44f), dp(44f)))

        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        titleView.typeface = AndroidUtilities.bold()
        titleView.setSingleLine(true)
        titleView.ellipsize = TextUtils.TruncateAt.END
        texts.addView(titleView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        artistView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        artistView.setSingleLine(true)
        artistView.ellipsize = TextUtils.TruncateAt.END
        artistView.alpha = 0.8f
        texts.addView(artistView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2f) })
        card.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(12f)
            rightMargin = dp(4f)
        })

        playButton.setOnClickListener { Md3PlayerHelper.togglePlay() }
        card.addView(playButton, LinearLayout.LayoutParams(dp(48f), dp(48f)))

        nextButton.scaleType = ImageView.ScaleType.CENTER
        nextButton.setImageDrawable(nextIcon)
        nextButton.contentDescription = LocaleController.getString(R.string.Next)
        nextButton.setOnClickListener { MediaController.getInstance().playNextMessage() }
        card.addView(nextButton, LinearLayout.LayoutParams(dp(44f), dp(48f)))

        closeButton.scaleType = ImageView.ScaleType.CENTER
        closeButton.setImageDrawable(closeIcon)
        closeButton.contentDescription = LocaleController.getString(R.string.AccDescrClosePlayer)
        closeButton.setOnClickListener { MediaController.getInstance().cleanupPlayer(true, true) }
        card.addView(closeButton, LinearLayout.LayoutParams(dp(44f), dp(48f)))

        applyColors(colors)
        visibility = GONE
    }

    fun canTransition(): Boolean =
        isAttachedToWindow && visibility == VISIBLE && shown && showProgress >= 1f && hostFactor >= 0.99f && card.width > 0

    fun getCardRect(out: RectF) = locate(card, out)

    fun getCoverRect(out: RectF) = locate(cover, out)

    private fun locate(view: View, out: RectF) {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        out.set(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + view.width).toFloat(), (loc[1] + view.height).toFloat())
    }

    val cardRadius: Float get() = dp(20f).toFloat()

    val coverRadius: Float get() = dp(12f).toFloat()

    val cardColor: Int get() = colors.primaryContainer

    val coverBitmap: Bitmap? get() = cover.imageReceiver.bitmap

    fun captureCard(): Bitmap? {
        val w = card.width
        val h = card.height
        if (w <= 0 || h <= 0) return null
        val visibility = cover.visibility
        return try {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            cover.visibility = INVISIBLE
            card.isPressed = false
            card.jumpDrawablesToCurrentState()
            card.draw(canvas)
            bitmap
        } catch (e: Throwable) {
            null
        } finally {
            cover.visibility = visibility
        }
    }

    fun setTransitionHidden(hidden: Boolean) {
        card.alpha = if (hidden) 0f else 1f
    }

    val visibleOffset: Float get() = (dp(HEIGHT_DP.toFloat()) + dp(8f)) * showProgress * hostFactor

    val targetOffset: Int get() = if (shown && hostFactor > 0.5f) dp(HEIGHT_DP.toFloat()) + dp(8f) else 0

    fun setHostPosition(translation: Float, factor: Float) {
        baseTranslation = translation
        val old = hostFactor
        hostFactor = factor
        applyTransform()
        if (old != factor) notifyOffset()
    }

    private fun applyTransform() {
        val p = showProgress * hostFactor
        translationY = baseTranslation + dp(24f) * (1f - p)
        alpha = p
        visibility = if (p > 0f) VISIBLE else GONE
    }

    private fun notifyOffset() {
        offsetListener?.invoke()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            val nc = NotificationCenter.getInstance(a)
            nc.addObserver(this, NotificationCenter.messagePlayingDidStart)
            nc.addObserver(this, NotificationCenter.messagePlayingDidReset)
            nc.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged)
            nc.addObserver(this, NotificationCenter.messagePlayingProgressDidChanged)
            nc.addObserver(this, NotificationCenter.fileLoaded)
        }
        update(false)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            val nc = NotificationCenter.getInstance(a)
            nc.removeObserver(this, NotificationCenter.messagePlayingDidStart)
            nc.removeObserver(this, NotificationCenter.messagePlayingDidReset)
            nc.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged)
            nc.removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged)
            nc.removeObserver(this, NotificationCenter.fileLoaded)
        }
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        when (id) {
            NotificationCenter.messagePlayingProgressDidChanged -> playButton.progressChanged()
            NotificationCenter.fileLoaded -> {
                val mo = current
                if (mo?.document != null && TextUtils.equals(args.getOrNull(0) as? String, FileLoader.getAttachFileName(mo.document))) {
                    cover.bind(mo)
                }
            }
            else -> update(true)
        }
    }

    private fun checkThemeColors() {
        val themeDark = Md3PlayerColors.isDark(resourcesProvider)
        val themeSeed = Md3PlayerColors.fallbackSeed(resourcesProvider)
        if (themeDark != dark || themeSeed != seed) {
            dark = themeDark
            seed = themeSeed
            applyColors(Md3PlayerColors.fromSeed(seed, dark))
        }
    }

    fun update(animated: Boolean) {
        checkThemeColors()
        val mo = MediaController.getInstance().playingMessageObject
        val want = Md3PlayerHelper.enabled() && mo != null && mo.isMusic && mo.id != 0
        if (want && mo != null) bind(mo)
        playButton.setPlaying(want && !MediaController.getInstance().isMessagePaused, animated)
        setShown(want, animated)
    }

    private fun bind(mo: MessageObject) {
        current = mo
        titleView.text = mo.musicTitle
        artistView.text = mo.musicAuthor
        cover.bind(mo)
        playButton.setMessage(mo)
        playButton.contentDescription = LocaleController.getString(if (MediaController.getInstance().isMessagePaused) R.string.AccActionPlay else R.string.AccActionPause)
    }

    private fun setShown(value: Boolean, animated: Boolean) {
        val target = if (value) 1f else 0f
        if (shown == value && (showAnimator != null || showProgress == target)) return
        shown = value
        showAnimator?.cancel()
        showAnimator = null
        if (!animated || !isAttachedToWindow) {
            showProgress = target
            applyTransform()
            notifyOffset()
            return
        }
        showAnimator = ValueAnimator.ofFloat(showProgress, target).apply {
            addUpdateListener {
                showProgress = it.animatedValue as Float
                applyTransform()
                notifyOffset()
            }
            duration = if (value) 450L else 300L
            interpolator = if (value) CubicBezierInterpolator(0.05, 0.7, 0.1, 1.0) else CubicBezierInterpolator(0.3, 0.0, 0.8, 0.15)
            start()
        }
    }

    private fun applyColors(c: Md3PlayerColors) {
        colors = c
        cardBg.setColor(c.primaryContainer)
        if (Build.VERSION.SDK_INT >= 28) {
            card.outlineSpotShadowColor = c.shadow()
            card.outlineAmbientShadowColor = c.shadow()
        }
        cover.setColors(c.secondaryContainer, c.onSecondaryContainer)
        titleView.setTextColor(c.onPrimaryContainer)
        artistView.setTextColor(c.onPrimaryContainer)
        nextIcon.setColor(c.onPrimaryContainer)
        closeIcon.setColor(c.onPrimaryContainer)
        val ripple = ColorUtils.setAlphaComponent(c.onPrimaryContainer, 0x1f)
        nextButton.background = Theme.createSelectorDrawable(ripple, Theme.RIPPLE_MASK_CIRCLE_20DP)
        closeButton.background = Theme.createSelectorDrawable(ripple, Theme.RIPPLE_MASK_CIRCLE_20DP)
        playButton.background = Theme.createSelectorDrawable(ripple, Theme.RIPPLE_MASK_CIRCLE_20DP)
        playButton.setColors(c.onPrimaryContainer, ColorUtils.setAlphaComponent(c.onPrimaryContainer, 46), c.onPrimaryContainer)
        card.foreground = Theme.createSelectorDrawable(ripple, Theme.RIPPLE_MASK_ALL)
    }
}
