package desu.inugram.ui.settings

import android.text.InputType
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ai.AiComposeHelper
import desu.inugram.helpers.ai.AiEndpoint
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

/** Inline editor for one AI Compose service / endpoint matching the reference design. */
class AiServiceSettingsActivity(private val endpointId: String? = null) : SettingsPageActivity() {

    private var draft: AiEndpoint? = null

    override fun getTitle(): CharSequence = LocaleController.getString(
        if (endpointId == null) R.string.InuAiServiceNew else R.string.InuAiServiceEdit
    )

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val ctx = context ?: return
        val current = currentDraft()
        val provider = providerFor(current.url)

        // Section: Провайдер
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiProvider)))
        PROVIDERS.forEachIndexed { index, entry ->
            items.add(
                UItem.asRadio(PROVIDER_BASE + index, entry.first).also {
                    it.checked = provider == entry.second
                }
            )
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAiProviderDesc)))

        // Section: Інформація про сервіс
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiServiceInfo)))
        items.add(
            UItem.asCustom(
                BUTTON_URL,
                AiServiceFieldCell(
                    ctx,
                    LocaleController.getString(R.string.InuAiEndpointUrl),
                    current.url,
                    InputType.TYPE_TEXT_VARIATION_URI,
                    showCounter = true
                ) {
                    draft = currentDraft().copy(url = it)
                }
            )
        )
        items.add(
            UItem.asCustom(
                BUTTON_KEY,
                AiServiceFieldCell(
                    ctx,
                    LocaleController.getString(R.string.InuAiApiKeyHint),
                    current.apiKey,
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                ) {
                    draft = currentDraft().copy(apiKey = it)
                }
            )
        )
        items.add(
            UItem.asCustom(
                BUTTON_MODEL,
                AiServiceFieldCell(
                    ctx,
                    LocaleController.getString(R.string.InuAiEndpointModel),
                    current.model,
                    showCounter = true
                ) {
                    draft = currentDraft().copy(model = it)
                }
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_REASONING,
                R.string.InuAiReasoning,
                R.string.InuAiReasoningDesc,
                InuConfig.AI_REASONING_ENABLED.value
            )
        )
        items.add(UItem.asShadow(null))

        // Save & Delete
        items.add(UItem.asButton(BUTTON_SAVE, R.drawable.input_done, LocaleController.getString(R.string.Save)))
        if (endpointId != null) {
            items.add(UItem.asButton(BUTTON_DELETE, R.drawable.msg_delete, LocaleController.getString(R.string.Delete)).red())
        }
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            in PROVIDER_BASE..(PROVIDER_BASE + PROVIDERS.lastIndex) -> {
                val selected = PROVIDERS[item.id - PROVIDER_BASE].second
                val current = currentDraft()
                val (url, defaultModel) = when (selected) {
                    PROVIDER_GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai/" to "gemini-3.1-flash-lit"
                    PROVIDER_OPENAI -> "https://api.openai.com/v1/" to "gpt-4o-mini"
                    PROVIDER_OPENROUTER -> "https://openrouter.ai/api/v1/" to "openai/gpt-4o-mini"
                    PROVIDER_GROQ -> "https://api.groq.com/openai/v1/" to "llama-3.3-70b-versatile"
                    else -> "" to ""
                }
                draft = current.copy(
                    name = if (selected != PROVIDER_CUSTOM) PROVIDERS[item.id - PROVIDER_BASE].first else current.name,
                    url = url,
                    model = if (selected == PROVIDER_CUSTOM) "" else defaultModel
                )
                listView.adapter.update(true)
            }
            TOGGLE_REASONING -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.AI_REASONING_ENABLED.toggle()
            }
            BUTTON_SAVE -> save()
            BUTTON_DELETE -> {
                endpointId?.let { AiComposeHelper.deleteEndpoint(it) }
                finishFragment()
            }
        }
    }

    private fun currentDraft(): AiEndpoint {
        draft?.let { return it }
        val existing = endpointId?.let { id -> AiComposeHelper.endpoints().firstOrNull { it.id == id } }
        return (existing ?: AiEndpoint(
            AiComposeHelper.newEndpointId(),
            "Gemini",
            "https://generativelanguage.googleapis.com/v1beta/openai/",
            "",
            "gemini-3.1-flash-lite"
        )).also { draft = it }
    }

    private fun save() {
        val value = currentDraft()
        if (value.url.isBlank()) return
        val finalEndpoint = if (value.name.isBlank()) {
            value.copy(name = providerNameFor(value.url))
        } else value
        if (value.url.isBlank()) return
        AiComposeHelper.upsertEndpoint(finalEndpoint)
        AiComposeHelper.setActiveEndpoint(finalEndpoint.id)
        finishFragment()
    }

    private fun providerNameFor(url: String): String = when {
        url.contains("generativelanguage.googleapis.com", ignoreCase = true) -> "Gemini"
        url.contains("api.openai.com", ignoreCase = true) -> "OpenAI"
        url.contains("openrouter.ai", ignoreCase = true) -> "OpenRouter"
        else -> AiComposeHelper.host(url).ifBlank { "Custom" }
    }

    private fun providerFor(url: String): String = when {
        url.contains("generativelanguage.googleapis.com", ignoreCase = true) -> PROVIDER_GEMINI
        url.contains("api.openai.com", ignoreCase = true) -> PROVIDER_OPENAI
        url.contains("openrouter.ai", ignoreCase = true) -> PROVIDER_OPENROUTER
        url.contains("api.groq.com", ignoreCase = true) -> PROVIDER_GROQ
        else -> PROVIDER_CUSTOM
    }

    companion object {
        private val BUTTON_URL = InuUtils.generateId()
        private val BUTTON_KEY = InuUtils.generateId()
        private val BUTTON_MODEL = InuUtils.generateId()
        private val TOGGLE_REASONING = InuUtils.generateId()
        private val BUTTON_SAVE = InuUtils.generateId()
        private val BUTTON_DELETE = InuUtils.generateId()
        private const val PROVIDER_BASE = 24000
        private const val PROVIDER_GEMINI = "gemini"
        private const val PROVIDER_OPENAI = "openai"
        private const val PROVIDER_OPENROUTER = "openrouter"
        private const val PROVIDER_GROQ = "groq"
        private const val PROVIDER_CUSTOM = "custom"
        private val PROVIDERS = listOf(
            "Gemini" to PROVIDER_GEMINI,
            "OpenAI" to PROVIDER_OPENAI,
            "OpenRouter" to PROVIDER_OPENROUTER,
            "Groq" to PROVIDER_GROQ,
            LocaleController.getString(R.string.InuAiProviderCustom) to PROVIDER_CUSTOM,
        )
    }
}
