package desu.inugram.ui.settings

import android.view.View
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ai.AiProviderStore
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
            val chatId = AiProviderStore.chatProvider()?.id
            val voiceId = AiProviderStore.voiceProvider()?.id
            for ((index, p) in providers.withIndex()) {
                val tags = ArrayList<String>()
                if (p.id == chatId) tags.add(LocaleController.getString(R.string.InuAiProviderTagChat))
                if (p.id == voiceId) tags.add(LocaleController.getString(R.string.InuAiProviderTagVoice))
                val value = if (tags.isNotEmpty()) tags.joinToString(" • ") else p.chatModel.ifBlank { p.voiceModel }
                items.add(UItem.asButton(ENTRY_BASE + index, AiFeatureSupport.iconFor(p.kind), p.name, value))
            }
            items.add(UItem.asShadow(null))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id >= ENTRY_BASE) {
            AiProviderStore.all().getOrNull(item.id - ENTRY_BASE)?.let { presentFragment(AiProviderEditActivity.forExisting(it.id)) }
            return
        }
        if (item.id == BUTTON_ADD) presentFragment(AiProviderPickerActivity())
    }

    companion object {
        private val BUTTON_ADD = InuUtils.generateId()
        private const val ENTRY_BASE = 26000
    }
}
