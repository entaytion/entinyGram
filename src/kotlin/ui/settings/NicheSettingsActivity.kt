package desu.inugram.ui.settings

import android.view.View
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class NicheSettingsActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuNicheSettings)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuNicheSettingsInfo)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) = Unit
}
