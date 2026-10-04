package desu.inugram.ui.settings

import android.view.View
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ai.AiRolesHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AiRolesSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAiRoles)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asTopView(
                LocaleController.getString(R.string.InuAiRoles),
                LocaleController.getString(R.string.InuAiRolesSubtitle),
                "RestrictedEmoji",
                "📝",
            )
        )
        items.add(UItem.asButton(BUTTON_ADD, R.drawable.msg_add, LocaleController.getString(R.string.InuAiRoleAdd)))
        items.add(UItem.asShadow(null))

        val activeId = AiRolesHelper.activeRole()?.id
        for ((index, role) in AiRolesHelper.roles().withIndex()) {
            val tags = if (role.id == activeId) listOf(LocaleController.getString(R.string.InuAiRoleTagActive)) else emptyList()
            items.add(
                UItem.asButton(
                    ROLE_BASE + index,
                    R.drawable.inu_tabler_sparkles,
                    role.text.ifBlank { LocaleController.getString(R.string.InuAiRoleNew) },
                    tags.joinToString(" • "),
                )
            )
        }
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id == BUTTON_ADD) {
            presentFragment(AiRoleEditActivity(null))
        } else if (item.id >= ROLE_BASE) {
            AiRolesHelper.roles().getOrNull(item.id - ROLE_BASE)?.let { presentFragment(AiRoleEditActivity(it.id)) }
        }
    }

    companion object {
        private val BUTTON_ADD = InuUtils.generateId()
        private const val ROLE_BASE = 27000
    }
}
