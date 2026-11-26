package desu.inugram.helpers.dialogs

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC

object JoinedChannelRepair {
    @JvmStatic
    fun run(account: Int) {
        if (!UserConfig.getInstance(account).isClientActivated) return
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val ids = HashSet<Long>()
            val chats = ArrayList<TLRPC.Chat>()
            try {
                val cursor = db.queryFinalized("SELECT did FROM dialogs WHERE folder_id = -1 AND did < 0")
                try {
                    while (cursor.next()) ids.add(-cursor.longValue(0))
                } finally {
                    cursor.dispose()
                }
                if (ids.isEmpty()) return@postRunnable
                storage.getChatsInternal(ids.joinToString(","), chats)
            } catch (e: Exception) {
                storage.checkSQLException(e)
                return@postRunnable
            }
            AndroidUtilities.runOnUIThread {
                val controller = MessagesController.getInstance(account)
                controller.putChats(chats, true)
                chats.filter { it.id in ids }.forEach { controller.inu_loadMissingJoinedChannelDialog(it.id) }
            }
        }
    }
}
