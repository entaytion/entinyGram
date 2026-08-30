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
import desu.inugram.helpers.CrashReporter
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.LogsHelper
import desu.inugram.helpers.SystemInfo
import desu.inugram.helpers.cloud.SettingsBackupHelper
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.collection.LongSparseArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.telegram.messenger.DialogObject
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.ShareAlert
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Cells.CheckBoxCell
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
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuCategoryBackup)

    private var updateLayout: IUpdateLayout? = null
    private var updateWrapper: UpdateLayoutWrapper? = null
    private var bottomInset: Int = 0

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
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

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuDataBackup)))
        items.add(UItem.asButton(BUTTON_EXPORT, R.drawable.inu_tabler_file_export, LocaleController.getString(R.string.InuBackupExport)))
        items.add(UItem.asButton(BUTTON_IMPORT, R.drawable.inu_tabler_file_import, LocaleController.getString(R.string.InuBackupImport)))
        items.add(mkSubPageButton(BUTTON_CLOUD_SYNC, R.drawable.inu_tabler_cloud, LocaleController.getString(R.string.InuCloudSync)))
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuCacheManagement)))
        items.add(mkSubPageButton(BUTTON_CACHE_MANAGEMENT, R.drawable.inu_tabler_trash_x, LocaleController.getString(R.string.InuCacheManagement)))
        items.add(UItem.asShadow(null))

        if (BuildVars.isBetaApp()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuLogs)))
            items.add(
                UItem.asCheck(
                    TOGGLE_LOGS_ENABLED,
                    LocaleController.getString(R.string.InuLogsEnabled),
                ).setChecked(LogsHelper.isEnabled())
            )
            if (LogsHelper.isEnabled()) {
                items.add(UItem.asCustom(getOrCreateLogsRow()))
                items.add(UItem.asCustom(getOrCreateHeapRow()))
            }
        }
        items.add(UItem.asButton(BUTTON_COPY_SYSINFO, R.drawable.inu_tabler_terminal_2, LocaleController.getString(R.string.InuLogsCopySystemInfo)))
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
        applyListPadding()

        return root
    }

    override fun onInsets(left: Int, top: Int, right: Int, bottom: Int) {
        bottomInset = bottom
        updateWrapper?.setPadding(0, 0, 0, bottom)
        applyListPadding()
    }

    private fun applyListPadding() {
        val lv = listView ?: return
        val barHeight = if (SharedConfig.isAppUpdateAvailable()) dp(44f) else 0
        lv.setPadding(lv.paddingLeft, lv.paddingTop, lv.paddingRight, bottomInset + barHeight)
    }

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

            TOGGLE_LOGS_ENABLED -> {
                val new = !LogsHelper.isEnabled()
                LogsHelper.setEnabled(new)
                (view as? TextCheckCell)?.isChecked = new
                if (new) refreshLogsSize()
                listView?.adapter?.update(true)
            }

            BUTTON_COPY_SYSINFO -> {
                AndroidUtilities.addToClipboard(SystemInfo.build())
                BulletinFactory.of(this).createCopyBulletin(
                    LocaleController.getString(R.string.InuLogsSystemInfoCopied)
                ).show()
            }

            BUTTON_EXPORT -> launchExport()
            BUTTON_IMPORT -> launchImport()
            BUTTON_CLOUD_SYNC -> presentFragment(CloudSyncActivity())
            BUTTON_CACHE_MANAGEMENT -> presentFragment(CacheManagementSettingsActivity())
        }
    }

    override fun onFragmentCreate(): Boolean {
        val ok = super.onFragmentCreate()
        val global = NotificationCenter.getGlobalInstance()
        global.addObserver(this, NotificationCenter.appUpdateAvailable)
        global.addObserver(this, NotificationCenter.appUpdateLoading)
        val acct = NotificationCenter.getInstance(UserConfig.selectedAccount)
        acct.addObserver(this, NotificationCenter.fileLoadProgressChanged)
        acct.addObserver(this, NotificationCenter.fileLoaded)
        acct.addObserver(this, NotificationCenter.fileLoadFailed)
        return ok
    }

    override fun onFragmentDestroy() {
        val global = NotificationCenter.getGlobalInstance()
        global.removeObserver(this, NotificationCenter.appUpdateAvailable)
        global.removeObserver(this, NotificationCenter.appUpdateLoading)
        val acct = NotificationCenter.getInstance(UserConfig.selectedAccount)
        acct.removeObserver(this, NotificationCenter.fileLoadProgressChanged)
        acct.removeObserver(this, NotificationCenter.fileLoaded)
        acct.removeObserver(this, NotificationCenter.fileLoadFailed)
        super.onFragmentDestroy()
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        val ul = updateLayout ?: return
        val acct = UserConfig.selectedAccount
        when (id) {
            NotificationCenter.appUpdateAvailable -> {
                val animated = args.getOrNull(0) as? Boolean ?: true
                ul.updateAppUpdateViews(acct, animated)
                applyListPadding()
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
        opts.add(R.drawable.msg_list, LocaleController.getString(R.string.InuLogsShareByCategory)) {
            showCategoryPicker()
        }
        opts.setGravity(Gravity.END).show()
    }

    private fun showCategoryPicker() {
        val activity = parentActivity as? LaunchActivity ?: return
        val categories = LogsHelper.availableCategories()
        if (categories.isEmpty()) {
            BulletinFactory.of(this).createErrorBulletin(
                LocaleController.getString(R.string.InuLogsNoCategories)
            ).show()
            return
        }
        val checked = BooleanArray(categories.size) { true }
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        categories.forEachIndexed { i, category ->
            list.addView(CheckBoxCell(activity, 1).apply {
                setText(category, "", true, i != categories.lastIndex)
                setOnClickListener {
                    checked[i] = !checked[i]
                    setChecked(checked[i], true)
                }
            })
        }
        AlertDialog.Builder(activity)
            .setTitle(LocaleController.getString(R.string.InuLogsShareByCategory))
            .setView(list)
            .setPositiveButton(LocaleController.getString(R.string.InuLogsShare)) { _, _ ->
                val selected = categories.filterIndexed { i, _ -> checked[i] }.toSet()
                if (selected.isEmpty()) return@setPositiveButton
                LogsHelper.shareCategories(activity, selected, ::onShareDone)
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show()
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
        row.addView(buildLogsIconButton(ctx, R.drawable.msg_calls_minimize, R.string.InuLogsMakeHeapDump) {
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
        // let the dialog draw a frame before the (blocking) dump freezes the UI thread
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

    private fun launchExport() {
        Utilities.globalQueue.postRunnable {
            val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(AndroidUtilities.getCacheDir(), "$date.inu-settings.json")
            val err: String? = try {
                file.parentFile?.mkdirs()
                file.writeText(SettingsBackupHelper.export(), Charsets.UTF_8)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            AndroidUtilities.runOnUIThread {
                if (err != null) {
                    BulletinFactory.of(this).createErrorBulletin(
                        LocaleController.formatString(R.string.InuBackupExportError, err)
                    ).show()
                    return@runOnUIThread
                }
                openSharePicker(file)
            }
        }
    }

    private fun openSharePicker(file: File) {
        val ctx = parentActivity ?: return
        val account = accountInstance
        val sheet = object : ShareAlert(ctx, null, null, false, null, false) {
            override fun onSend(
                dids: LongSparseArray<TLRPC.Dialog>,
                count: Int,
                topic: TLRPC.TL_forumTopic?,
                showToast: Boolean
            ) {
                for (i in 0 until dids.size()) {
                    val did = dids.keyAt(i)
                    SendMessagesHelper.prepareSendingDocument(
                        account, file.absolutePath, file.absolutePath, null, null,
                        "application/json", did,
                        null, null, null, null, null,
                        true, 0, null, null, false,
                    )
                }
                if (dids.size() == 1) openChat(dids.keyAt(0))
            }
        }
        showDialog(sheet)
    }

    private fun openChat(did: Long) {
        val args = Bundle().apply {
            putBoolean("scrollToTopOnResume", true)
            when {
                DialogObject.isEncryptedDialog(did) -> putInt("enc_id", DialogObject.getEncryptedChatId(did))
                DialogObject.isUserDialog(did) -> putLong("user_id", did)
                else -> putLong("chat_id", -did)
            }
        }
        if (messagesController.checkCanOpenChat(args, this)) {
            presentFragment(ChatActivity(args))
        }
    }

    private fun launchImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "*/*"))
        }
        try {
            startActivityForResult(intent, REQ_IMPORT)
        } catch (e: Exception) {
            BulletinFactory.of(this).createErrorBulletin(e.message ?: "").show()
        }
    }

    override fun onActivityResultFragment(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val ctx = context ?: parentActivity ?: return
        if (requestCode == REQ_IMPORT) {
            Utilities.globalQueue.postRunnable {
                val text = try {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (_: Exception) {
                    null
                }
                AndroidUtilities.runOnUIThread {
                    if (text == null) {
                        BulletinFactory.of(this).createErrorBulletin(
                            LocaleController.getString(R.string.InuBackupImportBadFormat)
                        ).show()
                        return@runOnUIThread
                    }
                    SettingsBackupHelper.showImportConfirm(this, text)
                }
            }
        }
    }

    companion object {
        private val TOGGLE_AUTO_UPDATE_CHECK = InuUtils.generateId()
        private val TOGGLE_UPDATES_INCLUDE_BETA = InuUtils.generateId()
        private val TOGGLE_LOGS_ENABLED = InuUtils.generateId()
        private val BUTTON_COPY_SYSINFO = InuUtils.generateId()
        private val BUTTON_EXPORT = InuUtils.generateId()
        private val BUTTON_IMPORT = InuUtils.generateId()
        private val BUTTON_CLOUD_SYNC = InuUtils.generateId()
        private val BUTTON_CACHE_MANAGEMENT = InuUtils.generateId()

        private const val REQ_IMPORT = 31002

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "additional",
            titleRes = R.string.InuAdditional,
            iconRes = R.drawable.inu_tabler_device_floppy,
            factory = ::AdditionalSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("auto-update-check", R.string.InuAutoUpdateCheck, TOGGLE_AUTO_UPDATE_CHECK),
                SearchRegistry.Entry("updates-include-beta", R.string.InuUpdatesIncludeBeta, TOGGLE_UPDATES_INCLUDE_BETA),
                SearchRegistry.Entry("backup-export", R.string.InuBackupExport, BUTTON_EXPORT),
                SearchRegistry.Entry("backup-import", R.string.InuBackupImport, BUTTON_IMPORT),
                SearchRegistry.Entry("cloud-sync", R.string.InuCloudSync, BUTTON_CLOUD_SYNC),
                SearchRegistry.Entry("additional-cache-management", R.string.InuCacheManagement, BUTTON_CACHE_MANAGEMENT),
            ),
        )
    }
}
