package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ai.AiProviderStore
import desu.inugram.ui.showInputDialog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AiProvidersSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAiProvidersTitle)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asTopView(
                LocaleController.getString(R.string.InuAiProvidersTitle),
                LocaleController.getString(R.string.InuAiProvidersSubtitle),
                120,
                "tg_superplaceholders_android_2",
                "🤖🏝️",
            )
        )
        items.add(UItem.asButton(BUTTON_ADD, R.drawable.msg_add, LocaleController.getString(R.string.InuAiProviderAdd)))
        items.add(UItem.asShadow(null))

        val providers = AiProviderStore.all()
        if (providers.isEmpty()) {
            items.add(UItem.asCenterShadow(LocaleController.getString(R.string.InuAiProvidersEmpty)))
        } else {
            val ctx = context ?: return
            val chatId = AiProviderStore.chatProvider()?.id
            val voiceId = AiProviderStore.voiceProvider()?.id
            for (p in providers) {
                val tags = ArrayList<String>()
                if (p.id == chatId) tags.add(LocaleController.getString(R.string.InuAiProviderTagChat))
                if (p.id == voiceId) tags.add(LocaleController.getString(R.string.InuAiProviderTagVoice))
                val model = p.chatModel.ifBlank { p.voiceModel }
                items.add(
                    UItem.asCustom(
                        InuUtils.generateId(),
                        AiProviderCardCell(ctx, p.kind, p.name, model, tags) {
                            presentFragment(AiProviderEditActivity.forExisting(p.id))
                        }
                    )
                )
            }
            items.add(UItem.asShadow(null))
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiTranscribe)))
        items.add(
            UItem.asButton(
                BUTTON_LANGUAGE,
                LocaleController.getString(R.string.InuAiTranscribeLanguage),
                InuConfig.AI_TRANSCRIBE_LANGUAGE.value.ifBlank { LocaleController.getString(R.string.InuAiProviderNotSet) },
            )
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAiTranscribeLanguageInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_ADD -> presentFragment(AiProviderPickerActivity())
            BUTTON_LANGUAGE -> showInputDialog(
                this,
                LocaleController.getString(R.string.InuAiTranscribeLanguage),
                initialText = InuConfig.AI_TRANSCRIBE_LANGUAGE.value,
                selectAll = true,
            ) { text ->
                InuConfig.AI_TRANSCRIBE_LANGUAGE.value = text.trim()
                listView?.adapter?.update(true)
                true
            }
        }
    }

    companion object {
        private val BUTTON_ADD = InuUtils.generateId()
        private val BUTTON_LANGUAGE = InuUtils.generateId()
    }
}
