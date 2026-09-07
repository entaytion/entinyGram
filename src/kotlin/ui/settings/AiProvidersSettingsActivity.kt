package desu.inugram.ui.settings

import android.text.InputType
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ai.AiComposeHelper
import desu.inugram.helpers.ai.AiModelsHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.browser.Browser
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ColoredImageSpan
import org.telegram.ui.Components.URLSpanNoUnderline
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

/**
 * Every AI provider (chat compose + voice transcription) in one flat list -- no Universal/Chat/
 * Voice sections, just a small leading glyph per row marking what a provider supports (infinity =
 * both, keyboard = chat only, mic = voice only). Tapping a provider expands it in place. Named
 * providers (Gemini/OpenAI/Groq) take one shared API key for both scopes and one shared model
 * field/fetch button -- writing the model updates whichever scope(s) are switched on -- with the
 * "Use for Chat" / "Use for Voice" switches at the bottom of the block.
 */
class AiProvidersSettingsActivity : SettingsPageActivity() {

    private var expandedProvider: Int = InuConfig.AI_CHAT_ACTIVE_PROVIDER.value

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAiProvidersTitle)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiProvidersTitle)))
        ALL_PROVIDERS.forEach { addProviderRow(items, it) }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAiProvidersUniversalDesc)))

        // Applies to whichever voice provider is active, so it lives outside the per-provider blocks.
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiTranscribe)))
        keyField(items, R.string.InuAiTranscribeLanguage, InuConfig.AI_TRANSCRIBE_LANGUAGE.value, InputType.TYPE_CLASS_TEXT) {
            InuConfig.AI_TRANSCRIBE_LANGUAGE.value = it.trim()
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAiTranscribeLanguageInfo)))
    }

    private fun addProviderRow(items: ArrayList<UItem>, meta: ProviderMeta) {
        items.add(UItem.asButton(PROVIDER_BASE + meta.id, badgeLabel(meta), statusFor(meta)))
        if (expandedProvider != meta.id) return

        val chatOn = meta.chat && InuConfig.AI_CHAT_ACTIVE_PROVIDER.value == meta.id
        val voiceOn = meta.voice && InuConfig.AI_TRANSCRIBE_PROVIDER.value == meta.id

        when (meta.id) {
            InuConfig.TRANSCRIBE_PROVIDER_CUSTOM -> addCustomBlock(items, meta, chatOn, voiceOn)
            InuConfig.TRANSCRIBE_PROVIDER_CF -> addSingleScopeVoiceBlock(items, meta, voiceOn)
            InuConfig.AI_PROVIDER_OPENROUTER -> addSingleScopeChatBlock(items, meta, chatOn)
            else -> addNamedProviderBlock(items, meta, chatOn, voiceOn)
        }
    }

    private fun addNamedProviderBlock(items: ArrayList<UItem>, meta: ProviderMeta, chatOn: Boolean, voiceOn: Boolean) {
        keyField(items, R.string.InuAiApiKeyHint, AiComposeHelper.chatProviderKey(meta.id)) { AiComposeHelper.setProviderKey(meta.id, it) }

        val sameModelItem = AiComposeHelper.sameModelForBothScopes(meta.id)
        val sameModel = chatOn && voiceOn && sameModelItem?.value == true

        when {
            chatOn && voiceOn && sameModel -> {
                keyField(items, R.string.InuAiEndpointModel, AiComposeHelper.chatProviderModel(meta.id), InputType.TYPE_CLASS_TEXT, showCounter = true) { value ->
                    setChatModel(meta.id, value)
                    setVoiceModel(meta.id, value)
                }
                items.add(UItem.asButton(BUTTON_FETCH_MODELS, LocaleController.getString(R.string.InuAiTranscribeFetchModels)))
            }
            chatOn && voiceOn -> {
                items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiProvidersChatModelHint)))
                keyField(items, R.string.InuAiEndpointModel, AiComposeHelper.chatProviderModel(meta.id), InputType.TYPE_CLASS_TEXT, showCounter = true) { setChatModel(meta.id, it) }
                items.add(UItem.asButton(BUTTON_FETCH_MODELS, LocaleController.getString(R.string.InuAiTranscribeFetchModels)))
                items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiProvidersVoiceModelHint)))
                keyField(items, R.string.InuAiTranscribeModel, voiceModel(meta.id), InputType.TYPE_CLASS_TEXT) { setVoiceModel(meta.id, it) }
                items.add(UItem.asButton(BUTTON_FETCH_VOICE_MODELS, LocaleController.getString(R.string.InuAiTranscribeFetchModels)))
            }
            chatOn -> {
                keyField(items, R.string.InuAiEndpointModel, AiComposeHelper.chatProviderModel(meta.id), InputType.TYPE_CLASS_TEXT, showCounter = true) { setChatModel(meta.id, it) }
                items.add(UItem.asButton(BUTTON_FETCH_MODELS, LocaleController.getString(R.string.InuAiTranscribeFetchModels)))
            }
            voiceOn -> {
                keyField(items, R.string.InuAiTranscribeModel, voiceModel(meta.id), InputType.TYPE_CLASS_TEXT) { setVoiceModel(meta.id, it) }
                items.add(UItem.asButton(BUTTON_FETCH_MODELS, LocaleController.getString(R.string.InuAiTranscribeFetchModels)))
            }
        }

        if (chatOn && voiceOn && sameModelItem != null) {
            items.add(
                UItem.asCheck(SAME_MODEL_BASE + meta.id, LocaleController.getString(R.string.InuAiProvidersSameModel)).also { it.checked = sameModelItem.value }
            )
        }

        items.add(
            UItem.asCheck(ACTIVE_CHAT_BASE + meta.id, LocaleController.getString(R.string.InuAiProvidersActiveForChat)).also { it.checked = chatOn }
        )
        items.add(
            UItem.asCheck(ACTIVE_VOICE_BASE + meta.id, LocaleController.getString(R.string.InuAiProvidersActiveForVoice)).also { it.checked = voiceOn }
        )
        items.add(UItem.asShadow(footerFor(meta.id)))
    }

    private fun addSingleScopeChatBlock(items: ArrayList<UItem>, meta: ProviderMeta, chatOn: Boolean) {
        keyField(items, R.string.InuAiApiKeyHint, InuConfig.AI_CHAT_OPENROUTER_KEY.value) { InuConfig.AI_CHAT_OPENROUTER_KEY.value = it }
        keyField(items, R.string.InuAiEndpointModel, InuConfig.AI_CHAT_OPENROUTER_MODEL.value, InputType.TYPE_CLASS_TEXT, showCounter = true) { InuConfig.AI_CHAT_OPENROUTER_MODEL.value = it }
        items.add(UItem.asButton(BUTTON_FETCH_MODELS, LocaleController.getString(R.string.InuAiTranscribeFetchModels)))
        items.add(
            UItem.asCheck(ACTIVE_CHAT_BASE + meta.id, LocaleController.getString(R.string.InuAiProvidersActiveForChat)).also { it.checked = chatOn }
        )
        items.add(UItem.asShadow(footerFor(meta.id)))
    }

    private fun addSingleScopeVoiceBlock(items: ArrayList<UItem>, meta: ProviderMeta, voiceOn: Boolean) {
        keyField(items, R.string.InuAiTranscribeAccountId, InuConfig.AI_TRANSCRIBE_CF_ACCOUNT_ID.value, InputType.TYPE_CLASS_TEXT) { InuConfig.AI_TRANSCRIBE_CF_ACCOUNT_ID.value = it }
        keyField(items, R.string.InuAiTranscribeApiToken, InuConfig.AI_TRANSCRIBE_CF_API_TOKEN.value) { InuConfig.AI_TRANSCRIBE_CF_API_TOKEN.value = it }
        keyField(items, R.string.InuAiTranscribeModel, InuConfig.AI_TRANSCRIBE_CF_MODEL.value.ifBlank { "@cf/openai/whisper" }, InputType.TYPE_CLASS_TEXT) { InuConfig.AI_TRANSCRIBE_CF_MODEL.value = it }
        items.add(UItem.asButton(BUTTON_FETCH_MODELS, LocaleController.getString(R.string.InuAiTranscribeFetchModels)))
        items.add(
            UItem.asCheck(ACTIVE_VOICE_BASE + meta.id, LocaleController.getString(R.string.InuAiProvidersActiveForVoice)).also { it.checked = voiceOn }
        )
        items.add(UItem.asShadow(footerFor(meta.id)))
    }

    private fun addCustomBlock(items: ArrayList<UItem>, meta: ProviderMeta, chatOn: Boolean, voiceOn: Boolean) {
        // Custom endpoints are genuinely independent per scope -- an arbitrary URL isn't "the same
        // account" the way a named provider's key is -- so nothing here is shared between them.
        keyField(items, R.string.InuAiProviderCustomNameHint, InuConfig.AI_CHAT_CUSTOM_NAME.value, InputType.TYPE_CLASS_TEXT) { InuConfig.AI_CHAT_CUSTOM_NAME.value = it }
        keyField(items, R.string.InuAiEndpointUrl, InuConfig.AI_CHAT_CUSTOM_URL.value, InputType.TYPE_TEXT_VARIATION_URI) { InuConfig.AI_CHAT_CUSTOM_URL.value = it }
        keyField(items, R.string.InuAiApiKeyHint, InuConfig.AI_CHAT_CUSTOM_KEY.value) { InuConfig.AI_CHAT_CUSTOM_KEY.value = it }
        keyField(items, R.string.InuAiEndpointModel, InuConfig.AI_CHAT_CUSTOM_MODEL.value, InputType.TYPE_CLASS_TEXT, showCounter = true) { InuConfig.AI_CHAT_CUSTOM_MODEL.value = it }
        items.add(UItem.asButton(BUTTON_FETCH_MODELS, LocaleController.getString(R.string.InuAiTranscribeFetchModels)))
        items.add(
            UItem.asCheck(ACTIVE_CHAT_BASE + meta.id, LocaleController.getString(R.string.InuAiProvidersActiveForChat)).also { it.checked = chatOn }
        )
        items.add(UItem.asShadow(null))

        keyField(items, R.string.InuAiProviderCustomNameHint, InuConfig.AI_TRANSCRIBE_CUSTOM_NAME.value, InputType.TYPE_CLASS_TEXT) { InuConfig.AI_TRANSCRIBE_CUSTOM_NAME.value = it }
        keyField(items, R.string.InuAiEndpointUrl, InuConfig.AI_TRANSCRIBE_CUSTOM_URL.value, InputType.TYPE_TEXT_VARIATION_URI) { InuConfig.AI_TRANSCRIBE_CUSTOM_URL.value = it }
        keyField(items, R.string.InuAiTranscribeApiKey, InuConfig.AI_TRANSCRIBE_CUSTOM_KEY.value) { InuConfig.AI_TRANSCRIBE_CUSTOM_KEY.value = it }
        keyField(items, R.string.InuAiTranscribeModel, InuConfig.AI_TRANSCRIBE_CUSTOM_MODEL.value, InputType.TYPE_CLASS_TEXT) { InuConfig.AI_TRANSCRIBE_CUSTOM_MODEL.value = it }
        items.add(UItem.asButton(BUTTON_FETCH_VOICE_MODELS, LocaleController.getString(R.string.InuAiTranscribeFetchModels)))
        items.add(
            UItem.asCheck(ACTIVE_VOICE_BASE + meta.id, LocaleController.getString(R.string.InuAiProvidersActiveForVoice)).also { it.checked = voiceOn }
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAiTranscribeCustomInfo)))
    }

    private fun keyField(items: ArrayList<UItem>, titleRes: Int, value: String, type: Int = InputType.TYPE_TEXT_VARIATION_PASSWORD, showCounter: Boolean = false, onChanged: (String) -> Unit) {
        items.add(UItem.asCustom(InuUtils.generateId(), AiServiceFieldCell(context!!, LocaleController.getString(titleRes), value, type, showCounter, onChanged)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val allIds = ALL_PROVIDERS.map { it.id }
        if (item.id - PROVIDER_BASE in allIds) {
            val id = item.id - PROVIDER_BASE
            expandedProvider = if (expandedProvider == id) -1 else id
            listView.adapter.update(true)
            return
        }
        if (item.id - ACTIVE_CHAT_BASE in allIds) {
            InuConfig.AI_CHAT_ACTIVE_PROVIDER.value = item.id - ACTIVE_CHAT_BASE
            listView.adapter.update(true)
            return
        }
        if (item.id - ACTIVE_VOICE_BASE in allIds) {
            InuConfig.AI_TRANSCRIBE_PROVIDER.value = item.id - ACTIVE_VOICE_BASE
            listView.adapter.update(true)
            return
        }
        if (item.id - SAME_MODEL_BASE in allIds) {
            val id = item.id - SAME_MODEL_BASE
            AiComposeHelper.sameModelForBothScopes(id)?.toggle()
            listView.adapter.update(true)
            return
        }
        when (item.id) {
            BUTTON_FETCH_MODELS -> fetchModels()
            BUTTON_FETCH_VOICE_MODELS -> if (expandedProvider == InuConfig.TRANSCRIBE_PROVIDER_CUSTOM) fetchCustomVoiceModels() else fetchNamedVoiceModels()
        }
    }

    // ---------------- named-provider scope helpers ----------------

    private fun setChatModel(id: Int, value: String) {
        when (id) {
            InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> InuConfig.AI_CHAT_GEMINI_MODEL.value = value
            InuConfig.TRANSCRIBE_PROVIDER_OPENAI -> InuConfig.AI_CHAT_OPENAI_MODEL.value = value
            InuConfig.TRANSCRIBE_PROVIDER_GROQ -> InuConfig.AI_CHAT_GROQ_MODEL.value = value
        }
    }

    private fun voiceModel(id: Int): String = when (id) {
        InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> InuConfig.AI_TRANSCRIBE_GEMINI_MODEL.value.ifBlank { "gemini-3.5-flash" }
        InuConfig.TRANSCRIBE_PROVIDER_OPENAI -> InuConfig.AI_TRANSCRIBE_OPENAI_MODEL.value.ifBlank { "whisper-1" }
        InuConfig.TRANSCRIBE_PROVIDER_GROQ -> InuConfig.AI_TRANSCRIBE_GROQ_MODEL.value.ifBlank { "whisper-large-v3-turbo" }
        else -> ""
    }

    private fun setVoiceModel(id: Int, value: String) {
        when (id) {
            InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> InuConfig.AI_TRANSCRIBE_GEMINI_MODEL.value = value
            InuConfig.TRANSCRIBE_PROVIDER_OPENAI -> InuConfig.AI_TRANSCRIBE_OPENAI_MODEL.value = value
            InuConfig.TRANSCRIBE_PROVIDER_GROQ -> InuConfig.AI_TRANSCRIBE_GROQ_MODEL.value = value
        }
    }

    /** The primary Fetch button for a named provider: chat's broader listing when chat is
     * visible (writes to both scopes if they're merged into one model field), otherwise the
     * voice-side listing when only voice is on. */
    private fun fetchModels() {
        val id = expandedProvider
        val chatOn = InuConfig.AI_CHAT_ACTIVE_PROVIDER.value == id
        val voiceOn = InuConfig.AI_TRANSCRIBE_PROVIDER.value == id
        val sameModel = chatOn && voiceOn && AiComposeHelper.sameModelForBothScopes(id)?.value == true
        val onPick: (String) -> Unit = { model ->
            setChatModel(id, model)
            if (sameModel || !chatOn) setVoiceModel(id, model)
        }
        if (chatOn) {
            val key = AiComposeHelper.chatProviderKey(id).trim()
            if (key.isBlank()) { showFetchError(LocaleController.getString(R.string.InuAiApiKeyHint)); return }
            val baseUrl = AiComposeHelper.chatProviderBaseUrl(id).trim()
            AiModelsHelper.fetchOpenAiCompatModels(baseUrl, key, callback = { onModelsFetched(it) { model -> onPick(model) } })
        } else {
            fetchVoiceListing(id) { model -> setVoiceModel(id, model) }
        }
    }

    /** Only reached when both scopes are on with separate models -- fetches the voice-side listing. */
    private fun fetchNamedVoiceModels() {
        val id = expandedProvider
        fetchVoiceListing(id) { model -> setVoiceModel(id, model) }
    }

    private fun fetchVoiceListing(id: Int, onPick: (String) -> Unit) {
        val key = AiComposeHelper.chatProviderKey(id).trim()
        if (key.isBlank()) { showFetchError(LocaleController.getString(R.string.InuAiApiKeyHint)); return }
        when (id) {
            InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> AiModelsHelper.fetchGeminiModels(key) { onModelsFetched(it, onPick) }
            InuConfig.TRANSCRIBE_PROVIDER_GROQ -> AiModelsHelper.fetchOpenAiCompatModels("https://api.groq.com/openai/v1", key, "whisper") { onModelsFetched(it, onPick) }
            InuConfig.TRANSCRIBE_PROVIDER_OPENAI -> AiModelsHelper.fetchOpenAiCompatModels("https://api.openai.com/v1", key, "whisper") { onModelsFetched(it, onPick) }
        }
    }

    private fun fetchCustomVoiceModels() {
        val url = InuConfig.AI_TRANSCRIBE_CUSTOM_URL.value.trim()
        if (url.isEmpty()) { showFetchError(LocaleController.getString(R.string.InuAiEndpointUrl)); return }
        val base = url.removeSuffix("/audio/transcriptions").trimEnd('/')
        val key = InuConfig.AI_TRANSCRIBE_CUSTOM_KEY.value.trim()
        AiModelsHelper.fetchOpenAiCompatModels(base, key, callback = { onModelsFetched(it) { model -> InuConfig.AI_TRANSCRIBE_CUSTOM_MODEL.value = model } })
    }

    // ---------------- shared UI plumbing ----------------

    private fun onModelsFetched(result: Result<List<String>>, onPick: (String) -> Unit) {
        result.onSuccess { models ->
            if (models.isEmpty()) { showFetchError(null); return@onSuccess }
            val ctx = context ?: return@onSuccess
            showDialog(
                RadioDialogBuilder(ctx, resourceProvider)
                    .setTitle(LocaleController.getString(R.string.InuAiTranscribeFetchModels))
                    .setItems(models.map { RadioDialogBuilder.Item(it) }, -1) { _, index ->
                        onPick(models[index])
                        listView.adapter.update(true)
                    }
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .create()
            )
        }.onFailure { e -> showFetchError(e.message) }
    }

    private fun showFetchError(detail: String?) {
        val message = if (detail.isNullOrBlank()) {
            LocaleController.getString(R.string.InuAiTranscribeFetchModelsEmpty)
        } else {
            LocaleController.formatString(R.string.InuAiTranscribeFetchModelsFailed, detail)
        }
        BulletinFactory.of(this).createErrorBulletin(message).show()
    }

    private fun statusFor(meta: ProviderMeta): CharSequence {
        val chatActive = meta.chat && InuConfig.AI_CHAT_ACTIVE_PROVIDER.value == meta.id
        val voiceActive = meta.voice && InuConfig.AI_TRANSCRIBE_PROVIDER.value == meta.id
        return when {
            chatActive && voiceActive -> LocaleController.getString(R.string.InuAiProvidersActiveBoth)
            chatActive -> LocaleController.getString(R.string.InuAiProvidersActiveForChat)
            voiceActive -> LocaleController.getString(R.string.InuAiProvidersActiveForVoice)
            else -> ""
        }
    }

    private fun displayName(meta: ProviderMeta): String {
        if (meta.id == InuConfig.TRANSCRIBE_PROVIDER_CUSTOM) {
            val name = InuConfig.AI_CHAT_CUSTOM_NAME.value.ifBlank { InuConfig.AI_TRANSCRIBE_CUSTOM_NAME.value }
            if (name.isNotBlank()) return name
        }
        return AiComposeHelper.providerDisplayName(meta.id)
    }

    private fun scopeIconRes(meta: ProviderMeta): Int = when {
        meta.chat && meta.voice -> R.drawable.inu_tabler_infinity
        meta.chat -> R.drawable.inu_tabler_keyboard
        else -> R.drawable.inu_tabler_microphone
    }

    /** Small leading glyph (same mechanism as [addExperimentalSpan]'s beta badge) marking scope,
     * not brand -- what it means is explained once in the screen's footer, not repeated per row. */
    private fun badgeLabel(meta: ProviderMeta): CharSequence {
        val span = ColoredImageSpan(scopeIconRes(meta), ColoredImageSpan.ALIGN_CENTER)
        span.setSize(AndroidUtilities.dp(14f))
        val badge = SpannableString(" ")
        badge.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return SpannableStringBuilder().append(badge).append(" ").append(displayName(meta))
    }

    private fun footerFor(id: Int): CharSequence = when (id) {
        InuConfig.TRANSCRIBE_PROVIDER_GROQ -> linkFooter(R.string.InuAiTranscribeGroqInfo, "https://console.groq.com/keys")
        InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> linkFooter(R.string.InuAiTranscribeGeminiInfo, "https://aistudio.google.com/apikey")
        InuConfig.TRANSCRIBE_PROVIDER_OPENAI -> linkFooter(R.string.InuAiTranscribeOpenAiInfo, "https://platform.openai.com/api-keys")
        InuConfig.TRANSCRIBE_PROVIDER_CF -> linkFooter(R.string.InuAiTranscribeCfInfo, "https://dash.cloudflare.com/profile/api-tokens")
        InuConfig.AI_PROVIDER_OPENROUTER -> linkFooter(R.string.InuAiProvidersOpenRouterInfo, "https://openrouter.ai/keys")
        else -> LocaleController.getString(R.string.InuAiTranscribeCustomInfo)
    }

    private fun linkFooter(infoRes: Int, url: String): CharSequence {
        val builder = SpannableStringBuilder(LocaleController.getString(infoRes))
        builder.append("\n").append(url)
        builder.setSpan(object : URLSpanNoUnderline(null) {
            override fun onClick(view: View) {
                context?.let { Browser.openUrl(it, url) }
            }
        }, builder.length - url.length, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return builder
    }

    private data class ProviderMeta(val id: Int, val chat: Boolean, val voice: Boolean)

    companion object {
        private const val PROVIDER_BASE = 26000
        private const val ACTIVE_CHAT_BASE = 26100
        private const val ACTIVE_VOICE_BASE = 26200
        private const val SAME_MODEL_BASE = 26300
        private val BUTTON_FETCH_MODELS = InuUtils.generateId()
        private val BUTTON_FETCH_VOICE_MODELS = InuUtils.generateId()

        private val ALL_PROVIDERS = listOf(
            ProviderMeta(InuConfig.TRANSCRIBE_PROVIDER_GEMINI, chat = true, voice = true),
            ProviderMeta(InuConfig.TRANSCRIBE_PROVIDER_OPENAI, chat = true, voice = true),
            ProviderMeta(InuConfig.TRANSCRIBE_PROVIDER_GROQ, chat = true, voice = true),
            ProviderMeta(InuConfig.TRANSCRIBE_PROVIDER_CUSTOM, chat = true, voice = true),
            ProviderMeta(InuConfig.AI_PROVIDER_OPENROUTER, chat = true, voice = false),
            ProviderMeta(InuConfig.TRANSCRIBE_PROVIDER_CF, chat = false, voice = true),
        )
    }
}
