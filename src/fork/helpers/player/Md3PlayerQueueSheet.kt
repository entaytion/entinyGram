package desu.inugram.helpers.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.RecyclerListView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private fun dp(value: Float): Int = AndroidUtilities.dp(value)

class Md3PlayerQueueSheet(context: Context, private val colors: Md3PlayerColors, rp: Theme.ResourcesProvider?) :
    BottomSheet(context, false, rp), NotificationCenter.NotificationCenterDelegate {

    private val account: Int = MediaController.getInstance().playingMessageObject?.currentAccount ?: UserConfig.selectedAccount
    private val list: RecyclerListView
    private val layoutManager = LinearLayoutManager(context)
    private val subtitle = TextView(context)
    private val chip = TextView(context)
    private val chipBg = GradientDrawable()
    private val chipIcon = Md3PlayerIcon.stroke(Md3PlayerIcon.SHUFFLE, 18f)
    private val adapter = Adapter()
    private var touchInList = false
    private var observing = false

    init {
        occupyNavigationBar = true
        drawNavigationBar = false
        setApplyTopPadding(false)
        setApplyBottomPadding(false)
        currentAccount = account

        val bg = GradientDrawable()
        bg.setColor(colors.surfaceLow)
        val r = dp(28f).toFloat()
        bg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)

        list = object : RecyclerListView(context) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                setPadding(dp(12f), 0, dp(12f), dp(24f) + this@Md3PlayerQueueSheet.getBottomInset())
                super.onMeasure(widthSpec, heightSpec)
            }
        }

        val root = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val h = (MeasureSpec.getSize(heightMeasureSpec) * 0.78f).toInt()
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            }

            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                val action = ev.actionMasked
                if (action == MotionEvent.ACTION_DOWN) touchInList = ev.y >= list.top
                val result = super.dispatchTouchEvent(ev)
                if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && touchInList) {
                    touchInList = false
                    this@Md3PlayerQueueSheet.container.requestDisallowInterceptTouchEvent(false)
                }
                return result
            }
        }
        root.background = bg
        containerView = root

        val handle = View(context)
        val handleColor = colors.onSurfaceVariant
        handle.background = GradientDrawable().apply {
            cornerRadius = dp(2f).toFloat()
            setColor(ColorUtils.setAlphaComponent(handleColor, 0x73))
        }
        root.addView(handle, FrameLayout.LayoutParams(dp(32f), dp(4f), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(18f) })

        val header = LinearLayout(context)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(24f), 0, dp(24f), 0)
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(context)
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22f)
        title.typeface = AndroidUtilities.bold()
        title.setTextColor(colors.onSurface)
        title.text = LocaleController.getString(R.string.InuMd3PlayerUpNext)
        titles.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        subtitle.setTextColor(colors.onSurfaceVariant)
        subtitle.setSingleLine(true)
        subtitle.ellipsize = TextUtils.TruncateAt.END
        titles.addView(subtitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2f) })
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        chip.typeface = AndroidUtilities.bold()
        chip.gravity = Gravity.CENTER_VERTICAL
        chip.setPadding(dp(12f), 0, dp(16f), 0)
        chip.compoundDrawablePadding = dp(8f)
        chip.setCompoundDrawablesWithIntrinsicBounds(chipIcon, null, null, null)
        chip.text = LocaleController.getString(R.string.ShuffleList)
        chip.background = chipBg
        chip.setOnClickListener {
            MediaController.getInstance().setPlaybackOrderType(if (SharedConfig.shuffleMusic) 0 else 2)
            updateChip()
            adapter.notifyDataSetChanged()
            Md3PlayerSheet.instance?.refreshModes()
        }
        header.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40f)).apply { leftMargin = dp(12f) })
        root.addView(header, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(72f), Gravity.TOP).apply { topMargin = dp(34f) })

        list.layoutManager = layoutManager
        list.adapter = adapter
        list.clipToPadding = false
        list.isVerticalScrollBarEnabled = false
        list.setSelectorDrawableColor(0)
        list.setOnItemClickListener { _, position -> onItemClick(position) }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - 5) {
                    MediaController.getInstance().loadMoreMusic()
                }
            }
        })
        root.addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.TOP).apply { topMargin = dp(34f + 72f + 8f) })

        updateChip()
        updateSubtitle()
        val index = indexOfPlaying()
        if (index > 0) layoutManager.scrollToPositionWithOffset(index, dp(72f))
    }

    override fun canDismissWithSwipe(): Boolean = !touchInList || !list.canScrollVertically(-1)

    override fun show() {
        super.show()
        if (!observing) {
            observing = true
            val nc = NotificationCenter.getInstance(account)
            nc.addObserver(this, NotificationCenter.messagePlayingDidStart)
            nc.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged)
            nc.addObserver(this, NotificationCenter.messagePlayingDidReset)
            nc.addObserver(this, NotificationCenter.moreMusicDidLoad)
            nc.addObserver(this, NotificationCenter.musicDidLoad)
        }
        AndroidUtilities.setLightNavigationBar(this, ColorUtils.calculateLuminance(colors.surfaceLow) > 0.5)
    }

    override fun dismiss() {
        super.dismiss()
        if (observing) {
            observing = false
            val nc = NotificationCenter.getInstance(account)
            nc.removeObserver(this, NotificationCenter.messagePlayingDidStart)
            nc.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged)
            nc.removeObserver(this, NotificationCenter.messagePlayingDidReset)
            nc.removeObserver(this, NotificationCenter.moreMusicDidLoad)
            nc.removeObserver(this, NotificationCenter.musicDidLoad)
        }
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.messagePlayingDidReset) {
            val mo = MediaController.getInstance().playingMessageObject
            if (mo == null || !mo.isMusic) {
                dismiss()
                return
            }
        }
        if (id == NotificationCenter.moreMusicDidLoad || id == NotificationCenter.musicDidLoad) {
            adapter.notifyDataSetChanged()
            updateSubtitle()
            return
        }
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (child is Row) child.updateState()
        }
    }

    private fun updateChip() {
        val on = SharedConfig.shuffleMusic
        chipBg.cornerRadius = dp(if (on) 12f else 20f).toFloat()
        chipBg.setColor(if (on) colors.secondaryContainer else 0)
        chipBg.setStroke(if (on) 0 else dp(1f), colors.outlineVariant)
        val fg = if (on) colors.onSecondaryContainer else colors.onSurfaceVariant
        chip.setTextColor(fg)
        chipIcon.setColor(fg)
    }

    private fun updateSubtitle() {
        subtitle.text = LocaleController.formatPluralString("InuMd3PlayerTracks", MediaController.getInstance().playlist.size)
    }

    private fun direct(): Boolean =
        if (MediaController.getInstance().currentSavedMusicList != null) !SharedConfig.playOrderReversed else SharedConfig.playOrderReversed

    private fun itemAt(position: Int): MessageObject? {
        val playlist = MediaController.getInstance().playlist
        if (position < 0 || position >= playlist.size) return null
        return if (direct()) playlist[position] else playlist[playlist.size - 1 - position]
    }

    private fun currentItem(): MessageObject? {
        val mc = MediaController.getInstance()
        val playing = mc.playingMessageObject ?: return null
        val playlist = mc.playlist
        playlist.firstOrNull { it === playing }?.let { return it }
        playlist.firstOrNull { it.dialogId == playing.dialogId && it.id == playing.id }?.let { return it }
        val key = Md3PlayerArt.key(playing)
        return playlist.firstOrNull { TextUtils.equals(key, Md3PlayerArt.key(it)) }
    }

    private fun indexOfPlaying(): Int {
        val current = currentItem() ?: return -1
        for (i in 0 until adapter.itemCount) {
            if (itemAt(i) === current) return i
        }
        return -1
    }

    private fun onItemClick(position: Int) {
        val mo = itemAt(position) ?: return
        val mc = MediaController.getInstance()
        if (mc.isPlayingMessage(mo)) {
            if (mc.isMessagePaused) mc.playMessage(mo) else mc.pauseMessage(mo)
        } else {
            mc.findMessageInPlaylistAndPlay(mo)
        }
    }

    private inner class Adapter : RecyclerListView.SelectionAdapter() {
        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = true

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val row = Row(parent.context)
            row.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72f))
            return RecyclerListView.Holder(row)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as Row).bind(itemAt(position))
        }

        override fun getItemCount(): Int = MediaController.getInstance().playlist.size
    }

    private inner class Row(context: Context) : FrameLayout(context) {
        private val cover = Md3CoverImage(context, 22f)
        private val title = TextView(context)
        private val artist = TextView(context)
        private val duration = TextView(context)
        private val equalizer = Equalizer(context)
        private val bg = GradientDrawable()
        private var message: MessageObject? = null

        init {
            bg.cornerRadius = dp(20f).toFloat()
            background = bg
            setPadding(dp(10f), 0, dp(14f), 0)
            cover.setRadius(dp(14f))
            cover.setColors(colors.primaryContainer, colors.onPrimaryContainer)
            addView(cover, LayoutParams(dp(52f), dp(52f), Gravity.LEFT or Gravity.CENTER_VERTICAL))
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            title.typeface = AndroidUtilities.bold()
            title.setSingleLine(true)
            title.ellipsize = TextUtils.TruncateAt.END
            texts.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            artist.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            artist.setSingleLine(true)
            artist.ellipsize = TextUtils.TruncateAt.END
            texts.addView(artist, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2f) })
            addView(texts, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.LEFT or Gravity.CENTER_VERTICAL).apply {
                leftMargin = dp(66f)
                rightMargin = dp(52f)
            })
            duration.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            duration.fontFeatureSettings = "tnum"
            duration.setTextColor(colors.onSurfaceVariant)
            addView(duration, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.RIGHT or Gravity.CENTER_VERTICAL))
            addView(equalizer, LayoutParams(dp(22f), dp(20f), Gravity.RIGHT or Gravity.CENTER_VERTICAL))
        }

        fun bind(mo: MessageObject?) {
            message = mo
            if (mo == null) return
            cover.bind(mo)
            title.text = mo.musicTitle
            artist.text = mo.musicAuthor
            val d = mo.duration.roundToInt()
            duration.text = String.format(Locale.US, "%d:%02d", d / 60, d % 60)
            updateState()
        }

        fun updateState() {
            val mo = message
            val current = mo != null && mo === currentItem()
            bg.setColor(if (current) colors.secondaryContainer else 0)
            title.setTextColor(if (current) colors.onSecondaryContainer else colors.onSurface)
            artist.setTextColor(if (current) colors.onSecondaryContainer else colors.onSurfaceVariant)
            duration.visibility = if (current) GONE else VISIBLE
            equalizer.visibility = if (current) VISIBLE else GONE
            equalizer.setPlaying(current && !MediaController.getInstance().isMessagePaused)
        }
    }

    private inner class Equalizer(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.primary }
        private val rect = RectF()
        private val still = floatArrayOf(6f, 11f, 8f)
        private var playing = false
        private var phase = 0f
        private var lastFrame = 0L

        fun setPlaying(value: Boolean) {
            if (playing != value) {
                playing = value
                lastFrame = 0L
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val now = SystemClock.elapsedRealtime()
            val dt = if (lastFrame == 0L) 0.016f else min(0.05f, (now - lastFrame) / 1000f)
            lastFrame = now
            if (playing) phase += 6f * dt
            val barW = dp(4f).toFloat()
            val gap = dp(3f).toFloat()
            var x = (width - (barW * 3 + gap * 2)) / 2f
            val bottom = height.toFloat()
            val radius = dp(2f).toFloat()
            for (k in 0 until 3) {
                val h = if (playing) dp(5f + abs(sin(phase * 1.6f + k * 2.1f)) * 13f).toFloat() else dp(still[k]).toFloat()
                rect.set(x, bottom - h, x + barW, bottom)
                canvas.drawRoundRect(rect, radius, radius, paint)
                x += barW + gap
            }
            if (playing) postInvalidateOnAnimation() else lastFrame = 0L
        }
    }
}
