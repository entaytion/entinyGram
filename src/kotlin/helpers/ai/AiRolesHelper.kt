package desu.inugram.helpers.ai

import desu.inugram.InuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import java.util.UUID

object AiRolesHelper {

    @JvmStatic
    fun roles(): List<AiRole> {
        seedDefaults()
        return InuConfig.AI_ROLES.value
    }

    // entiny: one-time starter set; never re-added after the user deletes or edits roles
    private fun seedDefaults() {
        if (InuConfig.AI_ROLES_SEEDED.value) return
        InuConfig.AI_ROLES_SEEDED.value = true
        if (InuConfig.AI_ROLES.value.isNotEmpty()) return
        val defaults = listOf(
            AiRole(
                newRoleId(),
                LocaleController.getString(R.string.InuAiRolesAssistant),
                "You are a helpful, precise assistant. Follow the instruction exactly, reply in the same language as the user's text, and add no preamble or commentary.",
            ),
            AiRole(
                newRoleId(),
                LocaleController.getString(R.string.InuAiRoleDefaultEditor),
                "You are a meticulous copy editor. Keep the author's meaning, voice and formatting; fix mistakes and improve clarity and flow without adding new facts or changing the tone.",
            ),
            AiRole(
                newRoleId(),
                LocaleController.getString(R.string.InuAiRoleDefaultConcise),
                "You write short, direct text. Cut filler and repetition, keep every essential fact, use plain words and short sentences, and reply in the same language as the input.",
            ),
        )
        InuConfig.AI_ROLES.value = defaults
        InuConfig.AI_ACTIVE_ROLE.value = defaults.first().id
    }

    @JvmStatic
    fun activeRole(): AiRole? {
        val list = roles()
        if (list.isEmpty()) return null
        val activeId = InuConfig.AI_ACTIVE_ROLE.value
        return list.firstOrNull { it.id == activeId } ?: list.first()
    }

    @JvmStatic
    fun activeRoleText(): String = activeRole()?.text.orEmpty()

    @JvmStatic
    fun setActiveRole(id: String) {
        InuConfig.AI_ACTIVE_ROLE.value = id
    }

    @JvmStatic
    fun upsertRole(role: AiRole) {
        val list = roles().toMutableList()
        val index = list.indexOfFirst { it.id == role.id }
        if (index >= 0) list[index] = role else list.add(role)
        InuConfig.AI_ROLES.value = list
        if (InuConfig.AI_ACTIVE_ROLE.value.isBlank()) InuConfig.AI_ACTIVE_ROLE.value = role.id
    }

    @JvmStatic
    fun deleteRole(id: String) {
        InuConfig.AI_ROLES.value = roles().filterNot { it.id == id }
        if (InuConfig.AI_ACTIVE_ROLE.value == id) {
            InuConfig.AI_ACTIVE_ROLE.value = roles().firstOrNull()?.id.orEmpty()
        }
    }

    @JvmStatic
    fun newRoleId(): String = UUID.randomUUID().toString()
}
