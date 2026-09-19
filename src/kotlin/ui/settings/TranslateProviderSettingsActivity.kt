package desu.inugram.ui.settings

import android.text.InputType
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.translate.engine.EntinyTranslate
import desu.inugram.helpers.translate.engine.TranslationProviders
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import kotlin.reflect.KMutableProperty0

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
                ::deeplKeyField,
                LocaleController.getString(R.string.InuTranslateDeepLApiKey),
                InuConfig.TRANSLATE_DEEPL_KEY.value,
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
            ) { InuConfig.TRANSLATE_DEEPL_KEY.value = it }

            TranslationProviders.PROVIDER_LLM -> {
                keyField(items, ::llmUrlField, LocaleController.getString(R.string.InuTranslateLlmEndpointUrl), InuConfig.TRANSLATE_LLM_URL.value, InputType.TYPE_TEXT_VARIATION_URI) { InuConfig.TRANSLATE_LLM_URL.value = it }
                keyField(items, ::llmKeyField, LocaleController.getString(R.string.InuTranslateLlmApiKey), InuConfig.TRANSLATE_LLM_KEY.value, InputType.TYPE_TEXT_VARIATION_PASSWORD) { InuConfig.TRANSLATE_LLM_KEY.value = it }
                keyField(items, ::llmModelField, LocaleController.getString(R.string.InuTranslateLlmModel), InuConfig.TRANSLATE_LLM_MODEL.value) { InuConfig.TRANSLATE_LLM_MODEL.value = it }
                keyField(items, ::llmPromptField, LocaleController.getString(R.string.InuTranslateLlmPrompt), InuConfig.TRANSLATE_LLM_PROMPT.value) { InuConfig.TRANSLATE_LLM_PROMPT.value = it }

                // entiny: keep slider instances as fields so rebuilding items does not reset thumb mid-drag
                if (contextSlider == null) contextSlider = SliderCell(
                    context,
                    min = 0f,
                    max = 20f,
                    defaultValue = InuConfig.TRANSLATE_LLM_CONTEXT.default.toFloat(),
                    initialValue = InuConfig.TRANSLATE_LLM_CONTEXT.value.toFloat(),
                    step = 1f,
                    title = LocaleController.getString(R.string.InuTranslateLlmContext),
                    format = { if (it <= 0f) LocaleController.getString(R.string.NotificationsOff) else it.toInt().toString() },
                    onChanged = { InuConfig.TRANSLATE_LLM_CONTEXT.value = it.toInt() },
                )
                items.add(UItem.asCustom(contextSlider))
                items.add(UItem.asShadow(LocaleController.getString(R.string.InuTranslateLlmContextInfo)))

                if (temperatureSlider == null) temperatureSlider = SliderCell(
                    context,
                    min = 0f,
                    max = 1f,
                    defaultValue = InuConfig.TRANSLATE_LLM_TEMPERATURE.default,
                    initialValue = InuConfig.TRANSLATE_LLM_TEMPERATURE.value,
                    step = 0.05f,
                    title = LocaleController.getString(R.string.InuTranslateLlmTemperature),
                    format = { String.format(java.util.Locale.US, "%.2f", it) },
                    onChanged = { InuConfig.TRANSLATE_LLM_TEMPERATURE.value = it },
                )
                items.add(UItem.asCustom(temperatureSlider))
                items.add(UItem.asShadow(LocaleController.getString(R.string.InuTranslateLlmTemperatureInfo)))
            }

            TranslationProviders.PROVIDER_YANDEX -> keyField(
                items,
                ::yandexKeyField,
                LocaleController.getString(R.string.InuTranslateYandexApiKey),
                InuConfig.TRANSLATE_YANDEX_KEY.value,
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
            ) { InuConfig.TRANSLATE_YANDEX_KEY.value = it }

            TranslationProviders.PROVIDER_MICROSOFT -> {
                keyField(items, ::microsoftKeyField, LocaleController.getString(R.string.InuTranslateMicrosoftApiKey), InuConfig.TRANSLATE_MICROSOFT_KEY.value, InputType.TYPE_TEXT_VARIATION_PASSWORD) { InuConfig.TRANSLATE_MICROSOFT_KEY.value = it }
                keyField(items, ::microsoftRegionField, LocaleController.getString(R.string.InuTranslateMicrosoftRegion), InuConfig.TRANSLATE_MICROSOFT_REGION.value) { InuConfig.TRANSLATE_MICROSOFT_REGION.value = it }
            }
        }
    }

    private var contextSlider: SliderCell? = null
    private var temperatureSlider: SliderCell? = null

    private var deeplKeyField: FieldSlot? = null
    private var llmUrlField: FieldSlot? = null
    private var llmKeyField: FieldSlot? = null
    private var llmModelField: FieldSlot? = null
    private var llmPromptField: FieldSlot? = null
    private var yandexKeyField: FieldSlot? = null
    private var microsoftKeyField: FieldSlot? = null
    private var microsoftRegionField: FieldSlot? = null

    private class FieldSlot(val id: Int, val cell: AiServiceFieldCell)

    // entiny: keep one AiServiceFieldCell instance per field across fillItems() rebuilds - it used
    // to allocate a fresh id + cell every time (e.g. on every provider radio tap), which meant the
    // key/URL/model/prompt fields lost cursor position and IME focus on every rebuild. The sliders
    // right above already got this treatment; the text fields never did.
    private fun keyField(items: ArrayList<UItem>, slot: KMutableProperty0<FieldSlot?>, title: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT, onChanged: (String) -> Unit) {
        var s = slot.get()
        if (s == null) {
            s = FieldSlot(InuUtils.generateId(), AiServiceFieldCell(context!!, title, value, type, onChanged = onChanged))
            slot.set(s)
        }
        items.add(UItem.asCustom(s.id, s.cell))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id in PROVIDER_BASE..(PROVIDER_BASE + 10)) {
            val newProvider = item.id - PROVIDER_BASE
            if (newProvider != InuConfig.TRANSLATE_PROVIDER.value) {
                InuConfig.TRANSLATE_PROVIDER.value = newProvider
                EntinyTranslate.onProviderChanged()
                unlockStockTranslateButton()
            }
            listView.adapter.update(true)
        }
    }

    // entiny: enable stock translate buttons automatically so configured provider is immediately accessible
    private fun unlockStockTranslateButton() {
        val controller = MessagesController.getInstance(UserConfig.selectedAccount).translateController
        if (!controller.isContextTranslateEnabled) controller.setContextTranslateEnabled(true)
        if (!controller.isChatTranslateEnabled) controller.setChatTranslateEnabled(true)
    }

    companion object {
        private const val PROVIDER_BASE = 24000
    }
}
