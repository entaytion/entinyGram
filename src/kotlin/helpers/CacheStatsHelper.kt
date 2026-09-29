package desu.inugram.helpers

import desu.inugram.InuConfig
import desu.inugram.helpers.chat.SavedMessagesHelper
import desu.inugram.helpers.dialogs.RecentChatsHelper
import desu.inugram.helpers.pillstack.ExchangeRates
import desu.inugram.helpers.pillstack.GoldPrice
import desu.inugram.helpers.security.PresenceHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessagesStorage
import java.io.File

object CacheStatsHelper {
    enum class Kind { DELETED, EDITS, REACTIONS, MEDIA, PRESENCE, FEED, RECENT, PILLS, TEMP, LOGS }

    data class Stat(val kind: Kind, val count: Int, val size: Long)

    fun load(account: Int, onResult: (List<Stat>) -> Unit) {
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val deleted = InuDatabaseHelper.getTableStat(db, "inu_deleted_messages", "text", 250L)
            val edits = InuDatabaseHelper.getTableStat(db, "inu_edit_history", "text", 150L)
            val reactions = InuDatabaseHelper.getTableStat(db, "inu_deleted_reactions", null, 40L)
            val recent = InuDatabaseHelper.getTableStat(db, "inu_recent_dialogs", null, 16L)
            val presence = InuDatabaseHelper.getPresenceLogsStats(db)
            val feed = InuDatabaseHelper.getFeedCacheStat(db, account)
            val media = SavedMessagesHelper.getSavedMediaDir().let(::filesIn)
            val temp = tempDirs().flatMap(::filesIn)
            val logs = logFiles()
            val pills = listOf(InuConfig.PILL_STACK_RATE_CACHE.value, InuConfig.PILL_STACK_GOLD_CACHE.value).filter { it.isNotEmpty() }
            val stats = listOf(
                Stat(Kind.DELETED, deleted.count, deleted.estimatedSize),
                Stat(Kind.EDITS, edits.count, edits.estimatedSize),
                Stat(Kind.REACTIONS, reactions.count, reactions.estimatedSize),
                Stat(Kind.MEDIA, media.size, media.sumOf { it.length() }),
                Stat(Kind.PRESENCE, presence.count, presence.estimatedSize),
                Stat(Kind.FEED, feed.count, feed.estimatedSize),
                Stat(Kind.RECENT, recent.count, recent.estimatedSize),
                Stat(Kind.PILLS, pills.size, pills.sumOf { it.length.toLong() }),
                Stat(Kind.TEMP, temp.size, temp.sumOf { it.length() }),
                Stat(Kind.LOGS, logs.size, logs.sumOf { it.length() }),
            )
            AndroidUtilities.runOnUIThread { onResult(stats) }
        }
    }

    fun clear(account: Int, kind: Kind, onDone: Runnable) {
        when (kind) {
            Kind.DELETED -> SavedMessagesHelper.clearCache(account, null, true, onDone)
            Kind.EDITS -> SavedMessagesHelper.clearEditHistory(account, onDone)
            Kind.REACTIONS -> onStorage(account, onDone) { InuDatabaseHelper.clearDeletedReactions(it) }
            Kind.MEDIA -> onStorage(account, onDone) {
                InuDatabaseHelper.detachSavedMedia(it)
                SavedMessagesHelper.getSavedMediaDir().listFiles()?.filter { f -> f.isFile && f.name != ".nomedia" }?.forEach { f -> f.delete() }
            }
            Kind.PRESENCE -> PresenceHelper.clearLogs(account, null, onDone)
            Kind.FEED -> onStorage(account, onDone) { InuDatabaseHelper.clearFeedCache(it, account) }
            Kind.RECENT -> {
                RecentChatsHelper.clearRecentDialogs(account)
                onDone.run()
            }
            Kind.PILLS -> {
                InuConfig.PILL_STACK_RATE_CACHE.value = ""
                InuConfig.PILL_STACK_RATE_CACHE_TIME.value = 0L
                InuConfig.PILL_STACK_GOLD_CACHE.value = ""
                InuConfig.PILL_STACK_GOLD_CACHE_TIME.value = 0L
                ExchangeRates.clearCache()
                GoldPrice.clearCache()
                onDone.run()
            }
            Kind.TEMP -> onFiles(onDone) { tempDirs().flatMap(::filesIn).forEach { runCatching { it.delete() } } }
            Kind.LOGS -> onFiles(onDone) {
                FileLog.cleanupLogs()
                logFiles().forEach { runCatching { it.delete() } }
            }
        }
    }

    private fun onStorage(account: Int, onDone: Runnable, block: (org.telegram.SQLite.SQLiteDatabase) -> Unit) {
        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            storage.database?.let(block)
            AndroidUtilities.runOnUIThread(onDone)
        }
    }

    private fun onFiles(onDone: Runnable, block: () -> Unit) {
        Thread {
            block()
            AndroidUtilities.runOnUIThread(onDone)
        }.start()
    }

    private fun filesIn(dir: File): List<File> =
        dir.listFiles()?.flatMap { if (it.isDirectory) filesIn(it) else listOf(it) }?.filter { it.isFile && it.name != ".nomedia" } ?: emptyList()

    private fun tempDirs(): List<File> = listOf(
        File(ApplicationLoader.applicationContext.filesDir, "cache/inu_share_once"),
        File(FileLoader.getInternalCacheDir(), "inu_forward_once"),
    )

    private fun logFiles(): List<File> {
        val result = LinkedHashMap<String, File>()
        AndroidUtilities.getLogsDir()?.let { dir -> filesIn(dir).forEach { result[it.absolutePath] = it } }
        for (f in listOf(CrashReporter.getLogFile(), CrashReporter.getHeapDumpFile())) result[f.absolutePath] = f
        return result.values.filter { it.isFile && it.length() > 0 }
    }
}
