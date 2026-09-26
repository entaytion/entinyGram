package desu.inugram.helpers.dialogs

import org.telegram.tgnet.TLRPC

// entiny: color-codes the dialogs-list/hints/share-picker presence dot by last-seen freshness instead of plain online/offline
object PresenceColorHelper {
    private const val COLOR_ONLINE = 0xFF2196F3.toInt()
    private const val COLOR_RECENT_20M = 0xFFFFC107.toInt()
    private const val COLOR_RECENT_1H = 0xFFF44336.toInt()

    private const val TIER_20M_SECONDS = 20 * 60
    private const val TIER_1H_SECONDS = 60 * 60

    @JvmStatic
    fun colorFor(status: TLRPC.UserStatus?, isOnlineOverride: Boolean): Int {
        if (isOnlineOverride || status is TLRPC.TL_userStatusOnline) return COLOR_ONLINE
        // entiny: most contacts hide their exact last-seen time (privacy setting), so the server only ever
        // sends the coarse buckets below instead of TL_userStatusOffline+expires -- without this branch the
        // dot never colored for them at all, which is most contacts in practice
        when (status) {
            is TLRPC.TL_userStatusRecently -> return COLOR_RECENT_20M
            is TLRPC.TL_userStatusLastWeek -> return COLOR_RECENT_1H
            is TLRPC.TL_userStatusLastMonth -> return 0
            else -> {}
        }
        val lastSeen = (status as? TLRPC.TL_userStatusOffline)?.expires ?: return 0
        val elapsed = (System.currentTimeMillis() / 1000L).toInt() - lastSeen
        return when {
            elapsed < 0 -> COLOR_ONLINE
            elapsed <= TIER_20M_SECONDS -> COLOR_RECENT_20M
            elapsed <= TIER_1H_SECONDS -> COLOR_RECENT_1H
            else -> 0
        }
    }
}
