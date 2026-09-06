package desu.inugram.ui.settings

import android.app.Dialog
import android.view.View
import android.widget.TextView
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ai.AiRole
import desu.inugram.helpers.ai.AiRolesHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

/**
 * Saved AI persona presets -- switch, add, or remove the role used by AI Compose. Tapping the
 * active preset expands it in place for editing, same accordion pattern as AI Providers: a short
 * Name (the "you are X" label) and an optional longer Prompt that replaces that label entirely
 * when set, for personas that need more than one line of instruction.
 */
class AiRolesSettingsActivity : SettingsPageActivity() {

    private var expandedId: String? = null

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAiRoles)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val ctx = context ?: return
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiRoles)))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAiRolesScreenDesc)))

        val roles = AiRolesHelper.roles()
        val activeId = AiRolesHelper.activeRole()?.id
        roles.forEachIndexed { index, role ->
            items.add(
                UItem.asRadio(ROLE_BASE + index, role.text.ifBlank { LocaleController.getString(R.string.InuAiRoleNew) }).also {
                    it.checked = role.id == activeId
                }
            )
            if (expandedId != role.id) return@forEachIndexed
            items.add(
                UItem.asCustom(InuUtils.generateId(), AiServiceFieldCell(ctx, LocaleController.getString(R.string.InuAiRoleNameHint), role.text, showCounter = true) {
                    AiRolesHelper.upsertRole(role.copy(text = it))
                    listView.adapter.update(true)
                })
            )
            items.add(
                UItem.asCustom(InuUtils.generateId(), AiServiceFieldCell(ctx, LocaleController.getString(R.string.InuAiRolePromptHint), role.prompt, android.text.InputType.TYPE_CLASS_TEXT) {
                    AiRolesHelper.upsertRole(role.copy(prompt = it))
                })
            )
            items.add(UItem.asButton(BUTTON_DELETE, LocaleController.getString(R.string.Delete)).red())
            items.add(UItem.asShadow(LocaleController.getString(R.string.InuAiRolePromptDesc)))
        }
        items.add(UItem.asButton(BUTTON_ADD, R.drawable.msg_add, LocaleController.getString(R.string.InuAiRoleNew)))
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id == BUTTON_ADD) {
            val role = AiRole(id = AiRolesHelper.newRoleId(), text = "")
            AiRolesHelper.upsertRole(role)
            expandedId = role.id
            listView.adapter.update(true)
            return
        }
        if (item.id == BUTTON_DELETE) {
            val role = expandedId?.let { id -> AiRolesHelper.roles().firstOrNull { it.id == id } } ?: return
            confirmDelete(role)
            return
        }
        val role = roleFor(item.id) ?: return
        if (AiRolesHelper.activeRole()?.id != role.id) {
            AiRolesHelper.setActiveRole(role.id)
            listView.adapter.update(true)
        } else {
            expandedId = if (expandedId == role.id) null else role.id
            listView.adapter.update(true)
        }
    }

    private fun roleFor(itemId: Int): AiRole? {
        val index = itemId - ROLE_BASE
        return AiRolesHelper.roles().getOrNull(index)
    }

    private fun confirmDelete(role: AiRole) {
        val ctx = context ?: return
        val dialog = AlertDialog.Builder(ctx, resourceProvider)
            .setTitle(role.text.ifBlank { LocaleController.getString(R.string.InuAiRoleNew) })
            .setMessage(LocaleController.getString(R.string.InuAiRoleDeleteConfirm))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.Delete)) { _, _ ->
                AiRolesHelper.deleteRole(role.id)
                if (expandedId == role.id) expandedId = null
                listView.adapter.update(true)
            }
            .create()
        showDialog(dialog)
        (dialog.getButton(Dialog.BUTTON_POSITIVE) as? TextView)
            ?.setTextColor(getThemedColor(Theme.key_text_RedBold))
    }

    companion object {
        private val BUTTON_ADD = InuUtils.generateId()
        private val BUTTON_DELETE = InuUtils.generateId()
        private const val ROLE_BASE = 25000
    }
}
