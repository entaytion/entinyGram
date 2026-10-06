package desu.inugram.ui.settings

import android.content.Context
import android.view.View
import android.widget.EditText
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.pillstack.PillCurrencies
import desu.inugram.helpers.pillstack.RateInstances
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import java.util.Locale

// entiny: full-screen searchable currency picker; replaces the old chained RadioItemOptions modals for rate pairs.
class CurrencyPickerActivity : SettingsPageActivity() {

    var pickerMode: String = MODE_TARGET
    var currentCode: String = ""
    var excludeCode: String = ""

    private val codesById = HashMap<Int, String>()
    private var query: String = ""

    override fun getTitle(): CharSequence =
        if (pickerMode == MODE_BASE) LocaleController.getString(R.string.InuPillStackRateBase)
        else LocaleController.getString(R.string.InuPillStackRateTarget)

    override fun createView(context: Context): View {
        return super.createView(context).also {
            val searchItem = actionBar.createMenu().addItem(0, R.drawable.outline_header_search)
                .setIsSearchField(true)
            searchItem.setSearchFieldHint(LocaleController.getString(R.string.Search))
            searchItem.setActionBarMenuItemSearchListener(object : ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                override fun onTextChanged(searchField: EditText) {
                    query = searchField.text.toString().lowercase(Locale.getDefault())
                    // entiny: ids regenerate per filter pass -- animated diff would crossfade the whole list on every keystroke
                    listView?.adapter?.update(false)
                }
            })
        }
    }

    private fun allCodes(): List<String> =
        if (pickerMode == MODE_BASE) RateInstances.getBases()
        else PillCurrencies.getTargetCurrencies(excludeCode).toList()

    private fun labelFor(code: String): CharSequence =
        if (pickerMode == MODE_BASE) RateInstances.getBaseLabel(code)
        else PillCurrencies.getTargetCurrencyLabel(code)

    private fun matches(code: String): Boolean {
        if (query.isEmpty()) return true
        val haystack = code.lowercase(Locale.getDefault()) + " " + labelFor(code).toString().lowercase(Locale.getDefault())
        return haystack.contains(query)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        codesById.clear()
        val isBase = pickerMode == MODE_BASE
        for (code in allCodes()) {
            if (!matches(code)) continue
            val id = InuUtils.generateId()
            codesById[id] = code
            val label = labelFor(code)
            val value = if (isBase || code == PillCurrencies.AUTO) null else code
            items.add(UItem.asRadio(id, label, value).also { it.checked = code == currentCode })
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val code = codesById[item.id] ?: return
        val callback = onPicked
        onPicked = null
        finishFragment()
        callback?.invoke(code)
    }

    companion object {
        const val MODE_BASE = "base"
        const val MODE_TARGET = "target"

        // entiny: transient hand-off only; both picker and caller live in the same UI stack
        var onPicked: ((String) -> Unit)? = null

        @JvmStatic
        fun present(from: BaseFragment, mode: String, current: String?, exclude: String?, onPicked: (String) -> Unit) {
            val picker = CurrencyPickerActivity().apply {
                pickerMode = mode
                currentCode = current.orEmpty()
                excludeCode = exclude.orEmpty()
            }
            Companion.onPicked = onPicked
            from.presentFragment(picker)
        }
    }
}
