package desu.inugram.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import desu.inugram.SearchRegistry
import desu.inugram.helpers.network.DatacenterPing
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DocumentObject
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.tgnet.ConnectionsManager
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.LinkSpanDrawable
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import java.util.HashMap

class DatacenterStatusActivity(private val dcToHighlight: Int = 0) : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuDatacenterStatus)

    private var headerCell: DatacenterHeaderCell? = null
    private val dcCells = HashMap<Int, DatacenterCell>()
    private var refreshItem: ActionBarMenuItem? = null

    override fun createView(context: Context): View {
        if (dcToHighlight != 0) {
            withHighlight(DC_ITEM_BASE + dcToHighlight)
        }
        return super.createView(context).also {
            val menu = actionBar.createMenu()
            val item = menu.addItem(MENU_REFRESH, R.drawable.msg_retry)
            item.contentDescription = LocaleController.getString(R.string.Retry)
            refreshItem = item
            actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    if (id == -1) {
                        finishFragment()
                    } else if (id == MENU_REFRESH) {
                        refreshItem?.iconView?.animate()?.rotationBy(360f)?.setDuration(400)?.start()
                        DatacenterPing.checkAll(force = true) {
                            updateList()
                        }
                    }
                }
            })
        }
    }

    override fun onResume() {
        super.onResume()
        DatacenterPing.checkAll(force = false) {
            updateList()
        }
    }

    private fun updateList() {
        val currentDcId = ConnectionsManager.getInstance(currentAccount).currentDatacenterId
        val statuses = DatacenterPing.getAllStatuses()
        for (i in statuses.indices) {
            val status = statuses[i]
            val cell = dcCells[status.dc.id] ?: continue
            val isCurrentDc = (currentDcId == status.dc.id)
            val isLast = (i == statuses.lastIndex)
            cell.bind(status, isCurrentDc, !isLast)
        }
        listView?.adapter?.update(false)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val ctx = context ?: return

        items.add(UItem.asCustomShadow(getOrCreateHeaderCell(ctx)))
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuDatacenterStatus)))

        val currentDcId = ConnectionsManager.getInstance(currentAccount).currentDatacenterId
        val statuses = DatacenterPing.getAllStatuses()
        for (i in statuses.indices) {
            val status = statuses[i]
            val cell = getOrCreateCell(ctx, status.dc.id)
            val isCurrentDc = (currentDcId == status.dc.id)
            val isLast = (i == statuses.lastIndex)
            cell.bind(status, isCurrentDc, !isLast)
            cell.setOnClickListener {
                DatacenterPing.check(status.dc.id, force = true) {
                    updateList()
                }
            }
            items.add(UItem.asCustom(DC_ITEM_BASE + status.dc.id, cell))
        }
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val id = item.id
        if (id >= DC_ITEM_BASE && id < DC_ITEM_BASE + 10) {
            val dcId = id - DC_ITEM_BASE
            DatacenterPing.check(dcId, force = true) {
                updateList()
            }
        }
    }

    private fun getOrCreateHeaderCell(ctx: Context): DatacenterHeaderCell {
        headerCell?.let { return it }
        val cell = DatacenterHeaderCell(ctx, currentAccount, resourceProvider)
        headerCell = cell
        return cell
    }

    private fun getOrCreateCell(ctx: Context, dcId: Int): DatacenterCell {
        return dcCells.getOrPut(dcId) { DatacenterCell(ctx, resourceProvider) }
    }

    private class DatacenterHeaderCell(
        context: Context,
        private val currentAccount: Int,
        private val resourceProvider: Theme.ResourcesProvider? = null,
    ) : FrameLayout(context), NotificationCenter.NotificationCenterDelegate {
        private val imageView = BackupImageView(context)
        private val textView = LinkSpanDrawable.LinksTextView(context, resourceProvider)

        init {
            addView(imageView, LayoutHelper.createFrame(120, 120f, Gravity.CENTER_HORIZONTAL, 0f, 16f, 0f, 0f))
            imageView.setOnClickListener {
                val anim = imageView.imageReceiver?.lottieAnimation
                if (anim != null && !anim.isRunning) {
                    anim.setCurrentFrame(0, false)
                    anim.restart()
                }
            }
            imageView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

            addView(
                textView,
                LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(),
                    0, 36f, 152f, 36f, 0f
                )
            )
            textView.gravity = Gravity.CENTER_HORIZONTAL
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider))
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            textView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText, resourceProvider))
            textView.highlightColor = Theme.getColor(Theme.key_windowBackgroundWhiteLinkSelection, resourceProvider)
            textView.movementMethod = AndroidUtilities.LinkMovementMethodMy()
            textView.text = AndroidUtilities.replaceTags(LocaleController.getString(R.string.InuDatacenterStatusAbout))

            setSticker()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(196f), MeasureSpec.EXACTLY)
            )
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            setSticker()
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.diceStickersDidLoad)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.diceStickersDidLoad)
        }

        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            if (id == NotificationCenter.diceStickersDidLoad) {
                val name = args.getOrNull(0) as? String
                if (AndroidUtilities.STICKERS_PLACEHOLDER_PACK_NAME == name) {
                    setSticker()
                }
            }
        }

        private fun setSticker() {
            val mdc = MediaDataController.getInstance(currentAccount)
            val set = mdc.getStickerSetByName(AndroidUtilities.STICKERS_PLACEHOLDER_PACK_NAME)
                ?: mdc.getStickerSetByEmojiOrName(AndroidUtilities.STICKERS_PLACEHOLDER_PACK_NAME)
            val doc = if (set != null && set.documents.size >= 3) set.documents[2] else null

            val svgThumb = doc?.let { DocumentObject.getSvgThumb(it.thumbs, Theme.key_emptyListPlaceholder, 0.2f) }
            svgThumb?.overrideWidthAndHeight(512, 512)

            if (doc != null) {
                val loc = ImageLocation.getForDocument(doc)
                imageView.setImage(loc, "130_130", "tgs", svgThumb, set)
                imageView.imageReceiver?.setAutoRepeat(2)
            } else {
                mdc.loadStickersByEmojiOrName(AndroidUtilities.STICKERS_PLACEHOLDER_PACK_NAME, false, set == null)
            }
        }
    }

    private class DatacenterCell(
        context: Context,
        private val resourceProvider: Theme.ResourcesProvider? = null,
    ) : FrameLayout(context) {
        private val titleView = TextView(context)
        private val statusView = TextView(context)
        private var needDivider = false

        init {
            background = Theme.getSelectorDrawable(false)
            isClickable = true

            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider))
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            titleView.isSingleLine = true
            titleView.ellipsize = TextUtils.TruncateAt.END
            titleView.gravity = (if (LocaleController.isRTL) Gravity.RIGHT else Gravity.LEFT) or Gravity.CENTER_VERTICAL
            addView(
                titleView,
                LayoutHelper.createFrame(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(),
                    (if (LocaleController.isRTL) Gravity.RIGHT else Gravity.LEFT) or Gravity.TOP,
                    if (LocaleController.isRTL) 56f else 21f, 10f, if (LocaleController.isRTL) 21f else 56f, 0f
                )
            )

            statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            statusView.gravity = if (LocaleController.isRTL) Gravity.RIGHT else Gravity.LEFT
            statusView.isSingleLine = true
            statusView.ellipsize = TextUtils.TruncateAt.END
            addView(
                statusView,
                LayoutHelper.createFrame(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(),
                    (if (LocaleController.isRTL) Gravity.RIGHT else Gravity.LEFT) or Gravity.TOP,
                    if (LocaleController.isRTL) 56f else 21f, 35f, if (LocaleController.isRTL) 21f else 56f, 0f
                )
            )
        }

        fun bind(status: DatacenterPing.DcStatus, isCurrentDc: Boolean, divider: Boolean) {
            needDivider = divider
            setWillNotDraw(!needDivider)

            val baseTitle = "DC${status.dc.id} ${status.dc.name}, ${status.dc.location}"
            if (isCurrentDc) {
                val yourDc = LocaleController.getString(R.string.InuDatacenterYourDc)
                val sb = SpannableStringBuilder(baseTitle).append(" • ")
                val start = sb.length
                sb.append(yourDc)
                sb.setSpan(
                    ForegroundColorSpan(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourceProvider)),
                    start,
                    sb.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                titleView.text = sb
            } else {
                titleView.text = baseTitle
            }

            val (text, colorKey) = when (val res = status.result) {
                is DatacenterPing.Result.Idle, is DatacenterPing.Result.Checking -> {
                    LocaleController.getString(R.string.Checking) to Theme.key_windowBackgroundWhiteGrayText2
                }
                is DatacenterPing.Result.Available -> {
                    val pingStr = LocaleController.formatString(R.string.Ping, res.pingMs)
                    "${LocaleController.getString(R.string.Available)}, $pingStr" to Theme.key_windowBackgroundWhiteGreenText
                }
                is DatacenterPing.Result.Slow -> {
                    val pingStr = LocaleController.formatString(R.string.Ping, res.pingMs)
                    "${LocaleController.getString(R.string.SpeedSlow)}, $pingStr" to Theme.key_text_RedRegular
                }
                is DatacenterPing.Result.Unavailable -> {
                    LocaleController.getString(R.string.Unavailable) to Theme.key_text_RedRegular
                }
                is DatacenterPing.Result.LeakGuardBlocked -> {
                    LocaleController.getString(R.string.InuDatacenterLeakGuardBlocked) to Theme.key_windowBackgroundWhiteGrayText2
                }
            }
            statusView.text = text
            statusView.setTextColor(Theme.getColor(colorKey, resourceProvider))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64f) + if (needDivider) 1 else 0, MeasureSpec.EXACTLY)
            )
        }

        override fun onDraw(canvas: Canvas) {
            if (needDivider) {
                val left = if (LocaleController.isRTL) 0f else AndroidUtilities.dp(20f).toFloat()
                val right = (measuredWidth - if (LocaleController.isRTL) AndroidUtilities.dp(20f) else 0).toFloat()
                canvas.drawLine(left, measuredHeight - 1f, right, measuredHeight - 1f, Theme.dividerPaint)
            }
        }
    }

    companion object {
        private const val MENU_REFRESH = 1
        private const val DC_ITEM_BASE = 100

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "datacenter-status",
            titleRes = R.string.InuDatacenterStatus,
            iconRes = R.drawable.inu_tabler_server,
            factory = ::DatacenterStatusActivity,
        )
    }
}
