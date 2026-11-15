package desu.inugram.ui.settings

import android.view.View
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.chat.RegexFilterHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class RegexFilterEditActivity(
    private var filterId: String?,
    private val scopeDialogId: Long?,
    prefillPattern: String? = null,
) : SettingsPageActivity() {

    private val existing = filterId?.let { RegexFilterHelper.getFilter(it) }
    private var enabled: Boolean = existing?.enabled ?: true
    private var caseInsensitive: Boolean = existing?.caseInsensitive ?: true
    private var reversed: Boolean = existing?.reversed ?: false
    private var editor: RegexEditorCell? = null
    private val initialName = existing?.name.orEmpty()
    private val initialPattern = prefillPattern ?: existing?.pattern.orEmpty()
    private val initialAction = existing?.action ?: RegexFilterHelper.ACTION_BLOCK
    private val persistRunnable = Runnable { persist() }

    private val isNew get() = filterId == null

    override fun getTitle(): CharSequence =
        LocaleController.getString(if (filterId == null) R.string.InuRegexFilterAdd else R.string.InuRegexFilterEditTitle)

    override fun onFragmentDestroy() {
        if (!isNew) {
            AndroidUtilities.cancelRunOnUIThread(persistRunnable)
            persist()
        }
        super.onFragmentDestroy()
    }

    private fun editorCell(): RegexEditorCell = editor ?: RegexEditorCell(
        context, initialName, initialPattern, initialAction,
    ) {
        if (!isNew) {
            AndroidUtilities.cancelRunOnUIThread(persistRunnable)
            AndroidUtilities.runOnUIThread(persistRunnable, 700)
        }
    }.also {
        it.caseInsensitive = caseInsensitive
        it.reversed = reversed
        it.onActionChanged = { listView?.adapter?.update(true) }
        editor = it
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val cell = editorCell()
        items.add(UItem.asCustom(cell))
        items.add(UItem.asShadow(null))
        items.add(
            UItem.asCheck(TOGGLE_ENABLED, LocaleController.getString(R.string.InuRegexRuleEnabled))
                .setChecked(enabled)
        )
        items.add(
            UItem.asCheck(TOGGLE_CASE_INSENSITIVE, LocaleController.getString(R.string.InuRegexCaseInsensitive))
                .setChecked(caseInsensitive)
        )
        if (cell.action == RegexFilterHelper.ACTION_BLOCK) {
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_REVERSED,
                    R.string.InuRegexReversed,
                    R.string.InuRegexReversedInfo,
                    reversed,
                )
            )
        }
        items.add(UItem.asShadow(null))
        if (isNew) {
            items.add(UItem.asButton(BUTTON_SAVE, LocaleController.getString(R.string.Save)))
        } else {
            items.add(UItem.asButton(BUTTON_DELETE, LocaleController.getString(R.string.Delete)))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_ENABLED -> {
                enabled = !enabled
                persist()
                (view as? TextCheckCell)?.isChecked = enabled
            }

            TOGGLE_CASE_INSENSITIVE -> {
                caseInsensitive = !caseInsensitive
                editor?.caseInsensitive = caseInsensitive
                persist()
                (view as? TextCheckCell)?.isChecked = caseInsensitive
            }

            TOGGLE_REVERSED -> {
                reversed = !reversed
                editor?.reversed = reversed
                persist()
                (view as? NotificationsCheckCell)?.isChecked = reversed
            }

            BUTTON_SAVE -> saveNew()

            BUTTON_DELETE -> {
                AndroidUtilities.cancelRunOnUIThread(persistRunnable)
                filterId?.let { RegexFilterHelper.removeFilter(it) }
                filterId = null
                finishFragment()
            }
        }
    }

    // entiny: skip half-typed patterns, a trailing "|" would match every message
    private fun persist() {
        val id = filterId ?: return
        val cell = editor ?: return
        if (cell.patternText.isBlank() || cell.error() != null) return
        RegexFilterHelper.updateFilter(id, cell.patternText, enabled, caseInsensitive, effectiveReversed(cell), cell.action, cell.nameText)
    }

    private fun effectiveReversed(cell: RegexEditorCell) = reversed && cell.action == RegexFilterHelper.ACTION_BLOCK

    private fun saveNew() {
        val cell = editor ?: return
        if (cell.patternText.isBlank()) return
        cell.error()?.let {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, it).show()
            return
        }
        val id = RegexFilterHelper.addFilter(
            cell.patternText, scopeDialogId, caseInsensitive, effectiveReversed(cell), cell.action, cell.nameText,
        ).id
        if (!enabled) {
            RegexFilterHelper.updateFilter(id, cell.patternText, false, caseInsensitive, effectiveReversed(cell), cell.action, cell.nameText)
        }
        finishFragment()
    }

    companion object {
        private val TOGGLE_ENABLED = InuUtils.generateId()
        private val TOGGLE_CASE_INSENSITIVE = InuUtils.generateId()
        private val TOGGLE_REVERSED = InuUtils.generateId()
        private val BUTTON_DELETE = InuUtils.generateId()
        private val BUTTON_SAVE = InuUtils.generateId()
    }
}
