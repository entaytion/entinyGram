package desu.inugram.ui.settings

import android.text.InputType
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.translate.engine.EntinyTranslate
import desu.inugram.helpers.translate.engine.TranslationProviders
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

/** Provider selection and credentials for third-party chat translation. */
class TranslateProviderSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuTranslateProvider)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val provider = InuConfig.TRANSLATE_PROVIDER.value
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuTranslateProviderSection)))
        items.add(UItem.asRadio(PROVIDER_BASE + TranslationProviders.PROVIDER_TELEGRAM, LocaleController.getString(R.string.InuTranslateProviderTelegram)).also { it.checked = provider == TranslationProviders.PROVIDER_TELEGRAM })
        for (p in TranslationProviders.all) {
            items.add(UItem.asRadio(PROVIDER_BASE + p.id, LocaleController.getString(p.nameRes)).also { it.checked = provider == p.id })
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuTranslateProviderInfo)))

        when (provider) {
            TranslationProviders.PROVIDER_DEEPL -> keyField(
                items,
                LocaleController.getString(R.string.InuTranslateDeepLApiKey),
                InuConfig.TRANSLATE_DEEPL_KEY.value,
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
            ) { InuConfig.TRANSLATE_DEEPL_KEY.value = it }

            TranslationProviders.PROVIDER_LLM -> {
                keyField(items, LocaleController.getString(R.string.InuTranslateLlmEndpointUrl), InuConfig.TRANSLATE_LLM_URL.value, InputType.TYPE_TEXT_VARIATION_URI) { InuConfig.TRANSLATE_LLM_URL.value = it }
                keyField(items, LocaleController.getString(R.string.InuTranslateLlmApiKey), InuConfig.TRANSLATE_LLM_KEY.value, InputType.TYPE_TEXT_VARIATION_PASSWORD) { InuConfig.TRANSLATE_LLM_KEY.value = it }
                keyField(items, LocaleController.getString(R.string.InuTranslateLlmModel), InuConfig.TRANSLATE_LLM_MODEL.value) { InuConfig.TRANSLATE_LLM_MODEL.value = it }
                keyField(items, LocaleController.getString(R.string.InuTranslateLlmPrompt), InuConfig.TRANSLATE_LLM_PROMPT.value) { InuConfig.TRANSLATE_LLM_PROMPT.value = it }
            }

            TranslationProviders.PROVIDER_YANDEX -> keyField(
                items,
                LocaleController.getString(R.string.InuTranslateYandexApiKey),
                InuConfig.TRANSLATE_YANDEX_KEY.value,
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
            ) { InuConfig.TRANSLATE_YANDEX_KEY.value = it }

            TranslationProviders.PROVIDER_MICROSOFT -> {
                keyField(items, LocaleController.getString(R.string.InuTranslateMicrosoftApiKey), InuConfig.TRANSLATE_MICROSOFT_KEY.value, InputType.TYPE_TEXT_VARIATION_PASSWORD) { InuConfig.TRANSLATE_MICROSOFT_KEY.value = it }
                keyField(items, LocaleController.getString(R.string.InuTranslateMicrosoftRegion), InuConfig.TRANSLATE_MICROSOFT_REGION.value) { InuConfig.TRANSLATE_MICROSOFT_REGION.value = it }
            }
        }
    }

    private fun keyField(items: ArrayList<UItem>, title: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT, onChanged: (String) -> Unit) {
        items.add(UItem.asCustom(InuUtils.generateId(), AiServiceFieldCell(context!!, title, value, type, onChanged = onChanged)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id in PROVIDER_BASE..(PROVIDER_BASE + 10)) {
            val newProvider = item.id - PROVIDER_BASE
            if (newProvider != InuConfig.TRANSLATE_PROVIDER.value) {
                InuConfig.TRANSLATE_PROVIDER.value = newProvider
                // retry messages that failed under the previous provider with the new one
                EntinyTranslate.onProviderChanged()
            }
            listView.adapter.update(true)
        }
    }

    companion object {
        private const val PROVIDER_BASE = 24000
    }
}
