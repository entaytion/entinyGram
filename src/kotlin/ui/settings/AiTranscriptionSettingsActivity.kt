package desu.inugram.ui.settings

import android.text.InputType
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

/** Independent provider and credentials page for voice transcription. */
class AiTranscriptionSettingsActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAiTranscribeSettings)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val provider = InuConfig.AI_TRANSCRIBE_PROVIDER.value
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiTranscribeProvider)))
        listOf(
            InuConfig.TRANSCRIBE_PROVIDER_GROQ to R.string.InuAiTranscribeProviderGroq,
            InuConfig.TRANSCRIBE_PROVIDER_GEMINI to R.string.InuAiTranscribeProviderGemini,
            InuConfig.TRANSCRIBE_PROVIDER_OPENAI to R.string.InuAiTranscribeProviderOpenAI,
            InuConfig.TRANSCRIBE_PROVIDER_CF to R.string.InuAiTranscribeProviderCF,
            InuConfig.TRANSCRIBE_PROVIDER_CUSTOM to R.string.InuAiTranscribeProviderCustom,
        ).forEach { (id, titleRes) ->
            items.add(UItem.asRadio(PROVIDER_BASE + id, LocaleController.getString(titleRes)).also { it.checked = provider == id })
        }
        items.add(UItem.asShadow(null))

        when (provider) {
            InuConfig.TRANSCRIBE_PROVIDER_GROQ -> keyField(items, R.string.InuAiTranscribeApiKey, InuConfig.AI_TRANSCRIBE_GROQ_KEY.value) { InuConfig.AI_TRANSCRIBE_GROQ_KEY.value = it }
            InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> {
                keyField(items, R.string.InuAiTranscribeApiKey, InuConfig.AI_TRANSCRIBE_GEMINI_KEY.value) { InuConfig.AI_TRANSCRIBE_GEMINI_KEY.value = it }
                keyField(items, R.string.InuAiTranscribeModel, InuConfig.AI_TRANSCRIBE_GEMINI_MODEL.value.ifBlank { "gemini-2.0-flash" }, InputType.TYPE_CLASS_TEXT) { InuConfig.AI_TRANSCRIBE_GEMINI_MODEL.value = it }
            }
            InuConfig.TRANSCRIBE_PROVIDER_OPENAI -> keyField(items, R.string.InuAiTranscribeApiKey, InuConfig.AI_TRANSCRIBE_OPENAI_KEY.value) { InuConfig.AI_TRANSCRIBE_OPENAI_KEY.value = it }
            InuConfig.TRANSCRIBE_PROVIDER_CF -> {
                keyField(items, R.string.InuAiTranscribeAccountId, InuConfig.AI_TRANSCRIBE_CF_ACCOUNT_ID.value) { InuConfig.AI_TRANSCRIBE_CF_ACCOUNT_ID.value = it }
                keyField(items, R.string.InuAiTranscribeApiToken, InuConfig.AI_TRANSCRIBE_CF_API_TOKEN.value) { InuConfig.AI_TRANSCRIBE_CF_API_TOKEN.value = it }
            }
            InuConfig.TRANSCRIBE_PROVIDER_CUSTOM -> {
                keyField(items, R.string.InuAiEndpointUrl, InuConfig.AI_TRANSCRIBE_CUSTOM_URL.value, InputType.TYPE_TEXT_VARIATION_URI) { InuConfig.AI_TRANSCRIBE_CUSTOM_URL.value = it }
                keyField(items, R.string.InuAiTranscribeApiKey, InuConfig.AI_TRANSCRIBE_CUSTOM_KEY.value) { InuConfig.AI_TRANSCRIBE_CUSTOM_KEY.value = it }
                keyField(items, R.string.InuAiTranscribeModel, InuConfig.AI_TRANSCRIBE_CUSTOM_MODEL.value) { InuConfig.AI_TRANSCRIBE_CUSTOM_MODEL.value = it }
            }
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiTranscribePrompt)))
        items.add(UItem.asCustom(BUTTON_PROMPT, AiServiceFieldCell(context!!, LocaleController.getString(R.string.InuAiTranscribePrompt), InuConfig.AI_TRANSCRIBE_PROMPT.value) { InuConfig.AI_TRANSCRIBE_PROMPT.value = it }))
    }

    private fun keyField(items: ArrayList<UItem>, titleRes: Int, value: String, type: Int = InputType.TYPE_TEXT_VARIATION_PASSWORD, onChanged: (String) -> Unit) {
        items.add(UItem.asCustom(InuUtils.generateId(), AiServiceFieldCell(context!!, LocaleController.getString(titleRes), value, type, onChanged = onChanged)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id in PROVIDER_BASE..(PROVIDER_BASE + 4)) {
            InuConfig.AI_TRANSCRIBE_PROVIDER.value = item.id - PROVIDER_BASE
            listView.adapter.update(true)
        }
    }

    companion object {
        private const val PROVIDER_BASE = 23000
        private val BUTTON_PROMPT = InuUtils.generateId()
    }
}
