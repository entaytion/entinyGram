package desu.inugram.ui.settings

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.helpers.InuDatabaseHelper
import desu.inugram.helpers.chat.SavedMessagesHelper
import desu.inugram.ui.AyuMessageHistoryActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserObject
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class ArchiveCacheActivity(
    private val edited: Boolean,
) : SettingsPageActivity() {
    private var stats = emptyList<InuDatabaseHelper.DialogCacheStat>()
    private var loading = true
    private var clearing = false
    private val selected = LinkedHashSet<Long>()
    private var selecting = false

    override fun getTitle(): CharSequence = categoryTitle()

    private fun categoryTitle() = LocaleController.getString(if (edited) R.string.InuCacheEditsCategory else R.string.InuCacheDeletedCategory)

    private fun dialogTitle(id: Long): String = when (id) {
        0L, userConfig.clientUserId -> LocaleController.getString(R.string.SavedMessages)
        else -> messagesController.getUser(id)?.let { UserObject.getUserName(it) }
            ?: messagesController.getChat(-id)?.title ?: "ID $id"
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
    }

    private fun refreshStats() {
        loading = true
        val storage = MessagesStorage.getInstance(currentAccount)
        storage.storageQueue.postRunnable {
            val result = runCatching {
                val db = checkNotNull(storage.database)
                InuDatabaseHelper.getDeletedMessagesStats(db, edited)
            }
            AndroidUtilities.runOnUIThread {
                if (isFinished) return@runOnUIThread
                result.onSuccess { stats = it }.onFailure {
                    BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.ErrorOccurred)).show()
                }
                selected.retainAll(stats.map { it.dialogId }.toSet())
                loading = false
                listView?.adapter?.update(true)
            }
        }
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        if (loading) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuCacheCalculating)))
            return
        }
        if (stats.isNotEmpty() && !clearing) {
            items.add(UItem.asButton(1, R.drawable.inu_tabler_trash_x, LocaleController.getString(R.string.InuCacheClearAll)))
            if (selecting) {
                val all = selected.size == stats.size
                items.add(UItem.asButton(2, R.drawable.msg_select, LocaleController.getString(if (all) R.string.DeselectAll else R.string.SelectAll)))
                items.add(UItem.asButton(3, R.drawable.msg_cancel, LocaleController.getString(R.string.Cancel)))
            }
        }
        for (stat in stats) {
            items.add(UItem.asCustom(dialogRow(context, stat.dialogId), 60))
        }
        if (stats.isEmpty()) items.add(UItem.asShadow(LocaleController.formatString(R.string.InuCacheEntriesShort, 0)))
    }

    private fun dialogRow(context: Context, id: Long): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = AndroidUtilities.dp(60f)
        setPadding(AndroidUtilities.dp(16f), 0, AndroidUtilities.dp(16f), 0)
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        val avatar = AvatarDrawable()
        val peer: org.telegram.tgnet.TLObject? = if (id >= 0) messagesController.getUser(id) else messagesController.getChat(-id)
        when (peer) {
            is org.telegram.tgnet.TLRPC.User -> avatar.setInfo(peer)
            is org.telegram.tgnet.TLRPC.Chat -> avatar.setInfo(peer)
            else -> avatar.setInfo(id, dialogTitle(id), null)
        }
        addView(BackupImageView(context).apply {
            setRoundRadius(AndroidUtilities.dp(18f))
            setForUserOrChat(peer, avatar)
        }, LayoutHelper.createLinear(36, 36))
        addView(TextView(context).apply {
            text = dialogTitle(id)
            setTextSize(16f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 12, 0))
        addView(TextView(context).apply {
            text = if (selecting) { if (id in selected) "☑" else "☐" } else if (LocaleController.isRTL) "‹" else "›"
            setTextSize(24f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        }, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        setOnClickListener {
            if (selecting) {
                if (!selected.add(id)) selected.remove(id)
                listView.adapter.update(true)
            } else presentFragment(if (edited) {
                AyuMessageHistoryActivity.forEditedMessagesInDialog(currentAccount, id)
            } else {
                AyuMessageHistoryActivity.forDeletedMessagesInDialog(currentAccount, id)
            })
        }
        setOnLongClickListener {
            if (!clearing) {
                selecting = true
                selected.add(id)
                listView.adapter.update(true)
            }
            true
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (clearing) return
        if (item.id == 2) {
            if (selected.size == stats.size) selected.clear() else selected.addAll(stats.map { it.dialogId })
            return
        }
        if (item.id == 3) {
            selecting = false
            selected.clear()
            return
        }
        if (item.id != 1) return
        val ids = if (selecting) selected.toList() else stats.map { it.dialogId }
        if (ids.isEmpty()) return
        showDialog(AlertDialog.Builder(context)
            .setTitle(LocaleController.getString(R.string.InuCacheClearAll))
            .setMessage("${categoryTitle()} — ${ids.size}")
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.Delete)) { _, _ -> clearDialogs(ids) }
            .create())
    }

    private fun clearDialogs(ids: List<Long>) {
        clearing = true
        listView.adapter.update(true)
        val done = Runnable {
            clearing = false
            selected.clear()
            selecting = false
            if (!isFinished) {
                refreshStats()
                BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete, LocaleController.getString(R.string.InuCacheClearAllDone)).show()
            }
        }
        if (edited) {
            val storage = MessagesStorage.getInstance(currentAccount)
            storage.storageQueue.postRunnable {
                val result = runCatching {
                    val db = checkNotNull(storage.database)
                    InuDatabaseHelper.clearEditHistory(db, ids)
                    SavedMessagesHelper.invalidateMediaCache(currentAccount)
                }
                AndroidUtilities.runOnUIThread {
                    if (result.isSuccess) done.run() else {
                        clearing = false
                        if (!isFinished) {
                            listView.adapter.update(true)
                            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.ErrorOccurred)).show()
                        }
                    }
                }
            }
        } else {
            SavedMessagesHelper.clearCache(currentAccount, ids, deletedOnly = true, onDone = done)
        }
    }
}