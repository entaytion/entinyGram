package desu.inugram.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Paint.FontMetricsInt
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.Layout
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ReplacementSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.withTranslation
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCell
import org.telegram.ui.Components.Bulletin
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ColoredImageSpan
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalFragment
import org.telegram.ui.Components.UniversalRecyclerView
import org.telegram.ui.LaunchActivity

abstract class SettingsPageActivity : UniversalFragment() {
    override fun isSupportEdgeToEdge(): Boolean = true

    private var highlightItemId: Int = -1

    fun withHighlight(itemId: Int) = apply { highlightItemId = itemId }

    override fun createView(context: Context): View {
        return super.createView(context).also {
            if (actionBar.backButtonImageView == null) {
                actionBar.setBackButtonImage(R.drawable.ic_ab_back)
            }
            if (actionBar.actionBarMenuOnItemClick == null) {
                actionBar.setActionBarMenuOnItemClick(object : org.telegram.ui.ActionBar.ActionBar.ActionBarMenuOnItemClick() {
                    override fun onItemClick(id: Int) {
                        if (id == -1) finishFragment()
                    }
                })
            }
            listView.setSections()
            actionBar.setAdaptiveBackground(listView)
            listView.clipToPadding = false
            // pre-scroll before first layout so the row is on-screen at open, no jump after transition.
            if (highlightItemId != -1) {
                val index = indexOfItem(listView, highlightItemId)
                if (index >= 0) {
                    listView.layoutManager.scrollToPositionWithOffset(index, AndroidUtilities.dp(60f))
                }
            }
        }
    }

    override fun onTransitionAnimationEnd(isOpen: Boolean, backward: Boolean) {
        super.onTransitionAnimationEnd(isOpen, backward)
        if (!isOpen || backward || highlightItemId == -1) return
        val index = indexOfItem(listView, highlightItemId)
        highlightItemId = -1
        if (index >= 0) listView.highlightRow { index }
    }

    private fun indexOfItem(lv: UniversalRecyclerView, target: Int): Int {
        var i = 0
        while (true) {
            val item = lv.adapter.getItem(i) ?: return -1
            if (item.id == target) return i
            i++
        }
    }

    override fun onInsets(left: Int, top: Int, right: Int, bottom: Int) {
        val lv = listView ?: return
        val container = stickyButtonContainer
        if (container != null) {
            lv.setPadding(0, 0, 0, bottom + dp(STICKY_BUTTON_HEIGHT))
            container.setPadding(dp(16), dp(8), dp(16), dp(8) + bottom)
        } else {
            lv.setPadding(lv.paddingLeft, lv.paddingTop, lv.paddingRight, bottom)
        }
    }

    override fun onFragmentDestroy() {
        if (stickyButtonContainer != null) Bulletin.removeDelegate(this)
        super.onFragmentDestroy()
    }

    private var stickyButtonContainer: FrameLayout? = null

