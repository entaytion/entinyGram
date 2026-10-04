package desu.inugram.ui.settings

import android.view.View
import desu.inugram.helpers.ai.AiProviderStore
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AiProviderPickerActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAiProviderPickTitle)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asShadow(null))
        for ((index, kind) in AiProviderStore.PRESET_ORDER.withIndex()) {
            val tags = ArrayList<String>()
            if (AiProviderStore.canChat(kind)) tags.add(LocaleController.getString(R.string.InuAiProviderTagChat))
            if (AiProviderStore.canVoice(kind)) tags.add(LocaleController.getString(R.string.InuAiProviderTagVoice))
            items.add(UItem.asButton(KIND_BASE + index, AiFeatureSupport.iconFor(kind), AiProviderStore.defaultName(kind), tags.joinToString(" • ")))
        }
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val kind = AiProviderStore.PRESET_ORDER.getOrNull(item.id - KIND_BASE) ?: return
        presentFragment(AiProviderEditActivity.forNew(kind), true)
    }

    companion object {
        private const val KIND_BASE = 26500
    }
}
