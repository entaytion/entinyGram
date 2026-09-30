package desu.inugram.ui.settings

import android.content.Context
import android.view.View
import android.widget.EditText
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ai.AiModelsHelper
import desu.inugram.helpers.ai.AiProviderStore
import desu.inugram.ui.showInputDialog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import java.util.Locale

class AiModelPickerActivity : SettingsPageActivity() {

    var provider: AiProviderStore.Provider? = null
    var voice: Boolean = false
    var current: String = ""

    private val modelsById = HashMap<Int, String>()
    private var models: List<String> = emptyList()
    private var loading = true
    private var error: String? = null
    private var query: String = ""

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAiModelPickerTitle)

    override fun createView(context: Context): View {
        return super.createView(context).also {
            val searchItem = actionBar.createMenu().addItem(0, R.drawable.outline_header_search).setIsSearchField(true)
            searchItem.setSearchFieldHint(LocaleController.getString(R.string.Search))
            searchItem.setActionBarMenuItemSearchListener(object : ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                override fun onTextChanged(searchField: EditText) {
                    query = searchField.text.toString().lowercase(Locale.getDefault())
                    listView?.adapter?.update(false)
                }
            })
            load()
        }
    }

    private fun load() {
        val p = provider ?: return
        AiModelsHelper.fetchProviderModels(p, voice) { result ->
            loading = false
            models = result.getOrNull().orEmpty()
            error = result.exceptionOrNull()?.message
            listView?.adapter?.update(true)
        }
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        modelsById.clear()
        items.add(UItem.asButton(BUTTON_MANUAL, LocaleController.getString(R.string.InuAiModelEnterManually)))
        items.add(UItem.asShadow(null))
        if (loading) {
            items.add(UItem.asCenterShadow(LocaleController.getString(R.string.InuAiModelsLoading)))
            return
        }
        error?.let { items.add(UItem.asCenterShadow(LocaleController.formatString(R.string.InuAiTranscribeFetchModelsFailed, it))) }
        for (name in models) {
            if (query.isNotEmpty() && !name.lowercase(Locale.getDefault()).contains(query)) continue
            val id = InuUtils.generateId()
            modelsById[id] = name
            items.add(UItem.asRadio(id, name).also { it.checked = name == current })
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id == BUTTON_MANUAL) {
            showInputDialog(this, LocaleController.getString(R.string.InuAiModelEnterManually), initialText = current, selectAll = true) { text ->
                if (text.isBlank()) return@showInputDialog false
                pick(text.trim())
                true
            }
            return
        }
        modelsById[item.id]?.let { pick(it) }
    }

    private fun pick(model: String) {
        val callback = onPicked
        onPicked = null
        finishFragment()
        callback?.invoke(model)
    }

    companion object {
        private val BUTTON_MANUAL = InuUtils.generateId()

        // entiny: transient hand-off only; picker and caller live in the same UI stack
        var onPicked: ((String) -> Unit)? = null

        @JvmStatic
        fun present(from: BaseFragment, provider: AiProviderStore.Provider, voice: Boolean, current: String, onPicked: (String) -> Unit) {
            val picker = AiModelPickerActivity().apply {
                this.provider = provider
                this.voice = voice
                this.current = current
            }
            Companion.onPicked = onPicked
            from.presentFragment(picker)
        }
    }
}
