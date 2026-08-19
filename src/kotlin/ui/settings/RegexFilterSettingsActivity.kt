package desu.inugram.ui.settings

import android.content.DialogInterface
import android.text.InputType
import android.view.View
import android.widget.EditText
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class RegexFilterSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuRegexFilter)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuRegexFilter)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_REGEX_FILTER_ENABLED,
                R.string.InuRegexFilterEnabled,
                R.string.InuRegexFilterEnabledInfo,
                InuConfig.REGEX_FILTER_ENABLED.value,
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_REGEX_FILTER_MODE,
                LocaleController.getString(R.string.InuRegexFilterMode),
                when (InuConfig.REGEX_FILTER_MODE.value) {
                    InuConfig.RegexFilterModeItem.SPOILER -> LocaleController.getString(R.string.InuRegexFilterModeSpoiler)
                    else -> LocaleController.getString(R.string.InuRegexFilterModeHide)
                }
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_REGEX_PATTERNS,
                LocaleController.getString(R.string.InuRegexPatterns),
                InuConfig.REGEX_FILTER_PATTERNS.value
            )
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuRegexFilterHint)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_REGEX_FILTER_ENABLED -> {
                val new = InuConfig.REGEX_FILTER_ENABLED.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                refreshDialogs()
            }

            BUTTON_REGEX_FILTER_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuRegexFilterModeHide),
                    LocaleController.getString(R.string.InuRegexFilterModeSpoiler),
                ),
                InuConfig.REGEX_FILTER_MODE.value,
            ) { which ->
                InuConfig.REGEX_FILTER_MODE.value = which
                softRebuild()
                refreshDialogs()
            }

            BUTTON_REGEX_PATTERNS -> openPatternsDialog()
        }
    }

    private fun openPatternsDialog() {
        val context = context ?: return
        val editText = EditText(context).apply {
            setLines(5)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_dialogTextBlack))
            setHintTextColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_dialogTextHint))
            setText(InuConfig.REGEX_FILTER_PATTERNS.value)
            setSelection(text.length)
        }

        val builder = AlertDialog.Builder(context)
            .setTitle(LocaleController.getString(R.string.InuRegexPatterns))
            .setMessage(LocaleController.getString(R.string.InuRegexPatternsDialogHint))
            .setView(editText)
            .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                InuConfig.REGEX_FILTER_PATTERNS.value = editText.text.toString().trim()
                softRebuild()
                refreshDialogs()
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)

        showDialog(builder.create())
    }

    private fun refreshDialogs() {
        NotificationCenter.getInstance(currentAccount).postNotificationName(
            NotificationCenter.updateInterfaces,
            MessagesController.UPDATE_MASK_ALL,
        )
    }

    companion object {
        private val TOGGLE_REGEX_FILTER_ENABLED = InuUtils.generateId()
        private val BUTTON_REGEX_FILTER_MODE = InuUtils.generateId()
        private val BUTTON_REGEX_PATTERNS = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "regex-filter",
            titleRes = R.string.InuRegexFilter,
            iconRes = R.drawable.msg_block2,
            factory = ::RegexFilterSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("regex-filter-enabled", R.string.InuRegexFilterEnabled, TOGGLE_REGEX_FILTER_ENABLED),
                SearchRegistry.Entry("regex-filter-mode", R.string.InuRegexFilterMode, BUTTON_REGEX_FILTER_MODE),
                SearchRegistry.Entry("regex-patterns", R.string.InuRegexPatterns, BUTTON_REGEX_PATTERNS),
            ),
        )
    }
}
