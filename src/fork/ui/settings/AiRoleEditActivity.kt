package desu.inugram.ui.settings

import android.app.Dialog
import android.text.InputType
import android.view.View
import android.widget.TextView
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ai.AiRole
import desu.inugram.helpers.ai.AiRolesHelper
import desu.inugram.ui.showInputDialog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AiRoleEditActivity(roleId: String?) : SettingsPageActivity() {

    private var draft: AiRole = AiRolesHelper.roles().firstOrNull { it.id == roleId } ?: AiRole(AiRolesHelper.newRoleId(), "")
    private var saved: Boolean = roleId != null && AiRolesHelper.roles().any { it.id == roleId }

    override fun getTitle(): CharSequence = draft.text.ifBlank { LocaleController.getString(R.string.InuAiRoleNew) }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val notSet = LocaleController.getString(R.string.InuAiProviderNotSet)
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAiRoleNameHint)))
        items.add(UItem.asButton(BUTTON_NAME, LocaleController.getString(R.string.InuAiRoleNameHint), draft.text.ifBlank { notSet }))
        items.add(UItem.asButton(BUTTON_PROMPT, LocaleController.getString(R.string.InuAiRolePrompt), draft.prompt.replace('\n', ' ').trim().take(40).ifBlank { notSet }))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAiRolePromptDesc)))

        items.add(
            UItem.asCheck(TOGGLE_USE, LocaleController.getString(R.string.InuAiRoleUse))
                .setChecked(saved && AiRolesHelper.activeRole()?.id == draft.id)
        )
        items.add(UItem.asShadow(null))

        if (saved) {
            items.add(UItem.asButton(BUTTON_DELETE, R.drawable.inu_tabler_trash, LocaleController.getString(R.string.Delete)).red())
            items.add(UItem.asShadow(null))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_NAME -> showInputDialog(
                this,
                LocaleController.getString(R.string.InuAiRoleNameHint),
                initialText = draft.text,
                selectAll = true,
            ) { text ->
                if (text.isBlank()) return@showInputDialog false
                draft = draft.copy(text = text)
                persist()
                true
            }

            BUTTON_PROMPT -> showInputDialog(
                this,
                LocaleController.getString(R.string.InuAiRolePrompt),
                initialText = draft.prompt,
                inputType = InputType.TYPE_CLASS_TEXT,
                multiline = true,
            ) { text ->
                draft = draft.copy(prompt = text)
                persist()
                true
            }

            TOGGLE_USE -> {
                persist()
                AiRolesHelper.setActiveRole(draft.id)
                (view as? TextCheckCell)?.isChecked = true
            }

            BUTTON_DELETE -> confirmDelete()
        }
    }

    private fun persist() {
        if (draft.text.isBlank() && draft.prompt.isBlank()) return
        AiRolesHelper.upsertRole(draft)
        saved = true
        actionBar?.setTitle(getTitle())
        listView?.adapter?.update(true)
    }

    private fun confirmDelete() {
        val ctx = context ?: return
        val dialog = AlertDialog.Builder(ctx, resourceProvider)
            .setTitle(getTitle())
            .setMessage(LocaleController.getString(R.string.InuAiRoleDeleteConfirm))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.Delete)) { _, _ ->
                AiRolesHelper.deleteRole(draft.id)
                finishFragment()
            }
            .create()
        showDialog(dialog)
        (dialog.getButton(Dialog.BUTTON_POSITIVE) as? TextView)?.setTextColor(getThemedColor(Theme.key_text_RedBold))
    }

    companion object {
        private val BUTTON_NAME = InuUtils.generateId()
        private val BUTTON_PROMPT = InuUtils.generateId()
        private val BUTTON_DELETE = InuUtils.generateId()
        private val TOGGLE_USE = InuUtils.generateId()
    }
}
