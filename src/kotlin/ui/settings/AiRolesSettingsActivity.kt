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
        val ctx = context ?: return
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
            val preview = role.prompt.replace('\n', ' ').trim().take(80)
            items.add(
                UItem.asCustom(
                    InuUtils.generateId(),
                    AiProviderCardCell(ctx, -1, role.text.ifBlank { LocaleController.getString(R.string.InuAiRoleNew) }, preview, tags, PALETTE[index % PALETTE.size]) {
                        presentFragment(AiRoleEditActivity(role.id))
                    }
                )
            )
        }
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id == BUTTON_ADD) presentFragment(AiRoleEditActivity(null))
    }

    companion object {
        private val BUTTON_ADD = InuUtils.generateId()
        private val PALETTE = intArrayOf(0xFF4285F4.toInt(), 0xFF10A37F.toInt(), 0xFFF55036.toInt(), 0xFF6467F2.toInt(), 0xFFF6821F.toInt(), 0xFF8E8E93.toInt())
    }
}
