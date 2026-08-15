package desu.inugram.ui.settings

import android.view.View
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ai.AiComposeHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

/** Services list page matching the reference design. */
class AiServicesSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAiServices)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val ctx = context ?: return

        // Section: Сервіси
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiServices)))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAiServicesHeaderDesc)))

        val endpoints = AiComposeHelper.endpoints()
        val activeId = AiComposeHelper.activeEndpoint()?.id

        endpoints.forEachIndexed { index, endpoint ->
            val title = endpoint.name.ifBlank { AiComposeHelper.host(endpoint.url) }.ifBlank { endpoint.url }
            items.add(
                UItem.asRadio(ENDPOINT_BASE + index, title).also {
                    it.checked = endpoint.id == activeId
                }
            )
        }

        // "+ Новий сервіс" row
        items.add(
            UItem.asButton(
                BUTTON_ADD,
                R.drawable.msg_add,
                LocaleController.getString(R.string.InuAiServiceNew)
            )
        )
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id == BUTTON_ADD) {
            presentFragment(AiServiceSettingsActivity())
            return
        }
        val index = item.id - ENDPOINT_BASE
        val endpoints = AiComposeHelper.endpoints()
        if (index !in endpoints.indices) return
        val endpoint = endpoints[index]
        if (AiComposeHelper.activeEndpoint()?.id != endpoint.id) {
            AiComposeHelper.setActiveEndpoint(endpoint.id)
            listView.adapter.update(true)
        } else {
            presentFragment(AiServiceSettingsActivity(endpoint.id))
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        val index = item.id - ENDPOINT_BASE
        val endpoints = AiComposeHelper.endpoints()
        if (index !in endpoints.indices) return super.onLongClick(item, view, position, x, y)
        presentFragment(AiServiceSettingsActivity(endpoints[index].id))
        return true
    }

    companion object {
        private val BUTTON_ADD = InuUtils.generateId()
        private const val ENDPOINT_BASE = 21000
    }
}