    // Sticky bottom button bar (à la CloudSyncActivity): wraps `button` in a windowBackgroundWhite
    // bar, reserves matching list padding, and offsets bulletins above it. Call from createView
    // after super.createView. Cleanup is handled in onFragmentDestroy.
    protected fun attachStickyButton(rootView: View, button: View) {
        val container = FrameLayout(rootView.context).apply {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(button, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48f))
        }
        stickyButtonContainer = container
        (rootView as FrameLayout).addView(
            container,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM)
        )
        listView.setPadding(0, 0, 0, dp(STICKY_BUTTON_HEIGHT))
        Bulletin.addDelegate(this, object : Bulletin.Delegate {
            override fun getBottomOffset(tag: Int): Int = stickyButtonContainer?.height ?: 0
        })
    }

    private fun dp(v: Int) = AndroidUtilities.dp(v.toFloat())

    protected fun postNotificationForAllAccounts(id: Int, vararg args: Any?) {
        for (i in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (UserConfig.getInstance(i).isClientActivated) {
                NotificationCenter.getInstance(i).postNotificationName(id, *args)
            }
        }
    }

    // rebuilds views of all fragments below this one — settings page itself stays.
    protected fun softRebuild() {
        LaunchActivity.instance?.rebuildAllFragments(false)
    }

    // redraws all visible rows; softRebuild skips the current page, so toggles that change how
    // cells draw (e.g. material 3 switches) need this to take effect live.
    protected fun invalidateVisibleRows() {
        fun walk(view: View) {
            view.invalidate()
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(listView ?: return)
    }

    protected fun showRestartBulletin() {
        BulletinFactory.of(this)
            .createSimpleBulletin(
                R.raw.chats_infotip,
                LocaleController.getString(R.string.InuRestartRequired),
                LocaleController.getString(R.string.InuRestartNow)
            ) {
                val activity = parentActivity ?: return@createSimpleBulletin
                InuUtils.restartApp(activity)
            }
            .show()
    }

    protected fun mkTwoLineCheckItem(
        id: Int,
        textRes: Int,
        infoRes: Int,
        checked: Boolean,
        experimental: Boolean = false
    ): UItem {
        val rawText = LocaleController.getString(textRes)
        val text = if (experimental) addExperimentalSpan(rawText) else rawText
        val subtext = if (infoRes == 0) null else LocaleController.getString(infoRes)
        return UItem.asButtonCheck(id, text, subtext).also {
            it.checked = checked
            it.bind = Utilities.Callback { view ->
                (view as? NotificationsCheckCell)?.setTextAndValueAndCheck(
                    text,
                    subtext,
                    checked,
                    0,
                    subtext != null,
                    !InuConfig.M3_SECTIONS_STYLE.value
                )
                (view as? NotificationsCheckCell)?.setDrawLine(false)
            }
        }
    }

    protected fun mkSubPageButton(id: Int, text: CharSequence): UItem {
        return UItem.asButton(id, text).also {
            it.bind = Utilities.Callback { view ->
                val cell = view as? TextCell ?: return@Callback
                val iv = cell.valueImageView
                iv.setImageResource(R.drawable.msg_arrowright)
                iv.colorFilter = PorterDuffColorFilter(
                    Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon),
                    PorterDuff.Mode.MULTIPLY,
                )
                iv.visibility = View.VISIBLE
            }
        }
    }

    protected fun mkSplitCheckItem(
        id: Int,
        textRes: Int,
        infoRes: Int,
        checked: Boolean,
        experimental: Boolean = false
    ): UItem {
        val rawText = LocaleController.getString(textRes)
        val text = if (experimental) addExperimentalSpan(rawText) else rawText
        val subtext = if (infoRes == 0) null else LocaleController.getString(infoRes)
        return UItem.asButtonCheck(id, text, subtext).also {
            it.checked = checked
            it.bind = Utilities.Callback { view ->
                (view as? NotificationsCheckCell)?.setTextAndValueAndCheck(
                    text,
                    subtext,
                    checked,
                    0,
                    subtext != null,
                    !InuConfig.M3_SECTIONS_STYLE.value
                )
                (view as? NotificationsCheckCell)?.setDrawLine(true)
            }
        }
    }


    protected fun addExperimentalSpan(string: CharSequence): CharSequence {
        val span = ColoredImageSpan(R.drawable.ic_beta_badge, ColoredImageSpan.ALIGN_CENTER)
        span.setSize(AndroidUtilities.dp(14f))

        val tagText = SpannableString(" ")
        tagText.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val text = SpannableStringBuilder()
        text.append(tagText)
        text.append("  ")
        text.append(string)
        return text
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        val opts = ItemOptions.makeOptions(this, view)
        if (!addCopyLinkOption(opts, item)) return false
        opts.show()
        return true
    }

    protected fun addCopyLinkOption(opts: ItemOptions, item: UItem): Boolean {
        val link = SearchRegistry.deepLinkForItemId(item.id) ?: return false
        opts.add(R.drawable.msg_link, LocaleController.getString(R.string.CopyLink)) {
            val cm = ApplicationLoader.applicationContext
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("label", link))
            BulletinFactory.of(this).createCopyLinkBulletin().show()
        }
        return true
    }

    companion object {
        private const val STICKY_BUTTON_HEIGHT = 64
    }
}
