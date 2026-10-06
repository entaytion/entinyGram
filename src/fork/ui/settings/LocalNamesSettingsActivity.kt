package desu.inugram.ui.settings

import android.content.ClipboardManager
import android.content.Context
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.DialogPicker
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.profile.LocalNameHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class LocalNamesSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuLocalNames)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asCheck(TOGGLE_ENABLED, LocaleController.getString(R.string.InuLocalNames))
                .setChecked(InuConfig.LOCAL_NAMES.value)
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuLocalNamesInfo)))

        val ids = LocalNameHelper.entries(currentAccount)
        if (ids.isNotEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuLocalNamesList)))
            ids.forEachIndexed { index, dialogId ->
                items.add(
                    UItem.asButton(
                        ENTRY_BASE + index,
                        LocalNameHelper.displayName(currentAccount, dialogId),
                        LocalNameHelper.summary(currentAccount, dialogId),
                    )
                )
            }
            items.add(UItem.asShadow(null))
        }
        items.add(UItem.asButton(BUTTON_ADD, R.drawable.msg_add, LocaleController.getString(R.string.InuLocalNamesAdd)))
        items.add(UItem.asButton(BUTTON_EXPORT, R.drawable.msg_share, LocaleController.getString(R.string.InuLocalNamesExport)))
        items.add(UItem.asButton(BUTTON_IMPORT, R.drawable.msg_copy, LocaleController.getString(R.string.InuLocalNamesImport)))
        if (ids.isNotEmpty()) {
            items.add(UItem.asButton(BUTTON_CLEAR, R.drawable.msg_delete, LocaleController.getString(R.string.InuLocalNamesClear)))
        }
    }

    private fun refresh() {
        listView?.adapter?.update(true)
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when {
            item.id == TOGGLE_ENABLED -> {
                val new = InuConfig.LOCAL_NAMES.toggle()
                (view as? TextCheckCell)?.isChecked = new
                LocalNameHelper.onToggle()
            }

            item.id == BUTTON_ADD -> DialogPicker.pick(this) { dialogId ->
                // entiny: wait for the picker to close before showing the dialog on this page
                AndroidUtilities.runOnUIThread({
                    LocalNameHelper.showEditor(this, currentAccount, dialogId) { refresh() }
                }, 300)
            }

            item.id == BUTTON_EXPORT -> LocalNameHelper.shareExport(this, currentAccount)

            item.id == BUTTON_IMPORT -> importFromClipboard()

            item.id == BUTTON_CLEAR -> parentActivity?.let { ctx ->
                showDialog(
                    AlertDialog.Builder(ctx, resourceProvider)
                        .setTitle(LocaleController.getString(R.string.InuLocalNamesClear))
                        .setMessage(LocaleController.getString(R.string.InuLocalNamesClearConfirm))
                        .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                        .setPositiveButton(LocaleController.getString(R.string.Delete)) { _, _ ->
                            LocalNameHelper.clear(currentAccount)
                            refresh()
                        }
                        .create()
                )
            }

            item.id >= ENTRY_BASE -> {
                val dialogId = LocalNameHelper.entries(currentAccount).getOrNull(item.id - ENTRY_BASE) ?: return
                val opts = ItemOptions.makeOptions(this, view)
                opts.add(R.drawable.msg_edit, LocaleController.getString(R.string.Edit)) {
                    LocalNameHelper.showEditor(this, currentAccount, dialogId) { refresh() }
                }
                opts.add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete)) {
                    LocalNameHelper.remove(currentAccount, dialogId)
                    refresh()
                    BulletinFactory.of(this)
                        .createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.InuLocalNameRemoved))
                        .show()
                }
                opts.show()
            }
        }
    }

    private fun importFromClipboard() {
        val clipboard = ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ApplicationLoader.applicationContext)?.toString()
        val count = try {
            if (text.isNullOrBlank()) -1 else LocalNameHelper.importJson(currentAccount, text)
        } catch (e: Throwable) {
            -1
        }
        val message = if (count < 0) {
            LocaleController.getString(R.string.InuLocalNamesImportFailed)
        } else {
            LocaleController.formatString(R.string.InuLocalNamesImported, count)
        }
        BulletinFactory.of(this).createSimpleBulletin(if (count < 0) R.raw.error else R.raw.contact_check, message).show()
        refresh()
    }

    companion object {
        private val TOGGLE_ENABLED = InuUtils.generateId()
        private val BUTTON_ADD = InuUtils.generateId()
        private val BUTTON_EXPORT = InuUtils.generateId()
        private val BUTTON_IMPORT = InuUtils.generateId()
        private val BUTTON_CLEAR = InuUtils.generateId()
        private const val ENTRY_BASE = 25000
    }
}
