package desu.inugram.ui.settings

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ai.AiComposeHelper
import desu.inugram.helpers.ai.AiEndpoint
import desu.inugram.helpers.ai.AiSetupSheet
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.browser.Browser
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AiSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAiCompose)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        // --- AI Compose Section ---
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiCompose)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_AI_ENABLED,
                R.string.InuAiCompose,
                R.string.InuAiComposeInfo,
                InuConfig.AI_COMPOSE_ENABLED.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_AI_REASONING,
                R.string.InuAiReasoning,
                R.string.InuAiReasoningInfo,
                InuConfig.AI_REASONING_ENABLED.value,
                experimental = true,
            )
        )
        if (InuConfig.AI_REASONING_ENABLED.value) {
            items.add(
                UItem.asButton(
                    BUTTON_AI_REASONING_EFFORT,
                    LocaleController.getString(R.string.InuAiReasoningEffort),
                    InuConfig.AI_REASONING_EFFORT.value.replaceFirstChar { it.uppercase() }
                )
            )
        }
        items.add(UItem.asShadow(null))

        // --- AI Compose Endpoints ---
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiEndpoints)))
        val endpoints = AiComposeHelper.endpoints()
        val activeId = InuConfig.AI_COMPOSE_ACTIVE_ENDPOINT.value
        for ((i, ep) in endpoints.withIndex()) {
            val active = ep.id == activeId || (activeId.isEmpty() && i == 0)
            val host = AiComposeHelper.host(ep.url)
            val name = ep.name.ifBlank { host.ifBlank { ep.url } }
            val subtitle = buildString {
                append(host.ifBlank { ep.url })
                if (ep.model.isNotBlank()) {
                    append(" · ")
                    append(ep.model)
                }
                if (active) {
                    append(" · ")
                    append(LocaleController.getString(R.string.InuAiEndpointActive))
                }
            }
            items.add(
                UItem.asButtonCheck(AI_ITEM_BASE + i, name, subtitle).also { it.checked = active }
            )
        }
        items.add(
            UItem.asButton(
                BUTTON_AI_SETUP,
                R.drawable.msg_add,
                LocaleController.getString(R.string.InuAiEndpointAdd)
            )
        )
        items.add(UItem.asShadow(null))

        // --- Voice-to-Text (Transcription) Section ---
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiTranscribeSection)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_TRANSCRIBE_ENABLED,
                R.string.InuAiTranscribe,
                R.string.InuAiTranscribeInfo,
                InuConfig.AI_TRANSCRIBE_ENABLED.value,
            )
        )
        if (InuConfig.AI_TRANSCRIBE_ENABLED.value) {
            val providerName = when (InuConfig.AI_TRANSCRIBE_PROVIDER.value) {
                InuConfig.TRANSCRIBE_PROVIDER_GROQ -> LocaleController.getString(R.string.InuAiTranscribeProviderGroq)
                InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> LocaleController.getString(R.string.InuAiTranscribeProviderGemini)
                InuConfig.TRANSCRIBE_PROVIDER_OPENAI -> LocaleController.getString(R.string.InuAiTranscribeProviderOpenAI)
                InuConfig.TRANSCRIBE_PROVIDER_CF -> LocaleController.getString(R.string.InuAiTranscribeProviderCF)
                InuConfig.TRANSCRIBE_PROVIDER_CUSTOM -> LocaleController.getString(R.string.InuAiTranscribeProviderCustom)
                else -> LocaleController.getString(R.string.InuAiTranscribeProviderGroq)
            }
            items.add(
                UItem.asButton(
                    BUTTON_TRANSCRIBE_PROVIDER,
                    LocaleController.getString(R.string.InuAiTranscribeProvider),
                    providerName
                )
            )
            items.add(
                UItem.asButton(
                    BUTTON_TRANSCRIBE_SETTINGS,
                    R.drawable.msg_settings,
                    LocaleController.getString(R.string.InuAiTranscribeSettings)
                )
            )
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_AI_ENABLED -> {
                val new = InuConfig.AI_COMPOSE_ENABLED.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_AI_REASONING -> {
                val new = InuConfig.AI_REASONING_ENABLED.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                listView.adapter.update(true)
            }

            BUTTON_AI_REASONING_EFFORT -> {
                val efforts = listOf("low", "medium", "high")
                RadioItemOptions.show(
                    this, view,
                    efforts.map { it.replaceFirstChar { c -> c.uppercase() } },
                    efforts.indexOf(InuConfig.AI_REASONING_EFFORT.value).coerceAtLeast(0)
                ) { which ->
                    InuConfig.AI_REASONING_EFFORT.value = efforts[which]
                }
            }

            BUTTON_AI_SETUP -> {
                val ctx = context ?: return
                AiSetupSheet(
                    context = ctx,
                    onManual = { showEndpointDialog(null) },
                    onSaved = { listView.adapter.update(true) },
                ).show()
            }

            TOGGLE_TRANSCRIBE_ENABLED -> {
                val new = InuConfig.AI_TRANSCRIBE_ENABLED.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                listView.adapter.update(true)
            }

            BUTTON_TRANSCRIBE_PROVIDER -> {
                val providers = listOf(
                    LocaleController.getString(R.string.InuAiTranscribeProviderGroq),
                    LocaleController.getString(R.string.InuAiTranscribeProviderGemini),
                    LocaleController.getString(R.string.InuAiTranscribeProviderOpenAI),
                    LocaleController.getString(R.string.InuAiTranscribeProviderCF),
                    LocaleController.getString(R.string.InuAiTranscribeProviderCustom),
                )
                RadioItemOptions.show(
                    this, view,
                    providers,
                    InuConfig.AI_TRANSCRIBE_PROVIDER.value.coerceIn(0, providers.size - 1)
                ) { which ->
                    InuConfig.AI_TRANSCRIBE_PROVIDER.value = which
                    listView.adapter.update(true)
                }
            }

            BUTTON_TRANSCRIBE_SETTINGS -> {
                showTranscribeProviderSettingsDialog()
            }

            else -> {
                val idx = item.id - AI_ITEM_BASE
                val endpoints = AiComposeHelper.endpoints()
                if (idx in endpoints.indices) {
                    showEndpointDialog(endpoints[idx])
                }
            }
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        val idx = item.id - AI_ITEM_BASE
        val endpoints = AiComposeHelper.endpoints()
        if (idx !in endpoints.indices) return super.onLongClick(item, view, position, x, y)
        val ep = endpoints[idx]
        val options = ItemOptions.makeOptions(this, view)
            .setScrimViewBackground(listView.getClipBackground(view))
        if (AiComposeHelper.activeEndpoint()?.id != ep.id) {
            options.add(R.drawable.msg_select, LocaleController.getString(R.string.InuAiEndpointSetActive)) {
                AiComposeHelper.setActiveEndpoint(ep.id)
                listView.adapter.update(true)
            }
        }
        options.add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete)) {
            AiComposeHelper.deleteEndpoint(ep.id)
            listView.adapter.update(true)
        }.show()
        return true
    }

    private fun showTranscribeProviderSettingsDialog() {
        val ctx = context ?: return
        val provider = InuConfig.AI_TRANSCRIBE_PROVIDER.value

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        var keyInput: EditText? = null
        var cfAccountIdInput: EditText? = null
        var cfTokenInput: EditText? = null
        var customUrlInput: EditText? = null
        var customKeyInput: EditText? = null
        var customModelInput: EditText? = null

        when (provider) {
            InuConfig.TRANSCRIBE_PROVIDER_GROQ -> {
                keyInput = fieldInput(
                    LocaleController.getString(R.string.InuAiTranscribeApiKey),
                    InuConfig.AI_TRANSCRIBE_GROQ_KEY.value,
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                )
                container.addView(keyInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
            InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> {
                keyInput = fieldInput(
                    LocaleController.getString(R.string.InuAiTranscribeApiKey),
                    InuConfig.AI_TRANSCRIBE_GEMINI_KEY.value,
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                )
                container.addView(keyInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
            InuConfig.TRANSCRIBE_PROVIDER_OPENAI -> {
                keyInput = fieldInput(
                    LocaleController.getString(R.string.InuAiTranscribeApiKey),
                    InuConfig.AI_TRANSCRIBE_OPENAI_KEY.value,
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                )
                container.addView(keyInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
            InuConfig.TRANSCRIBE_PROVIDER_CF -> {
                cfAccountIdInput = fieldInput(
                    LocaleController.getString(R.string.InuAiTranscribeAccountId),
                    InuConfig.AI_TRANSCRIBE_CF_ACCOUNT_ID.value,
                    InputType.TYPE_CLASS_TEXT
                )
                container.addView(cfAccountIdInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

                cfTokenInput = fieldInput(
                    LocaleController.getString(R.string.InuAiTranscribeApiToken),
                    InuConfig.AI_TRANSCRIBE_CF_API_TOKEN.value,
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                )
                container.addView(cfTokenInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
            InuConfig.TRANSCRIBE_PROVIDER_CUSTOM -> {
                customUrlInput = fieldInput(
                    "https://api.openai.com/v1",
                    InuConfig.AI_TRANSCRIBE_CUSTOM_URL.value,
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                )
                container.addView(customUrlInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

                customKeyInput = fieldInput(
                    LocaleController.getString(R.string.InuAiTranscribeApiKey),
                    InuConfig.AI_TRANSCRIBE_CUSTOM_KEY.value,
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                )
                container.addView(customKeyInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

                customModelInput = fieldInput(
                    LocaleController.getString(R.string.InuAiTranscribeModel),
                    InuConfig.AI_TRANSCRIBE_CUSTOM_MODEL.value,
                    InputType.TYPE_CLASS_TEXT
                )
                container.addView(customModelInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }

        val promptInput = fieldInput(
            LocaleController.getString(R.string.InuAiTranscribePrompt),
            InuConfig.AI_TRANSCRIBE_PROMPT.value,
            InputType.TYPE_CLASS_TEXT
        )
        container.addView(promptInput, LinearLayout.LayoutParams(-1, -2))

        val builder = AlertDialog.Builder(ctx)
            .setTitle(LocaleController.getString(R.string.InuAiTranscribeSettings))
            .setView(container)
            .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                when (provider) {
                    InuConfig.TRANSCRIBE_PROVIDER_GROQ -> InuConfig.AI_TRANSCRIBE_GROQ_KEY.value = keyInput?.text.toString().trim()
                    InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> InuConfig.AI_TRANSCRIBE_GEMINI_KEY.value = keyInput?.text.toString().trim()
                    InuConfig.TRANSCRIBE_PROVIDER_OPENAI -> InuConfig.AI_TRANSCRIBE_OPENAI_KEY.value = keyInput?.text.toString().trim()
                    InuConfig.TRANSCRIBE_PROVIDER_CF -> {
                        InuConfig.AI_TRANSCRIBE_CF_ACCOUNT_ID.value = cfAccountIdInput?.text.toString().trim()
                        InuConfig.AI_TRANSCRIBE_CF_API_TOKEN.value = cfTokenInput?.text.toString().trim()
                    }
                    InuConfig.TRANSCRIBE_PROVIDER_CUSTOM -> {
                        InuConfig.AI_TRANSCRIBE_CUSTOM_URL.value = customUrlInput?.text.toString().trim()
                        InuConfig.AI_TRANSCRIBE_CUSTOM_KEY.value = customKeyInput?.text.toString().trim()
                        InuConfig.AI_TRANSCRIBE_CUSTOM_MODEL.value = customModelInput?.text.toString().trim()
                    }
                }
                InuConfig.AI_TRANSCRIBE_PROMPT.value = promptInput.text.toString().trim()
                listView.adapter.update(true)
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)

        val helpUrl = when (provider) {
            InuConfig.TRANSCRIBE_PROVIDER_GROQ -> "https://console.groq.com/keys"
            InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> "https://aistudio.google.com/app/apikey"
            InuConfig.TRANSCRIBE_PROVIDER_OPENAI -> "https://platform.openai.com/api-keys"
            InuConfig.TRANSCRIBE_PROVIDER_CF -> "https://dash.cloudflare.com"
            else -> null
        }
        if (helpUrl != null) {
            builder.setNeutralButton(LocaleController.getString(R.string.InuAiTranscribeGetApiKey)) { _, _ ->
                Browser.openUrl(ctx, helpUrl)
            }
        }

        showDialog(builder.create())
    }

    private fun showEndpointDialog(existing: AiEndpoint?) {
        val ctx = context ?: return

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        val nameInput = fieldInput(
            LocaleController.getString(R.string.InuAiEndpointName),
            existing?.name ?: "",
            InputType.TYPE_CLASS_TEXT
        )
        container.addView(nameInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val urlInput = fieldInput(
            LocaleController.getString(R.string.InuAiEndpointUrl),
            existing?.url ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        container.addView(urlInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val keyInput = fieldInput(
            LocaleController.getString(R.string.InuAiEndpointApiKey),
            existing?.apiKey ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )
        container.addView(keyInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val modelInput = fieldInput(
            LocaleController.getString(R.string.InuAiEndpointModel),
            existing?.model ?: "",
            InputType.TYPE_CLASS_TEXT
        )
        container.addView(modelInput, LinearLayout.LayoutParams(-1, -2))

        val title = if (existing != null)
            LocaleController.getString(R.string.InuAiEndpointEdit)
        else
            LocaleController.getString(R.string.InuAiEndpointAdd)

        val builder = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                val url = urlInput.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                AiComposeHelper.upsertEndpoint(
                    AiEndpoint(
                        id = existing?.id ?: AiComposeHelper.newEndpointId(),
                        name = nameInput.text.toString().trim(),
                        url = url,
                        apiKey = keyInput.text.toString().trim(),
                        model = modelInput.text.toString().trim(),
                    )
                )
                listView.adapter.update(true)
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)

        if (existing != null) {
            builder.setNeutralButton(LocaleController.getString(R.string.Delete)) { _, _ ->
                AiComposeHelper.deleteEndpoint(existing.id)
                listView.adapter.update(true)
            }
        }

        showDialog(builder.create())
    }

    private fun fieldInput(hint: String, text: String, inputType: Int): EditText =
        org.telegram.ui.Components.EditTextBoldCursor(context!!).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
            setCursorColor(Theme.getColor(Theme.key_dialogTextBlack))
            setCursorSize(AndroidUtilities.dp(20f))
            setCursorWidth(1.5f)
            setHint(hint)
            setText(text)
            this.inputType = inputType
            isSingleLine = true
            setSelection(text.length)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = Theme.createRoundRectDrawable(
                dp(12),
                Theme.getColor(Theme.key_windowBackgroundGray)
            )
        }

    private fun dp(value: Int) = AndroidUtilities.dp(value.toFloat())

    companion object {
        private val TOGGLE_AI_ENABLED = InuUtils.generateId()
        private val TOGGLE_AI_REASONING = InuUtils.generateId()
        private val BUTTON_AI_REASONING_EFFORT = InuUtils.generateId()
        private val BUTTON_AI_SETUP = InuUtils.generateId()
        private val TOGGLE_TRANSCRIBE_ENABLED = InuUtils.generateId()
        private val BUTTON_TRANSCRIBE_PROVIDER = InuUtils.generateId()
        private val BUTTON_TRANSCRIBE_SETTINGS = InuUtils.generateId()
        private const val AI_ITEM_BASE = 20000

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "ai-compose",
            titleRes = R.string.InuAiCompose,
            iconRes = R.drawable.input_ai_star,
            factory = ::AiSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("ai-compose-enabled", R.string.InuAiCompose, TOGGLE_AI_ENABLED),
                SearchRegistry.Entry("ai-reasoning", R.string.InuAiReasoning, TOGGLE_AI_REASONING),
                SearchRegistry.Entry("ai-endpoints", R.string.InuAiEndpoints, BUTTON_AI_SETUP),
                SearchRegistry.Entry("ai-transcribe-enabled", R.string.InuAiTranscribe, TOGGLE_TRANSCRIBE_ENABLED),
                SearchRegistry.Entry("ai-transcribe-provider", R.string.InuAiTranscribeProvider, BUTTON_TRANSCRIBE_PROVIDER),
            ),
        )
    }
}
