package desu.inugram.helpers.ai

import desu.inugram.InuConfig
import java.util.UUID

/** CRUD for saved AI persona presets ([AiRole]), used by the AI Roles settings screen and [AiComposeHelper]. */
object AiRolesHelper {

    @JvmStatic
    fun roles(): List<AiRole> = InuConfig.AI_ROLES.value

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
