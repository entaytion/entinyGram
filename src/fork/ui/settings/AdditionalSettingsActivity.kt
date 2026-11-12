package desu.inugram.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.badges.BadgeRegistry
import desu.inugram.helpers.CrashReporter
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.LogsHelper
import desu.inugram.helpers.SystemInfo
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.messenger.browser.Browser
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.IUpdateLayout
import org.telegram.ui.LaunchActivity
import org.telegram.ui.UpdateLayoutWrapper

class AdditionalSettingsActivity : SettingsPageActivity(), NotificationCenter.NotificationCenterDelegate {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAbout)

    private var updateLayout: IUpdateLayout? = null
    private var updateWrapper: UpdateLayoutWrapper? = null

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asCustomShadow(InuSettingsHeader(context)))
        items.add(UItem.asShadow(null))
        items.add(
            UItem.asButton(
                BUTTON_CHANNEL_LINK,
                R.drawable.inu_tabler_brand_telegram,
                LocaleController.getString(R.string.InuAboutChannel),
                "@entinyGram"
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_GITHUB,
                R.drawable.inu_tabler_brand_github,
                LocaleController.getString(R.string.InuAboutGitHub),
                "Entaytion/entinyGram"
            )
        )
        items.add(mkTwoLineEntry(BUTTON_DONATE, R.drawable.inu_tabler_heart, LocaleController.getString(R.string.InuDonateRow), LocaleController.getString(R.string.InuDonateRowInfo), chevron = true))
        items.add(UItem.asShadow(null))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HIDE_DEV_BADGES,
                R.string.InuHideDevBadges,
                R.string.InuHideDevBadgesInfo,
                InuConfig.HIDE_DEV_BADGES.value,
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuUpdates)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_AUTO_UPDATE_CHECK,
                R.string.InuAutoUpdateCheck,
                R.string.InuAutoUpdateCheckInfo,
                InuConfig.UPDATES_ENABLED.value,
            )
        )
        if (InuConfig.UPDATES_ENABLED.value) {
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_UPDATES_INCLUDE_BETA,
                    R.string.InuUpdatesIncludeBeta,
                    R.string.InuUpdatesIncludeBetaInfo,
                    InuConfig.UPDATES_INCLUDE_BETA.value,
                )
            )
        }
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuLogs)))
        items.add(
            UItem.asCheck(
                TOGGLE_LOGS_ENABLED,
                LocaleController.getString(R.string.InuLogsEnabled),
            ).setChecked(LogsHelper.isEnabled())
        )
        if (LogsHelper.isEnabled()) {
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_LOGS_REDACT,
                    R.string.InuLogsRedact,
                    R.string.InuLogsRedactInfo,
                    InuConfig.LOGS_REDACT.value,
                )
            )
            items.add(UItem.asCustom(getOrCreateLogsRow()))
            items.add(UItem.asCustom(getOrCreateHeapRow()))
        }
        items.add(UItem.asShadow(null))

        items.add(UItem.asButton(BUTTON_COPY_SYSINFO, R.drawable.inu_tabler_info_square, LocaleController.getString(R.string.InuLogsCopySystemInfo)))
        items.add(UItem.asShadow(null))
    }

    override fun createView(context: Context): View {
        val root = super.createView(context) as FrameLayout

        val wrapper = UpdateLayoutWrapper(context)
        root.addView(
            wrapper,
            LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        updateWrapper = wrapper

        val ul = ApplicationLoader.applicationLoaderInstance?.takeUpdateLayout(parentActivity, wrapper)
        updateLayout = ul
        ul?.updateAppUpdateViews(UserConfig.selectedAccount, false)
        updateListPadding()

        return root
    }

    override fun onInsets(left: Int, top: Int, right: Int, bottom: Int) {
        updateWrapper?.setPadding(0, 0, 0, bottom)
        super.onInsets(left, top, right, bottom)
    }

    override fun getListExtraBottomPadding(): Int = if (SharedConfig.isAppUpdateAvailable()) dp(44f) else 0

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_AUTO_UPDATE_CHECK -> {
                val new = InuConfig.UPDATES_ENABLED.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                listView?.adapter?.update(true)
            }

            TOGGLE_UPDATES_INCLUDE_BETA -> {
                val new = InuConfig.UPDATES_INCLUDE_BETA.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_DEV_BADGES -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.HIDE_DEV_BADGES.toggle()
                // entiny: update badge on cached TLRPC user/chat objects so toggle takes effect without app restart
                BadgeRegistry.refreshCached()
            }

            TOGGLE_LOGS_REDACT -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.LOGS_REDACT.toggle()

            TOGGLE_LOGS_ENABLED -> {
                if (LogsHelper.isEnabled()) {
                    setLogsEnabled(false, view)
                } else {
                    AlertDialog.Builder(parentActivity ?: return)
                        .setTitle(LocaleController.getString(R.string.InuLogsPrivacyTitle))
                        .setMessage(LocaleController.getString(R.string.InuLogsPrivacyText))
                        .setPositiveButton(LocaleController.getString(R.string.InuLogsEnableAction)) { _, _ -> setLogsEnabled(true, view) }
                        .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                        .show()
                }
            }

            BUTTON_COPY_SYSINFO -> {
                AndroidUtilities.addToClipboard(SystemInfo.build())
                BulletinFactory.of(this).createCopyBulletin(
                    LocaleController.getString(R.string.InuLogsSystemInfoCopied)
                ).show()
            }

            BUTTON_DONATE -> presentFragment(DonateActivity())

            BUTTON_CHANNEL_LINK -> Browser.openUrl(context ?: return, "https://t.me/entinyGram")

            BUTTON_GITHUB -> Browser.openUrl(context ?: return, "https://github.com/Entaytion/EntinyGram")

        }
    }

    override fun onFragmentCreate(): Boolean {
        val ok = super.onFragmentCreate()
        val global = NotificationCenter.getGlobalInstance()
        global.addObserver(this, NotificationCenter.appUpdateAvailable)
        global.addObserver(this, NotificationCenter.appUpdateLoading)
        for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            val nc = NotificationCenter.getInstance(account)
            nc.addObserver(this, NotificationCenter.fileLoadProgressChanged)
            nc.addObserver(this, NotificationCenter.fileLoaded)
            nc.addObserver(this, NotificationCenter.fileLoadFailed)
        }
        return ok
    }

    override fun onFragmentDestroy() {
        val global = NotificationCenter.getGlobalInstance()
        global.removeObserver(this, NotificationCenter.appUpdateAvailable)
        global.removeObserver(this, NotificationCenter.appUpdateLoading)
        for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            val nc = NotificationCenter.getInstance(account)
            nc.removeObserver(this, NotificationCenter.fileLoadProgressChanged)
            nc.removeObserver(this, NotificationCenter.fileLoaded)
            nc.removeObserver(this, NotificationCenter.fileLoadFailed)
        }
        super.onFragmentDestroy()
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        val ul = updateLayout ?: return
        val acct = UserConfig.selectedAccount
        when (id) {
            NotificationCenter.appUpdateAvailable -> {
                val animated = args.getOrNull(0) as? Boolean ?: true
                ul.updateAppUpdateViews(acct, animated)
                updateListPadding()
            }

            NotificationCenter.appUpdateLoading -> {
                ul.updateFileProgress(null)
                ul.updateAppUpdateViews(acct, true)
            }

            NotificationCenter.fileLoadProgressChanged -> {
                ul.updateFileProgress(args)
            }

            NotificationCenter.fileLoaded, NotificationCenter.fileLoadFailed -> {
                val name = args.getOrNull(0) as? String ?: return
                val doc = SharedConfig.pendingAppUpdate?.document ?: return
                if (name == FileLoader.getAttachFileName(doc)) {
                    ul.updateAppUpdateViews(acct, true)
                }
            }
        }
    }

    private var logsRow: View? = null
    private var logsSizeText: TextView? = null
    private var logsSize: Long = -1L

    private fun getOrCreateLogsRow(): View {
        logsRow?.let { return it }
        val ctx = context!!
        val row = object : LinearLayout(ctx) {
            override fun dispatchDraw(canvas: Canvas) {
                super.dispatchDraw(canvas)
                canvas.drawLine(
                    dp(20f).toFloat(),
                    height - 1f,
                    width.toFloat(),
                    height.toFloat(),
                    Theme.dividerPaint,
                )
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(50f)
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
            setPadding(dp(21f), 0, dp(8f), 0)
        }
        val size = TextView(ctx).apply {
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            text = logsSizeLabel(logsSize)
        }
        logsSizeText = size
        row.addView(size, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(buildLogsIconButton(ctx, R.drawable.msg_clear, R.string.InuLogsClear) {
            FileLog.cleanupLogs()
            refreshLogsSize()
            BulletinFactory.of(this).createSimpleBulletin(
                R.raw.chats_infotip,
                LocaleController.getString(R.string.InuLogsCleared),
            ).show()
        })
        row.addView(buildLogsIconButton(ctx, R.drawable.msg_shareout, R.string.InuLogsShare) { anchor ->
            showShareMenu(anchor)
        })
        logsRow = row
        refreshLogsSize()
        return row
    }

    private fun buildLogsIconButton(
        ctx: Context, iconRes: Int, contentDescRes: Int, onClick: (anchor: View) -> Unit,
    ): ImageView = ImageView(ctx).apply {
        setImageResource(iconRes)
        setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY)
        background = Theme.createSelectorDrawable(
            Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP,
        )
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        contentDescription = LocaleController.getString(contentDescRes)
        layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f))
        setOnClickListener { onClick(this) }
    }

    private fun showShareMenu(anchor: View) {
        val opts = ItemOptions.makeOptions(this, anchor)
        opts.add(R.drawable.msg_archive, LocaleController.getString(R.string.InuLogsShareZip)) {
            val activity = parentActivity as? LaunchActivity ?: return@add
            LogsHelper.shareZip(activity, ::onShareDone)
        }
        opts.add(R.drawable.msg_log, LocaleController.getString(R.string.InuLogsShareCurrent)) {
            val activity = parentActivity as? LaunchActivity ?: return@add
            LogsHelper.shareCurrent(activity, ::onShareDone)
        }
        opts.setGravity(Gravity.END).show()
    }

    private fun setLogsEnabled(enabled: Boolean, view: View) {
        LogsHelper.setEnabled(enabled)
        (view as? TextCheckCell)?.isChecked = enabled
        if (enabled) refreshLogsSize()
        listView?.adapter?.update(true)
    }

    private fun onShareDone(ok: Boolean) {
        if (!ok) BulletinFactory.of(this).createErrorBulletin(
            LocaleController.getString(R.string.InuLogsShareError)
        ).show()
    }

    private var heapRow: View? = null
    private var heapUsageText: TextView? = null

    private fun getOrCreateHeapRow(): View {
        heapRow?.let { refreshHeapUsage(); return it }
        val ctx = context!!
        val row = object : LinearLayout(ctx) {
            override fun dispatchDraw(canvas: Canvas) {
                super.dispatchDraw(canvas)
                canvas.drawLine(
                    dp(20f).toFloat(),
                    height - 1f,
                    width.toFloat(),
                    height.toFloat(),
                    Theme.dividerPaint,
                )
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(50f)
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
            setPadding(dp(21f), 0, dp(8f), 0)
        }
        val usage = TextView(ctx).apply {
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        }
        heapUsageText = usage
        row.addView(usage, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(buildLogsIconButton(ctx, R.drawable.msg_calls_minimize, R.string.InuLogsRunGc) {
            val rt = Runtime.getRuntime()
            rt.gc()
            BulletinFactory.of(this).createErrorBulletin("Runtime GC finished").show()
            refreshHeapUsage()
        })
        row.addView(buildLogsIconButton(ctx, R.drawable.msg_download, R.string.InuLogsMakeHeapDump) {
            confirmAndMakeHeapDump()
        })
        heapRow = row
        refreshHeapUsage()
        return row
    }

    private fun confirmAndMakeHeapDump() {
        val activity = parentActivity as? LaunchActivity ?: return
        AlertDialog.Builder(activity)
            .setTitle(LocaleController.getString(R.string.InuLogsMakeHeapDump))
            .setMessage(AndroidUtilities.replaceTags(LocaleController.getString(R.string.InuLogsHeapDumpWarning)))
            .setPositiveButton(LocaleController.getString(R.string.Continue)) { _, _ -> makeHeapDump(activity) }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show()
    }

    private fun makeHeapDump(activity: LaunchActivity) {
        val progress = AlertDialog(activity, AlertDialog.ALERT_TYPE_MESSAGE).apply {
            setMessage(LocaleController.getString(R.string.InuLogsMakingHeapDump))
            setCanCancel(false)
        }
        progress.show()
        // entiny: delay heap dump so dialog can render a frame before thread freezes
        AndroidUtilities.runOnUIThread({
            var ok = true
            try {
                CrashReporter.dumpAndSaveHeap(activity)
            } catch (e: Throwable) {
                ok = false
                FileLog.e(e)
            } finally {
                progress.dismiss()
                refreshHeapUsage()
            }
            if (!ok) BulletinFactory.of(this).createErrorBulletin(
                LocaleController.getString(R.string.InuLogsShareError)
            ).show()
        }, 150)
    }

    private fun refreshHeapUsage() {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        heapUsageText?.text = LocaleController.formatString(
            R.string.InuLogsHeapUsage,
            AndroidUtilities.formatFileSize(used),
            AndroidUtilities.formatFileSize(rt.maxMemory()),
        )
    }

    private fun logsSizeLabel(size: Long): String = LocaleController.formatString(
        R.string.InuLogsSize,
        if (size < 0) "…" else AndroidUtilities.formatFileSize(size),
    )

    private fun refreshLogsSize() {
        logsSize = -1L
        logsSizeText?.text = logsSizeLabel(-1L)
        Utilities.globalQueue.postRunnable {
            val size = LogsHelper.computeSize()
            AndroidUtilities.runOnUIThread {
                logsSize = size
                logsSizeText?.text = logsSizeLabel(size)
            }
        }
    }

    companion object {
        private val TOGGLE_AUTO_UPDATE_CHECK = InuUtils.generateId()
        private val TOGGLE_UPDATES_INCLUDE_BETA = InuUtils.generateId()
        private val TOGGLE_HIDE_DEV_BADGES = InuUtils.generateId()
        private val TOGGLE_LOGS_ENABLED = InuUtils.generateId()
        private val TOGGLE_LOGS_REDACT = InuUtils.generateId()
        private val BUTTON_DONATE = InuUtils.generateId()
        private val BUTTON_CHANNEL_LINK = InuUtils.generateId()
        private val BUTTON_GITHUB = InuUtils.generateId()
        private val BUTTON_COPY_SYSINFO = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "about",
            aliases = listOf("additional"),
            titleRes = R.string.InuAbout,
            iconRes = R.drawable.inu_tabler_info_circle,
            factory = ::AdditionalSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("channel", R.string.InuAboutChannel, BUTTON_CHANNEL_LINK),
                SearchRegistry.Entry("github", R.string.InuAboutGitHub, BUTTON_GITHUB),
                SearchRegistry.Entry("logs-enabled", R.string.InuLogsEnabled, TOGGLE_LOGS_ENABLED),
                SearchRegistry.Entry("logs-redact", R.string.InuLogsRedact, TOGGLE_LOGS_REDACT),
                SearchRegistry.Entry("additional-donate", R.string.InuDonateRow, BUTTON_DONATE),
                SearchRegistry.Entry("hide-dev-badges", R.string.InuHideDevBadges, TOGGLE_HIDE_DEV_BADGES),
                SearchRegistry.Entry("auto-update-check", R.string.InuAutoUpdateCheck, TOGGLE_AUTO_UPDATE_CHECK),
                SearchRegistry.Entry("updates-include-beta", R.string.InuUpdatesIncludeBeta, TOGGLE_UPDATES_INCLUDE_BETA),
            ),
        )
    }
}
