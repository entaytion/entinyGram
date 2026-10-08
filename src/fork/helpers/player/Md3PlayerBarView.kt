package desu.inugram.helpers.player

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
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

private fun dp(value: Float): Int = AndroidUtilities.dp(value)

class Md3PlayerBarView(
    context: Context,
    resourcesProvider: Theme.ResourcesProvider?,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) : FrameLayout(context), NotificationCenter.NotificationCenterDelegate {

    private val cover = Md3CoverImage(context, 18f)
    private val titleView = TextView(context)
    private val artistView = TextView(context)
    private val playButton = Md3RingPlayButton(context, 15f, 2.5f, 16f, 1.1f, 10)
    private val nextButton = ImageView(context)
    private val closeButton = ImageView(context)
    private val nextIcon = Md3PlayerIcon.fill(Md3PlayerIcon.NEXT, 22f)
    private val closeIcon = Md3PlayerIcon.stroke(Md3PlayerIcon.CLOSE, 20f)
    private var current: MessageObject? = null

    init {
        val theme = Md3PlayerColors.fromSeed(Md3PlayerColors.fallbackSeed(resourcesProvider), Md3PlayerColors.isDark(resourcesProvider))
        background = Theme.getSelectorDrawable(false)
        setOnClickListener { onOpen() }
        contentDescription = LocaleController.getString(R.string.InuMd3PlayerOpen)

        cover.setRadius(dp(10f))
        addView(cover, FrameLayout.LayoutParams(dp(36f), dp(36f), Gravity.LEFT or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(12f) })

        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        titleView.typeface = AndroidUtilities.bold()
        titleView.setSingleLine(true)
        titleView.ellipsize = TextUtils.TruncateAt.END
        texts.addView(titleView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        artistView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        artistView.setSingleLine(true)
        artistView.ellipsize = TextUtils.TruncateAt.END
        texts.addView(artistView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(1f) })
        addView(texts, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.LEFT or Gravity.CENTER_VERTICAL).apply {
            leftMargin = dp(60f)
            rightMargin = dp(6f + 40f * 3 + 4f)
        })

        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        playButton.setOnClickListener { Md3PlayerHelper.togglePlay() }
        buttons.addView(playButton, LinearLayout.LayoutParams(dp(40f), dp(40f)))
        nextButton.scaleType = ImageView.ScaleType.CENTER
        nextButton.setImageDrawable(nextIcon)
        nextButton.contentDescription = LocaleController.getString(R.string.Next)
        nextButton.setOnClickListener { MediaController.getInstance().playNextMessage() }
        buttons.addView(nextButton, LinearLayout.LayoutParams(dp(40f), dp(40f)))
        closeButton.scaleType = ImageView.ScaleType.CENTER
        closeButton.setImageDrawable(closeIcon)
        closeButton.contentDescription = LocaleController.getString(R.string.AccDescrClosePlayer)
        closeButton.setOnClickListener { onClose() }
        buttons.addView(closeButton, LinearLayout.LayoutParams(dp(40f), dp(40f)))
        addView(buttons, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(40f), Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(6f) })

        val text = Theme.getColor(Theme.key_inappPlayerPerformer, resourcesProvider)
        val secondary = Theme.getColor(Theme.key_inappPlayerTitle, resourcesProvider)
        val icons = Theme.getColor(Theme.key_inappPlayerClose, resourcesProvider)
        titleView.setTextColor(text)
        artistView.setTextColor(ColorUtils.setAlphaComponent(secondary, 0xbf))
        nextIcon.setColor(icons)
        closeIcon.setColor(icons)
        val ripple = icons and 0x19ffffff
        nextButton.background = Theme.createSelectorDrawable(ripple, Theme.RIPPLE_MASK_CIRCLE_20DP)
        closeButton.background = Theme.createSelectorDrawable(ripple, Theme.RIPPLE_MASK_CIRCLE_20DP)
        playButton.background = Theme.createSelectorDrawable(ripple, Theme.RIPPLE_MASK_CIRCLE_20DP)
        val accent = Theme.getColor(Theme.key_inappPlayerPlayPause, resourcesProvider)
        playButton.setColors(accent, ColorUtils.setAlphaComponent(accent, 51), accent)
        cover.setColors(theme.primaryContainer, theme.onPrimaryContainer)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            val nc = NotificationCenter.getInstance(a)
            nc.addObserver(this, NotificationCenter.messagePlayingDidStart)
            nc.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged)
            nc.addObserver(this, NotificationCenter.messagePlayingProgressDidChanged)
            nc.addObserver(this, NotificationCenter.fileLoaded)
        }
        update()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        for (a in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            val nc = NotificationCenter.getInstance(a)
            nc.removeObserver(this, NotificationCenter.messagePlayingDidStart)
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
            else -> update()
        }
    }

    fun update() {
        val mo = MediaController.getInstance().playingMessageObject
        if (mo == null || !mo.isMusic) return
        current = mo
        titleView.text = mo.musicTitle
        artistView.text = mo.musicAuthor
        cover.bind(mo)
        playButton.setMessage(mo)
        val paused = MediaController.getInstance().isMessagePaused
        playButton.setPlaying(!paused, isAttachedToWindow)
        playButton.contentDescription = LocaleController.getString(if (paused) R.string.AccActionPlay else R.string.AccActionPause)
    }
}
