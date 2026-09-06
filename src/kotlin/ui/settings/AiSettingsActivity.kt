package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ai.AiComposeHelper
import desu.inugram.helpers.ai.AiRolesHelper
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
                BUTTON_PROVIDERS,
                R.drawable.inu_tabler_cpu,
                LocaleController.getString(R.string.InuAiProvidersTitle),
                activeProvidersSummary()
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_TRANSCRIBE_ENABLED,
                R.string.InuAiTranscribe,
                R.string.InuAiTranscribeInfo,
                InuConfig.AI_TRANSCRIBE_ENABLED.value
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
        // Section: Роль AI-персони
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiRoles)))
        items.add(
            UItem.asButton(BUTTON_AI_ROLE, LocaleController.getString(R.string.InuAiRoles), aiRoleSummary())
        )
        items.add(UItem.asShadow(null))

        // Section: Генерація відповіді
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiSectionGeneration)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_AI_STREAM,
                R.string.InuAiStream,
                R.string.InuAiStreamInfo,
                InuConfig.AI_STREAM_ENABLED.value
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_AI_ONLY_ANSWER,
                R.string.InuAiOnlyAnswer,
                R.string.InuAiOnlyAnswerInfo,
                InuConfig.AI_ONLY_ANSWER.value
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_AI_INSERT_QUOTE,
                R.string.InuAiInsertQuote,
                R.string.InuAiInsertQuoteInfo,
                InuConfig.AI_INSERT_QUOTE.value
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_AI_HISTORY,
                R.string.InuAiHistory,
                R.string.InuAiHistoryInfo,
                InuConfig.AI_HISTORY_ENABLED.value
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiSectionTemperature)))
        items.add(
            UItem.asCustom(
                AiTemperatureCell(ctx, InuConfig.AI_TEMPERATURE.value) {
                    InuConfig.AI_TEMPERATURE.value = it
                }
            )
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAiTemperatureInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_PROVIDERS -> presentFragment(AiProvidersSettingsActivity())
            BUTTON_AI_EDITOR -> {
                if (!hasComposeCredentials()) {
                    presentFragment(AiProvidersSettingsActivity())
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
            }
            BUTTON_AI_ROLE -> presentFragment(AiRolesSettingsActivity())
            TOGGLE_AI_STREAM -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.AI_STREAM_ENABLED.toggle()
            }
            TOGGLE_AI_ONLY_ANSWER -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.AI_ONLY_ANSWER.toggle()
            }
            TOGGLE_AI_INSERT_QUOTE -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.AI_INSERT_QUOTE.toggle()
            }
            TOGGLE_AI_HISTORY -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.AI_HISTORY_ENABLED.toggle()
            }
        }
    }

    private fun activeProvidersSummary(): String {
        val chatName = AiComposeHelper.providerDisplayName(InuConfig.AI_CHAT_ACTIVE_PROVIDER.value)
        val voiceName = AiComposeHelper.providerDisplayName(InuConfig.AI_TRANSCRIBE_PROVIDER.value)
        return if (chatName == voiceName) chatName else "$chatName • $voiceName"
    }

    private fun hasComposeCredentials(): Boolean {
        val endpoint = AiComposeHelper.activeEndpoint()
        return endpoint?.url?.isNotBlank() == true && endpoint.apiKey.isNotBlank()
    }

    private fun aiRoleSummary(): String =
        AiRolesHelper.activeRoleText().trim().ifEmpty { LocaleController.getString(R.string.InuAiRolesAssistant) }

    companion object {
        private val BUTTON_PROVIDERS = InuUtils.generateId()
        private val TOGGLE_TRANSCRIBE_ENABLED = InuUtils.generateId()
        private val BUTTON_AI_EDITOR = InuUtils.generateId()
        private val TOGGLE_AI_SUMMARY = InuUtils.generateId()
        private val BUTTON_AI_ROLE = InuUtils.generateId()
        private val TOGGLE_AI_STREAM = InuUtils.generateId()
        private val TOGGLE_AI_ONLY_ANSWER = InuUtils.generateId()
        private val TOGGLE_AI_INSERT_QUOTE = InuUtils.generateId()
        private val TOGGLE_AI_HISTORY = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "ai-compose",
            titleRes = R.string.InuAiChatTitle,
            iconRes = R.drawable.inu_tabler_sparkles,
            factory = ::AiSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("ai-providers", R.string.InuAiProvidersTitle, BUTTON_PROVIDERS),
                SearchRegistry.Entry("ai-transcribe-enabled", R.string.InuAiTranscribe, TOGGLE_TRANSCRIBE_ENABLED),
                SearchRegistry.Entry("ai-editor-button", R.string.InuHideAiEditor, BUTTON_AI_EDITOR),
                SearchRegistry.Entry("ai-summary", R.string.InuAiSummary, TOGGLE_AI_SUMMARY),
                SearchRegistry.Entry("ai-role", R.string.InuAiRoles, BUTTON_AI_ROLE),
                SearchRegistry.Entry("ai-stream", R.string.InuAiStream, TOGGLE_AI_STREAM),
                SearchRegistry.Entry("ai-only-answer", R.string.InuAiOnlyAnswer, TOGGLE_AI_ONLY_ANSWER),
                SearchRegistry.Entry("ai-insert-quote", R.string.InuAiInsertQuote, TOGGLE_AI_INSERT_QUOTE),
                SearchRegistry.Entry("ai-history", R.string.InuAiHistory, TOGGLE_AI_HISTORY),
            ),
        )
    }
}
