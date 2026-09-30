package desu.inugram.ui.settings

import android.view.View
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ai.AiProviderStore
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AiProviderPickerActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAiProviderPickTitle)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val ctx = context ?: return
        items.add(UItem.asShadow(null))
        for (kind in AiProviderStore.PRESET_ORDER) {
            val tags = ArrayList<String>()
            if (AiProviderStore.canChat(kind)) tags.add(LocaleController.getString(R.string.InuAiProviderTagChat))
            if (AiProviderStore.canVoice(kind)) tags.add(LocaleController.getString(R.string.InuAiProviderTagVoice))
            items.add(
                UItem.asCustom(
                    InuUtils.generateId(),
                    AiProviderCardCell(ctx, kind, AiProviderStore.defaultName(kind), "", tags) {
                        presentFragment(AiProviderEditActivity.forNew(kind), true)
                    }
                )
            )
        }
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) = Unit
}
