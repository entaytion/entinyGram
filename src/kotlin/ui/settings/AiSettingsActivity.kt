package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ai.AiComposeHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

/** AI Chat settings page matching the modern reference design. */
class AiSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAiChatTitle)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val ctx = context ?: return

        // Section: Загальні
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiSectionGeneral)))
        items.add(
            UItem.asButton(
                BUTTON_SERVICES,
                R.drawable.msg_language,
                LocaleController.getString(R.string.InuAiServices),
                activeServiceSummary()
            )
        )
        val composeConfigured = hasComposeCredentials()
        items.add(
            mkTwoLineCheckItem(
                BUTTON_AI_EDITOR,
                R.string.InuHideAiEditor,
                if (composeConfigured) R.string.InuAiEditorButtonInfo
                else R.string.InuAiEditorButtonSetupInfo,
                composeConfigured && !InuConfig.HIDE_AI_EDITOR.value
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_AI_SUMMARY,
                R.string.InuAiSummary,
                R.string.InuAiSummaryInfo,
                InuConfig.AI_SUMMARY_ENABLED.value
            )
        )
        // Only response controls that are implemented by the request pipeline remain here.
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiSectionTemperature)))
        items.add(
            UItem.asCustom(
                AiTemperatureCell(ctx, InuConfig.AI_TEMPERATURE.value) {
                    InuConfig.AI_TEMPERATURE.value = it
                }
            )
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAiTemperatureInfo)))

        // Section: Розпізнавання голосу (Voice Transcription)
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiTranscribeSection)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_TRANSCRIBE_ENABLED,
                R.string.InuAiTranscribe,
                R.string.InuAiTranscribeInfo,
                InuConfig.AI_TRANSCRIBE_ENABLED.value
            )
        )
        if (InuConfig.AI_TRANSCRIBE_ENABLED.value) {
            items.add(
                UItem.asButton(
                    BUTTON_TRANSCRIBE_SETTINGS,
                    R.drawable.msg_settings,
                    LocaleController.getString(R.string.InuAiTranscribeSettings)
                )
            )
        }
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_SERVICES -> presentFragment(AiServicesSettingsActivity())
            BUTTON_AI_EDITOR -> {
                if (!hasComposeCredentials()) {
                    presentFragment(AiServicesSettingsActivity())
                } else {
                    InuConfig.HIDE_AI_EDITOR.value = !InuConfig.HIDE_AI_EDITOR.value
                    listView.adapter.update(true)
                }
            }
            TOGGLE_AI_SUMMARY -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.AI_SUMMARY_ENABLED.toggle()
            }
            TOGGLE_TRANSCRIBE_ENABLED -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.AI_TRANSCRIBE_ENABLED.toggle()
                listView.adapter.update(true)
            }
            BUTTON_TRANSCRIBE_SETTINGS -> presentFragment(AiTranscriptionSettingsActivity())
        }
    }

    private fun activeServiceSummary(): String {
        val endpoints = AiComposeHelper.endpoints()
        if (endpoints.isEmpty()) return LocaleController.getString(R.string.InuAiServicesNone)
        val active = AiComposeHelper.activeEndpoint() ?: return endpoints.size.toString()
        return active.name.ifBlank { AiComposeHelper.host(active.url) }.ifBlank { LocaleController.getString(R.string.InuAiServicesNone) }
    }

    private fun hasComposeCredentials(): Boolean {
        val endpoint = AiComposeHelper.activeEndpoint()
        return endpoint?.url?.isNotBlank() == true && endpoint.apiKey.isNotBlank()
    }

    companion object {
        private val BUTTON_SERVICES = InuUtils.generateId()
        private val BUTTON_AI_EDITOR = InuUtils.generateId()
        private val TOGGLE_AI_SUMMARY = InuUtils.generateId()
        private val TOGGLE_TRANSCRIBE_ENABLED = InuUtils.generateId()
        private val BUTTON_TRANSCRIBE_SETTINGS = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "ai-compose",
            titleRes = R.string.InuAiChatTitle,
            iconRes = R.drawable.input_ai_star,
            factory = ::AiSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("ai-services", R.string.InuAiServices, BUTTON_SERVICES),
                SearchRegistry.Entry("ai-editor-button", R.string.InuHideAiEditor, BUTTON_AI_EDITOR),
                SearchRegistry.Entry("ai-summary", R.string.InuAiSummary, TOGGLE_AI_SUMMARY),
                SearchRegistry.Entry("ai-transcribe-enabled", R.string.InuAiTranscribe, TOGGLE_TRANSCRIBE_ENABLED),
            ),
        )
    }
}
