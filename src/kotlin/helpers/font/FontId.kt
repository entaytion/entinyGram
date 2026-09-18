package desu.inugram.helpers.font


sealed interface FontId {
    data class Builtin(val key: String) : FontId
    data class System(val name: String) : FontId
    data class Family(val id: String) : FontId

    fun token(): String = when (this) {
        is Builtin -> key
        is System -> SYSTEM_PREFIX + name
        is Family -> FAMILY_PREFIX + id
    }

    companion object {
        private const val FAMILY_PREFIX = "font:"
        private const val SYSTEM_PREFIX = "sys:"

        fun parse(token: String): FontId = when {
            token.startsWith(FAMILY_PREFIX) -> Family(token.substring(FAMILY_PREFIX.length))
            token.startsWith(SYSTEM_PREFIX) -> System(token.substring(SYSTEM_PREFIX.length))
            else -> Builtin(token)
        }

        fun parseWithLegacyCompat(id: String): FontId = when {
            id.startsWith(FAMILY_PREFIX) -> Family(id.substring(FAMILY_PREFIX.length))
            id.startsWith(SYSTEM_PREFIX) -> System(id.substring(SYSTEM_PREFIX.length))
            id.isEmpty() -> Family("")
            FontLibrary.isBuiltinKey(id) -> Builtin(id)
            else -> Family(id)
        }
    }
}
