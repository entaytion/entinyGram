package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.FilterTabsView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor
import java.util.HashMap

@SuppressLint("ViewConstructor")
class FilterTabsPreviewCell(context: Context) : FrameLayout(context), NotificationCenter.NotificationCenterDelegate {

    private val filterTabsView: FilterTabsView
    private val blurSource: BlurredBackgroundSourceColor
    private val idsWithCounters = HashMap<Int, Int>()
    private val counterSeed = Utilities.random.nextInt(40) + 20

    init {
        setWillNotDraw(false)

        filterTabsView = FilterTabsView(context, null).apply {
            setPadding(0, AndroidUtilities.dp(7f), 0, AndroidUtilities.dp(7f))
            setColors(
                Theme.key_actionBarTabLine,
                Theme.key_actionBarTabActiveText,
                Theme.key_actionBarTabUnactiveText,
                Theme.key_actionBarTabSelector,
                Theme.key_actionBarDefault
            )
        }

        blurSource = BlurredBackgroundSourceColor().apply {
            setColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        }
        val factory = BlurredBackgroundDrawableViewFactory(blurSource)
        val pill = factory.create(
            filterTabsView,
            BlurredBackgroundProviderImpl.topPanel(null)
        )
        pill.setRadius(AndroidUtilities.dp(18f).toFloat())
        pill.setPadding(AndroidUtilities.dp(6.666f))
        filterTabsView.setBlurredBackground(pill)

        filterTabsView.setDelegate(object : FilterTabsView.FilterTabsViewDelegate {
            override fun canPerformActions(): Boolean = false
            override fun didSelectTab(tabView: FilterTabsView.TabView, selected: Boolean): Boolean = false
            override fun isTabMenuVisible(): Boolean = false
            override fun onDeletePressed(id: Int) {}
            override fun onPageReorder(fromId: Int, toId: Int) {}
            override fun onPageScrolled(progress: Float) {}
            override fun onPageSelected(tab: FilterTabsView.Tab, forward: Boolean) {}
            override fun onSamePageSelected() {}

            override fun getTabCounter(tabId: Int): Int {
                if (InuConfig.FOLDERS_UNREAD_COUNTER_MODE.value == InuConfig.FoldersUnreadCounterModeItem.HIDE) {
                    return 0
                }
                return idsWithCounters[tabId] ?: 0
            }
        })

        addView(
            filterTabsView,
            LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                50f,
                Gravity.CENTER,
                12f,
                0f,
                12f,
                0f
            )
        )
        // deferred: filterTabsView has zero measured width until the first layout pass,
        // and selectTabWithId()'s smoothScrollToPosition needs real dimensions to land correctly
        post { updateTabs(false) }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = true

    fun refresh() {
        (filterTabsView.layoutParams as? FrameLayout.LayoutParams)?.height = AndroidUtilities.dp(50f)
        filterTabsView.requestLayout()
        updateTabs(false)
        refreshVisuals()
    }

    fun refreshVisuals() {
        for (i in 0 until filterTabsView.childCount) {
            filterTabsView.getChildAt(i)?.invalidate()
        }
        filterTabsView.invalidate()
    }

    fun refreshCounters() {
        filterTabsView.checkTabsCounter()
    }

    fun updateColors() {
        blurSource.setColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        filterTabsView.updateColors()
        refreshVisuals()
    }

    private fun updateTabs(animated: Boolean) {
        filterTabsView.resetTabId()
        filterTabsView.removeTabs()
        idsWithCounters.clear()

        val filters = MessagesController.getInstance(UserConfig.selectedAccount).dialogFilters
        var firstId = -1
        var count = 0
        for (i in 0 until filters.size) {
            val f = filters[i]
            if (f.isDefault && InuConfig.HIDE_ALL_CHATS_TAB.value) {
                continue
            }
            if (i == 0 || i == 1 || i == 3) {
                idsWithCounters[f.id] = counterSeed / (i + 1)
            }
            if (firstId == -1) {
                firstId = f.id
            }
            if (f.isDefault) {
                filterTabsView.inu_addTab(
                    f.id,
                    0,
                    LocaleController.getString(R.string.FilterAllChats),
                    null,
                    false,
                    true,
                    f.locked,
                    "\uD83D\uDCAC"
                )
            } else {
                val emoji = if (f.inu_emoticon == null) "\uD83D\uDCC1" else f.inu_emoticon
                filterTabsView.inu_addTab(
                    f.id,
                    f.localId,
                    f.name,
                    f.entities,
                    f.title_noanimate,
                    false,
                    f.locked,
                    emoji
                )
            }
            count++
        }

        if (count < 6) {
            val ids = intArrayOf(100, 101, 102, 103)
            val strings = intArrayOf(
                R.string.FilterContacts,
                R.string.FilterGroups,
                R.string.FilterChannels,
                R.string.FilterBots
            )
            val emojis = arrayOf(
                "\uD83D\uDC64",
                "\uD83D\uDC65",
                "\uD83D\uDCE2",
                "\uD83E\uDD16"
            )
            for (i in ids.indices) {
                if (count >= 6) break
                val seedIndex = filters.size + i
                if (seedIndex == 0 || seedIndex == 1 || seedIndex == 3) {
                    idsWithCounters[ids[i]] = counterSeed / (seedIndex + 1)
                }
                if (firstId == -1) {
                    firstId = ids[i]
                }
                filterTabsView.inu_addTab(
                    ids[i],
                    ids[i],
                    LocaleController.getString(strings[i]),
                    null,
                    false,
                    false,
                    false,
                    emojis[i]
                )
                count++
            }
        }

        (filterTabsView.layoutParams as? FrameLayout.LayoutParams)?.height = AndroidUtilities.dp(50f)
        filterTabsView.requestLayout()

        filterTabsView.finishAddingTabs(animated)
        if (firstId == -1 && filters.isNotEmpty()) {
            firstId = filters[0].id
        }
        if (firstId != -1) {
            filterTabsView.selectTabWithId(firstId, 1f)
        } else {
            filterTabsView.selectFirstTab()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        NotificationCenter.getInstance(UserConfig.selectedAccount)
            .addObserver(this, NotificationCenter.dialogFiltersUpdated)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        NotificationCenter.getInstance(UserConfig.selectedAccount)
            .removeObserver(this, NotificationCenter.dialogFiltersUpdated)
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.dialogFiltersUpdated) {
            refreshVisuals()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(74f), MeasureSpec.EXACTLY)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!InuConfig.M3_SECTIONS_STYLE.value) {
            canvas.drawLine(
                0f,
                (measuredHeight - 1).toFloat(),
                measuredWidth.toFloat(),
                (measuredHeight - 1).toFloat(),
                Theme.dividerPaint
            )
        }
    }
}
