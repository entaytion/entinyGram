package desu.inugram.ui.settings

import android.view.View
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.chat.RegexFilterHelper
import desu.inugram.ui.showInputDialog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

/**
 * Add/edit screen for a single regex filter. [filterId] null = creating a new filter (not
 * persisted until a non-blank pattern is set); [scopeDialogId] null = global/shared filter,
 * otherwise scoped to that chat. Existing filters ignore [scopeDialogId] (their scope is fixed
 * at creation).
 */
class RegexFilterEditActivity(
    private var filterId: String?,
    private val scopeDialogId: Long?,
    prefillPattern: String? = null,
) : SettingsPageActivity() {

    private val existing = filterId?.let { RegexFilterHelper.getFilter(it) }
    private var pattern: String = prefillPattern ?: existing?.pattern.orEmpty()
    private var enabled: Boolean = existing?.enabled ?: true
    private var caseInsensitive: Boolean = existing?.caseInsensitive ?: true
    private var reversed: Boolean = existing?.reversed ?: false

    override fun getTitle(): CharSequence =
        LocaleController.getString(if (filterId == null) R.string.InuRegexFilterAdd else R.string.InuRegexFilterEditTitle)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asButton(
                BUTTON_PATTERN,
                LocaleController.getString(R.string.InuRegexPattern),
                pattern.ifBlank { LocaleController.getString(R.string.InuRegexPatternEmpty) },
            )
        )
        items.add(UItem.asShadow(null))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_ENABLED,
                R.string.InuRegexFilterEnabled,
                R.string.InuRegexFilterEnabledInfo,
                enabled,
            )
        )
        items.add(
            UItem.asCheck(TOGGLE_CASE_INSENSITIVE, LocaleController.getString(R.string.InuRegexCaseInsensitive))
                .setChecked(caseInsensitive)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_REVERSED,
                R.string.InuRegexReversed,
                R.string.InuRegexReversedInfo,
                reversed,
            )
        )
        items.add(UItem.asShadow(null))
        if (filterId != null) {
            items.add(UItem.asButton(BUTTON_DELETE, LocaleController.getString(R.string.Delete)))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_PATTERN -> showInputDialog(
                this,
                LocaleController.getString(R.string.InuRegexPattern),
                initialText = pattern,
                selectAll = true,
            ) { text ->
                if (text.isBlank()) return@showInputDialog false
                pattern = text
                persist()
                listView?.adapter?.update(true)
                true
            }

            TOGGLE_ENABLED -> {
                enabled = !enabled
                persist()
                (view as? NotificationsCheckCell)?.isChecked = enabled
            }

            TOGGLE_CASE_INSENSITIVE -> {
                caseInsensitive = !caseInsensitive
                persist()
                (view as? TextCheckCell)?.isChecked = caseInsensitive
            }

            TOGGLE_REVERSED -> {
                reversed = !reversed
                persist()
                (view as? NotificationsCheckCell)?.isChecked = reversed
            }

            BUTTON_DELETE -> {
                filterId?.let { RegexFilterHelper.removeFilter(it) }
                finishFragment()
            }
        }
    }

    private fun persist() {
        if (pattern.isBlank()) return
        val id = filterId
        if (id == null) {
            filterId = RegexFilterHelper.addFilter(pattern, scopeDialogId, caseInsensitive, reversed).id
        } else {
            RegexFilterHelper.updateFilter(id, pattern, enabled, caseInsensitive, reversed)
        }
    }

    companion object {
        private val BUTTON_PATTERN = InuUtils.generateId()
        private val TOGGLE_ENABLED = InuUtils.generateId()
        private val TOGGLE_CASE_INSENSITIVE = InuUtils.generateId()
        private val TOGGLE_REVERSED = InuUtils.generateId()
        private val BUTTON_DELETE = InuUtils.generateId()
    }
}
