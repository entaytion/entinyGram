package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.TranslateController
import org.telegram.ui.Components.TranslateAlert2
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class TranslationTargetActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuTranslationTarget)

    private val languages by lazy { TranslateController.getLanguages() }
    private val suggested by lazy { TranslateController.getSuggestedLanguages(null) }
    private val idToCode = HashMap<Int, String>()

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        idToCode.clear()
        val current = currentValue()
        val app = LocaleController.getInstance().currentLocaleInfo
        val followAppId = 1
        idToCode[followAppId] = ""
        items.add(
            UItem.asRadio(
                followAppId,
                LocaleController.getString(R.string.InuTranslationTargetFollowApp),
                app?.name ?: "",
            ).setChecked(current.isEmpty())
        )

        var nextId = 2
        for (lang in suggested) {
            val code = lang.code ?: continue
            val id = nextId++
            idToCode[id] = code
            val title = lang.displayName
            val subtitle = lang.ownDisplayName.takeIf { it != title } ?: ""
            items.add(UItem.asRadio(id, title, subtitle).setChecked(code == current))
        }
        items.add(UItem.asShadow(null))

        for (lang in languages) {
            val code = lang.code ?: continue
            val id = nextId++
            idToCode[id] = code
            val title = lang.displayName
            val subtitle = lang.ownDisplayName.takeIf { it != title } ?: ""
            items.add(UItem.asRadio(id, title, subtitle).setChecked(code == current))
        }
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val code = idToCode[item.id] ?: return
        select(code)
        listView?.adapter?.update(true)
    }

    private fun currentValue(): String {
        val stored = InuConfig.TRANSLATE_TARGET_LANGUAGE.value
        if (stored.isNotEmpty()) return stored
        if (!MessagesController.getGlobalMainSettings().contains(PREF_KEY)) return ""
        val legacy = TranslateAlert2.getToLanguage().orEmpty()
        if (legacy.isNotEmpty()) InuConfig.TRANSLATE_TARGET_LANGUAGE.value = legacy
        return legacy
    }

    private fun select(newValue: String) {
        if (newValue == currentValue()) return
        InuConfig.TRANSLATE_TARGET_LANGUAGE.value = newValue
        if (newValue.isEmpty()) {
            TranslateAlert2.resetToLanguage()
        } else {
            TranslateAlert2.setToLanguage(newValue)
        }
    }

    companion object {
        private const val PREF_KEY = "translate_to_language"
    }
}

