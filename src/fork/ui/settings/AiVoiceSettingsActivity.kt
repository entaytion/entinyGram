package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.ui.showInputDialog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AiVoiceSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAiTranscribe)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val provider = AiFeatureSupport.effectiveProvider(InuConfig.AI_VOICE_PROVIDER_ID.value, true)
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_ENABLED,
                R.string.InuAiTranscribe,
                R.string.InuAiTranscribeInfo,
                InuConfig.AI_TRANSCRIBE_ENABLED.value,
            )
        )
        items.add(UItem.asShadow(if (provider == null) LocaleController.getString(R.string.InuAiAddProviderFirst) else null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiSectionProvider)))
        items.add(
            UItem.asButton(
                BUTTON_PROVIDER,
                LocaleController.getString(R.string.InuAiFeatureProvider),
                AiFeatureSupport.providerLabel(InuConfig.AI_VOICE_PROVIDER_ID.value, true),
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_MODEL,
                LocaleController.getString(R.string.InuAiTranscribeModel),
                AiFeatureSupport.modelLabel(InuConfig.AI_VOICE_MODEL.value, provider, true),
            ).setEnabled(provider != null)
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiSectionRecognition)))
        items.add(
            UItem.asButton(
                BUTTON_LANGUAGE,
                LocaleController.getString(R.string.InuAiTranscribeLanguage),
                InuConfig.AI_TRANSCRIBE_LANGUAGE.value.ifBlank { LocaleController.getString(R.string.InuAiTranscribeLanguageAuto) },
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_PROMPT,
                LocaleController.getString(R.string.InuAiTranscribePrompt),
                InuConfig.AI_TRANSCRIBE_PROMPT.value.ifBlank { LocaleController.getString(R.string.PasswordOff) },
            )
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAiTranscribePromptInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_ENABLED -> (view as? NotificationsCheckCell)?.isChecked = InuConfig.AI_TRANSCRIBE_ENABLED.toggle()
            BUTTON_PROVIDER -> AiFeatureSupport.pickProvider(this, true, InuConfig.AI_VOICE_PROVIDER_ID.value) {
                InuConfig.AI_VOICE_PROVIDER_ID.value = it
                InuConfig.AI_VOICE_MODEL.value = ""
                listView?.adapter?.update(true)
            }
            BUTTON_MODEL -> AiFeatureSupport.pickModel(
                this,
                AiFeatureSupport.effectiveProvider(InuConfig.AI_VOICE_PROVIDER_ID.value, true),
                true,
                InuConfig.AI_VOICE_MODEL.value,
            ) {
                InuConfig.AI_VOICE_MODEL.value = it
            }
            BUTTON_LANGUAGE -> showInputDialog(
                this,
                LocaleController.getString(R.string.InuAiTranscribeLanguage),
                hint = "en, uk, ru",
                initialText = InuConfig.AI_TRANSCRIBE_LANGUAGE.value,
                selectAll = true,
            ) { text ->
                InuConfig.AI_TRANSCRIBE_LANGUAGE.value = text.trim()
                listView?.adapter?.update(true)
                true
            }
            BUTTON_PROMPT -> showInputDialog(
                this,
                LocaleController.getString(R.string.InuAiTranscribePrompt),
                initialText = InuConfig.AI_TRANSCRIBE_PROMPT.value,
                selectAll = true,
                multiline = true,
            ) { text ->
                InuConfig.AI_TRANSCRIBE_PROMPT.value = text.trim()
                listView?.adapter?.update(true)
                true
            }
        }
    }

    companion object {
        private val TOGGLE_ENABLED = InuUtils.generateId()
        private val BUTTON_PROVIDER = InuUtils.generateId()
        private val BUTTON_MODEL = InuUtils.generateId()
        private val BUTTON_LANGUAGE = InuUtils.generateId()
        private val BUTTON_PROMPT = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "ai-voice",
            titleRes = R.string.InuAiTranscribe,
            iconRes = R.drawable.inu_tabler_microphone,
            factory = ::AiVoiceSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("ai-transcribe-enabled", R.string.InuAiTranscribe, TOGGLE_ENABLED),
                SearchRegistry.Entry("ai-voice-provider", R.string.InuAiFeatureProvider, BUTTON_PROVIDER),
                SearchRegistry.Entry("ai-voice-model", R.string.InuAiTranscribeModel, BUTTON_MODEL),
                SearchRegistry.Entry("ai-transcribe-language", R.string.InuAiTranscribeLanguage, BUTTON_LANGUAGE),
                SearchRegistry.Entry("ai-transcribe-prompt", R.string.InuAiTranscribePrompt, BUTTON_PROMPT),
            ),
        )
    }
}
