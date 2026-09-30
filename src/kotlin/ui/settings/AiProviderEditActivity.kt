package desu.inugram.ui.settings

import android.text.InputType
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ai.AiModelsHelper
import desu.inugram.helpers.ai.AiProviderStore
import desu.inugram.ui.showInputDialog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AiProviderEditActivity(private val existingId: String?, private val kind: Int) : SettingsPageActivity() {

    private var draft: AiProviderStore.Provider = AiProviderStore.get(existingId) ?: AiProviderStore.create(kind)
    private var saved: Boolean = existingId != null && AiProviderStore.get(existingId) != null

    override fun getTitle(): CharSequence = draft.name

    private val isCustom get() = draft.kind == InuConfig.TRANSCRIBE_PROVIDER_CUSTOM
    private val isCloudflare get() = draft.kind == InuConfig.TRANSCRIBE_PROVIDER_CF

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val notSet = LocaleController.getString(R.string.InuAiProviderNotSet)
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiProviderConnection)))
        items.add(UItem.asButton(BUTTON_NAME, LocaleController.getString(R.string.InuAiProviderName), draft.name))
        if (isCustom) {
            items.add(UItem.asButton(BUTTON_URL, LocaleController.getString(R.string.InuAiEndpointUrl), draft.url.ifBlank { notSet }))
            items.add(UItem.asButton(BUTTON_DETECT, LocaleController.getString(R.string.InuAiProviderDetect)))
        }
        if (isCloudflare) {
            items.add(UItem.asButton(BUTTON_ACCOUNT, LocaleController.getString(R.string.InuAiTranscribeAccountId), draft.accountId.ifBlank { notSet }))
        }
        val keyTitle = if (isCloudflare) R.string.InuAiTranscribeApiToken else R.string.InuAiApiKeyHint
        items.add(UItem.asButton(BUTTON_KEY, LocaleController.getString(keyTitle), maskKey(draft.key, notSet)))
        items.add(UItem.asShadow(if (isCustom) LocaleController.getString(R.string.InuAiChatCustomInfo) else null))

        val canChat = AiProviderStore.canChat(draft.kind)
        val canVoice = AiProviderStore.canVoice(draft.kind)
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiProviderModels)))
        if (canChat) {
            items.add(UItem.asButton(BUTTON_CHAT_MODEL, LocaleController.getString(R.string.InuAiProviderChatModel), draft.chatModel.ifBlank { notSet }))
        }
        if (canVoice) {
            items.add(UItem.asButton(BUTTON_VOICE_MODEL, LocaleController.getString(R.string.InuAiProviderVoiceModel), draft.voiceModel.ifBlank { notSet }))
        }
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiProviderUseFor)))
        if (canChat) {
            items.add(
                UItem.asCheck(TOGGLE_CHAT, LocaleController.getString(R.string.InuAiProvidersActiveForChat))
                    .setChecked(saved && AiProviderStore.chatProvider()?.id == draft.id)
            )
        }
        if (canVoice) {
            items.add(
                UItem.asCheck(TOGGLE_VOICE, LocaleController.getString(R.string.InuAiProvidersActiveForVoice))
                    .setChecked(saved && AiProviderStore.voiceProvider()?.id == draft.id)
            )
        }
        items.add(UItem.asShadow(null))

        if (saved) {
            items.add(UItem.asButton(BUTTON_DELETE, R.drawable.inu_tabler_trash, LocaleController.getString(R.string.Delete)))
            items.add(UItem.asShadow(null))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_NAME -> ask(R.string.InuAiProviderName, draft.name) { text ->
                if (text.isBlank()) return@ask false
                draft = draft.copy(name = text)
                true
            }

            BUTTON_URL -> ask(R.string.InuAiEndpointUrl, draft.url, InputType.TYPE_TEXT_VARIATION_URI) { text ->
                draft = draft.copy(url = text)
                true
            }

            BUTTON_ACCOUNT -> ask(R.string.InuAiTranscribeAccountId, draft.accountId) { text ->
                draft = draft.copy(accountId = text)
                true
            }

            BUTTON_KEY -> ask(
                if (isCloudflare) R.string.InuAiTranscribeApiToken else R.string.InuAiApiKeyHint,
                draft.key,
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
            ) { text ->
                draft = draft.copy(key = text)
                true
            }

            BUTTON_DETECT -> detectEndpoint()

            BUTTON_CHAT_MODEL -> AiModelPickerActivity.present(this, draft, false, draft.chatModel) { model ->
                draft = draft.copy(chatModel = model)
                persist()
                listView?.adapter?.update(true)
            }

            BUTTON_VOICE_MODEL -> AiModelPickerActivity.present(this, draft, true, draft.voiceModel) { model ->
                draft = draft.copy(voiceModel = model)
                persist()
                listView?.adapter?.update(true)
            }

            TOGGLE_CHAT -> {
                persist()
                val on = AiProviderStore.chatProvider()?.id != draft.id
                InuConfig.AI_CHAT_PROVIDER_ID.value = if (on) draft.id else AiProviderStore.NONE
                (view as? TextCheckCell)?.isChecked = on
            }

            TOGGLE_VOICE -> {
                persist()
                val on = AiProviderStore.voiceProvider()?.id != draft.id
                InuConfig.AI_VOICE_PROVIDER_ID.value = if (on) draft.id else AiProviderStore.NONE
                (view as? TextCheckCell)?.isChecked = on
            }

            BUTTON_DELETE -> {
                AiProviderStore.remove(draft.id)
                finishFragment()
            }
        }
    }

    private fun ask(titleRes: Int, initial: String, inputType: Int = InputType.TYPE_CLASS_TEXT, apply: (String) -> Boolean) {
        showInputDialog(this, LocaleController.getString(titleRes), initialText = initial, selectAll = true, inputType = inputType) { raw ->
            if (!apply(raw.trim())) return@showInputDialog false
            persist()
            listView?.adapter?.update(true)
            true
        }
    }

    private fun persist() {
        val first = !saved
        AiProviderStore.upsert(draft)
        saved = true
        actionBar?.setTitle(draft.name)
        if (first) {
            // entiny: the first saved provider becomes active so it works immediately
            if (AiProviderStore.canChat(draft.kind) && InuConfig.AI_CHAT_PROVIDER_ID.value.isBlank()) InuConfig.AI_CHAT_PROVIDER_ID.value = draft.id
            if (AiProviderStore.canVoice(draft.kind) && InuConfig.AI_VOICE_PROVIDER_ID.value.isBlank()) InuConfig.AI_VOICE_PROVIDER_ID.value = draft.id
        }
    }

    private fun detectEndpoint() {
        if (draft.url.isBlank()) {
            BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.InuAiEndpointUrl)).show()
            return
        }
        AiModelsHelper.detectEndpoint(draft.url, draft.key) { result ->
            result.onSuccess { base ->
                draft = draft.copy(url = base)
                persist()
                listView?.adapter?.update(true)
                BulletinFactory.of(this)
                    .createSimpleBulletin(R.raw.info, LocaleController.formatString(R.string.InuAiProviderDetected, base))
                    .show()
            }.onFailure {
                BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.InuAiProviderDetectFailed)).show()
            }
        }
    }

    private fun maskKey(key: String, empty: String): String = when {
        key.isBlank() -> empty
        key.length > 8 -> "••••" + key.takeLast(4)
        else -> "••••"
    }

    companion object {
        private val BUTTON_NAME = InuUtils.generateId()
        private val BUTTON_URL = InuUtils.generateId()
        private val BUTTON_DETECT = InuUtils.generateId()
        private val BUTTON_ACCOUNT = InuUtils.generateId()
        private val BUTTON_KEY = InuUtils.generateId()
        private val BUTTON_CHAT_MODEL = InuUtils.generateId()
        private val BUTTON_VOICE_MODEL = InuUtils.generateId()
        private val TOGGLE_CHAT = InuUtils.generateId()
        private val TOGGLE_VOICE = InuUtils.generateId()
        private val BUTTON_DELETE = InuUtils.generateId()

        fun forExisting(id: String) = AiProviderEditActivity(id, -1)

        fun forNew(kind: Int) = AiProviderEditActivity(null, kind)
    }
}
