package desu.inugram.core.plugins

object FsQuota {
    /** Keep in sync with Rust `fs::DEFAULT_QUOTA_BYTES`. */
    const val DEFAULT_BYTES = 50L * 1024 * 1024

    const val UNCAPPED = -1L

    fun parseSize(scope: String): Long? {
        val match = GrantCatalog.fsSizeMatch(scope) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val unit = when (match.groupValues[2].lowercase()) {
            "kb" -> 1024L
            "mb" -> 1024L * 1024
            else -> 1024L * 1024 * 1024
        }
        val ceiling = Long.MAX_VALUE / 2
        if (amount > ceiling / unit) return ceiling
        return amount * unit
    }

    /** `unsafe.fs` lifts the cap of `fs` alone */
    fun forGrants(grants: List<String>, name: String = "fs"): Long? {
        var quota: Long? = null
        var sawGrant = false
        for (token in grants) {
            val grant = PluginPermissions.parseGrant(token) ?: continue
            if (name == "fs" && grant.name == "unsafe.fs") return UNCAPPED
            if (grant.name != name) continue
            sawGrant = true
            for (scope in grant.scopes) {
                val size = parseSize(scope) ?: continue
                if (quota == null || size > quota) quota = size
            }
        }
        if (!sawGrant) return null
        return quota ?: DEFAULT_BYTES
    }
}
