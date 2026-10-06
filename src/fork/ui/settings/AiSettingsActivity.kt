package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ai.AiComposeHelper
import desu.inugram.helpers.ai.AiProviderStore
import desu.inugram.helpers.ai.AiRolesHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AiSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAiChatTitle)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asButton(
                BUTTON_PROVIDERS,
                R.drawable.inu_tabler_cpu,
                LocaleController.getString(R.string.InuAiProvidersTitle),
                providersSummary(),
            )
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAiProvidersSubtitle)))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiFeatures)))
        items.add(mkTwoLineEntry(ENTRY_VOICE, R.drawable.inu_tabler_microphone, LocaleController.getString(R.string.InuAiTranscribe), voiceSummary()))
        items.add(mkTwoLineEntry(ENTRY_EDITOR, R.drawable.inu_tabler_sparkles, LocaleController.getString(R.string.InuAiEditorTitle), editorSummary()))
        items.add(mkTwoLineEntry(ENTRY_SUMMARY, R.drawable.msg_text_outlined, LocaleController.getString(R.string.InuAiSummary), summarySummary()))
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiRoles)))
        items.add(UItem.asButton(BUTTON_ROLE, LocaleController.getString(R.string.InuAiRoles), roleSummary()))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAiRolesSubtitle)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_PROVIDERS -> presentFragment(AiProvidersSettingsActivity())
            ENTRY_VOICE -> presentFragment(AiVoiceSettingsActivity())
            ENTRY_EDITOR -> presentFragment(AiEditorSettingsActivity())
            ENTRY_SUMMARY -> presentFragment(AiSummarySettingsActivity())
            BUTTON_ROLE -> presentFragment(AiRolesSettingsActivity())
        }
    }

    private fun off() = LocaleController.getString(R.string.PasswordOff)

    private fun status(enabled: Boolean, provider: AiProviderStore.Provider?, model: String): String {
        if (!enabled) return off()
        if (provider == null) return LocaleController.getString(R.string.InuAiProviderNotSet)
        return listOf(provider.name, model).filter { it.isNotBlank() }.joinToString(" • ")
    }

    private fun voiceSummary(): String {
        val p = AiFeatureSupport.effectiveProvider(InuConfig.AI_VOICE_PROVIDER_ID.value, true)
        return status(InuConfig.AI_TRANSCRIBE_ENABLED.value, p, p?.voiceModel.orEmpty())
    }

    private fun editorSummary(): String {
        val e = AiComposeHelper.endpointFor(AiComposeHelper.Feature.EDITOR)
        val provider = AiComposeHelper.providerFor(AiComposeHelper.Feature.EDITOR)
        return status(!InuConfig.HIDE_AI_EDITOR.value, provider.takeIf { e != null }, e?.model.orEmpty())
    }

    private fun summarySummary(): String {
        val e = AiComposeHelper.endpointFor(AiComposeHelper.Feature.SUMMARY)
        val provider = AiComposeHelper.providerFor(AiComposeHelper.Feature.SUMMARY)
        return status(InuConfig.AI_SUMMARY_ENABLED.value, provider.takeIf { e != null }, e?.model.orEmpty())
    }

    private fun providersSummary(): String {
        val chat = AiProviderStore.chatProvider()?.name
        val voice = AiProviderStore.voiceProvider()?.name
        return when {
            chat == null && voice == null -> LocaleController.getString(R.string.InuAiProviderNotSet)
            chat == voice -> chat.orEmpty()
            else -> listOfNotNull(chat, voice).joinToString(" • ")
        }
    }

    private fun roleSummary(): String =
        AiRolesHelper.activeRoleText().trim().ifEmpty { LocaleController.getString(R.string.InuAiRolesAssistant) }

    companion object {
        private val BUTTON_PROVIDERS = InuUtils.generateId()
        private val ENTRY_VOICE = InuUtils.generateId()
        private val ENTRY_EDITOR = InuUtils.generateId()
        private val ENTRY_SUMMARY = InuUtils.generateId()
        private val BUTTON_ROLE = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "ai-compose",
            titleRes = R.string.InuAiChatTitle,
            iconRes = R.drawable.inu_tabler_sparkles,
            factory = ::AiSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("ai-providers", R.string.InuAiProvidersTitle, BUTTON_PROVIDERS),
                SearchRegistry.Entry("ai-feature-voice", R.string.InuAiTranscribe, ENTRY_VOICE),
                SearchRegistry.Entry("ai-feature-editor", R.string.InuAiEditorTitle, ENTRY_EDITOR),
                SearchRegistry.Entry("ai-feature-summary", R.string.InuAiSummary, ENTRY_SUMMARY),
            ),
        )
    }
}
