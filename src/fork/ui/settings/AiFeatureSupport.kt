package desu.inugram.ui.settings

import desu.inugram.InuConfig
import desu.inugram.helpers.ai.AiProviderStore
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment

// entiny: shared provider and model pickers for the per-feature AI pages
object AiFeatureSupport {

    fun iconFor(kind: Int): Int = when (kind) {
        InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> R.drawable.inu_ai_gemini
        InuConfig.TRANSCRIBE_PROVIDER_OPENAI -> R.drawable.inu_ai_openai
        InuConfig.TRANSCRIBE_PROVIDER_GROQ -> R.drawable.inu_ai_groq
        InuConfig.AI_PROVIDER_OPENROUTER -> R.drawable.inu_ai_openrouter
        InuConfig.TRANSCRIBE_PROVIDER_CF -> R.drawable.inu_ai_cloudflare
        else -> R.drawable.inu_tabler_cpu
    }

    fun providerLabel(providerId: String, voice: Boolean): String {
        val effective = effectiveProvider(providerId, voice)
        val own = AiProviderStore.get(providerId)
        return when {
            effective == null -> LocaleController.getString(R.string.InuAiProviderNotSet)
            own != null && own.id == effective.id -> providerName(effective)
            else -> "${LocaleController.getString(R.string.Default)} • ${providerName(effective)}"
        }
    }

    fun effectiveProvider(providerId: String, voice: Boolean): AiProviderStore.Provider? {
        val own = AiProviderStore.get(providerId)?.takeIf { if (voice) AiProviderStore.canVoice(it.kind) else AiProviderStore.canChat(it.kind) }
        return own ?: if (voice) AiProviderStore.voiceProvider() else AiProviderStore.chatProvider()
    }

    fun modelLabel(model: String, provider: AiProviderStore.Provider?, voice: Boolean): String {
        if (model.isNotBlank()) return model
        val fallback = provider?.let { if (voice) it.voiceModel else it.chatModel }.orEmpty()
        return if (fallback.isBlank()) LocaleController.getString(R.string.Default) else "${LocaleController.getString(R.string.Default)} • $fallback"
    }

    fun pickProvider(fragment: BaseFragment, voice: Boolean, current: String, onPicked: (String) -> Unit) {
        val context = fragment.parentActivity ?: return
        val candidates = AiProviderStore.all().filter { if (voice) AiProviderStore.canVoice(it.kind) else AiProviderStore.canChat(it.kind) }
        val labels = ArrayList<CharSequence>()
        labels.add(LocaleController.getString(R.string.Default))
        candidates.forEach { labels.add(providerName(it)) }
        val selected = candidates.indexOfFirst { it.id == current }.let { if (it < 0) 0 else it + 1 }
        fragment.showDialog(
            RadioDialogBuilder(context, fragment.resourceProvider)
                .setTitle(LocaleController.getString(R.string.InuAiFeatureProvider))
                .setItems(labels.toTypedArray(), selected) { dialog, which ->
                    val provider = candidates.getOrNull(which - 1)
                    onPicked(provider?.id.orEmpty())
                    dialog.dismiss()
                    if (provider != null && !AiProviderStore.isReady(provider)) {
                        fragment.presentFragment(AiProviderEditActivity.forExisting(provider.id))
                    }
                }
                .create()
        )
    }

    fun providerName(provider: AiProviderStore.Provider): String {
        if (AiProviderStore.isReady(provider)) return provider.name
        val missing = when {
            provider.kind == InuConfig.TRANSCRIBE_PROVIDER_CUSTOM -> R.string.InuAiEndpointUrl
            provider.key.isBlank() -> R.string.InuAiApiKeyHint
            else -> R.string.InuAiTranscribeAccountId
        }
        return "${provider.name} • ${LocaleController.getString(missing)}: ${LocaleController.getString(R.string.InuAiProviderNotSet)}"
    }

    fun pickModel(fragment: BaseFragment, provider: AiProviderStore.Provider?, voice: Boolean, current: String, onPicked: (String) -> Unit) {
        if (provider == null) return
        if (!AiProviderStore.isReady(provider)) {
            fragment.presentFragment(AiProviderEditActivity.forExisting(provider.id))
            return
        }
        val seed = current.ifBlank { if (voice) provider.voiceModel else provider.chatModel }
        AiModelPickerActivity.present(fragment, provider, voice, seed, onPicked)
    }
}
