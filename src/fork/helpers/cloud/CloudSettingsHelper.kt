package desu.inugram.helpers.cloud

import android.content.SharedPreferences
import android.util.Base64
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object CloudSettingsHelper {
    private const val KEY_PREFIX = "inu_settings"
    private const val CHUNK_KEY_PREFIX = "${KEY_PREFIX}_"
    private const val GEN_CHUNK_PREFIX = "${KEY_PREFIX}_chunk_"
    private const val UPDATED_AT_KEY = "${CHUNK_KEY_PREFIX}updated_at"
    private const val ENCODING_KEY = "${CHUNK_KEY_PREFIX}encoding"
    private const val ENCODING_GZIP_BASE64_V1 = "gzip_base64_v1"
    private const val MAX_CHUNK_CHARS = 3000
    private const val MAX_SYNC_CHUNKS = 2048
    private const val MAX_PAYLOAD_CHARS = MAX_SYNC_CHUNKS * MAX_CHUNK_CHARS
    private const val MAX_DECOMPRESSED_BYTES = 16L * 1024 * 1024
    private const val RESTORE_BATCH_SIZE = 50
    private const val AUTO_SYNC_DEBOUNCE_MS = 5000L

    @Volatile
    var restoring: Boolean = false

    @Volatile
    private var syncInFlight = false
    @Volatile
    private var dirtyDuringSync = false

    private val exportableInuKeys: Set<String> by lazy {
        InuConfig.items.filter { it.exportable }.map { it.key }.toSet()
    }

    private val pendingAutoSync = Runnable {
        if (!InuConfig.CLOUD_SYNC_AUTO.value) return@Runnable
        val account = resolveAccount(InuConfig.CLOUD_SYNC_ACCOUNT_ID.value)
        if (account < 0) return@Runnable
        if (syncInFlight) {
            dirtyDuringSync = true
            return@Runnable
        }
        syncInFlight = true
        dirtyDuringSync = false
        syncToCloud(account) { _, _ ->
            syncInFlight = false
            if (dirtyDuringSync) {
                dirtyDuringSync = false
                onSettingsChanged()
            }
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null) return@OnSharedPreferenceChangeListener
        val matters = if (prefs === InuConfig.prefs) key in exportableInuKeys
        else key in SettingsBackupHelper.STOCK_KEYS
        if (matters) onSettingsChanged()
    }

    fun attachAutoSyncListener() {
        InuConfig.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        SettingsBackupHelper.stockPrefs().registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun onSettingsChanged() {
        if (restoring) return
        if (!InuConfig.CLOUD_SYNC_AUTO.value) return
        if (resolveAccount(InuConfig.CLOUD_SYNC_ACCOUNT_ID.value) < 0) return
        AndroidUtilities.cancelRunOnUIThread(pendingAutoSync)
        AndroidUtilities.runOnUIThread(pendingAutoSync, AUTO_SYNC_DEBOUNCE_MS)
    }

    fun syncToCloud(account: Int, callback: (Boolean, String?) -> Unit) {
        Utilities.globalQueue.postRunnable {
            val payload = try {
                gzipBase64Encode(SettingsBackupHelper.export())
            } catch (e: Exception) {
                AndroidUtilities.runOnUIThread { callback(false, e.message) }
                return@postRunnable
            }
            AndroidUtilities.runOnUIThread {
                val numChunks = (payload.length + MAX_CHUNK_CHARS - 1) / MAX_CHUNK_CHARS
                if (numChunks > MAX_SYNC_CHUNKS) {
                    callback(false, "backup too large")
                    return@runOnUIThread
                }
                syncChunk(account, payload, newSyncId(), 0, numChunks, callback)
            }
        }
    }

    private fun newSyncId(): String = (0..Int.MAX_VALUE).random().toString(16)

    private fun syncChunk(
        account: Int,
        data: String,
        syncId: String,
        idx: Int,
        numChunks: Int,
        callback: (Boolean, String?) -> Unit,
    ) {
        if (idx >= numChunks) {
            // the manifest key is the commit marker: readers only touch this generation once it lands
            CloudStorageHelper.setItem(account, KEY_PREFIX, "$syncId:$numChunks") { _, error ->
                if (error != null) return@setItem callback(false, error)
                CloudStorageHelper.setItem(account, UPDATED_AT_KEY, System.currentTimeMillis().toString(), null)
                CloudStorageHelper.setItem(account, ENCODING_KEY, ENCODING_GZIP_BASE64_V1, null)
                cleanupStaleChunks(account, syncId)
                callback(true, null)
            }
            return
        }
        val start = idx * MAX_CHUNK_CHARS
        val end = minOf(start + MAX_CHUNK_CHARS, data.length)
        CloudStorageHelper.setItem(account, GEN_CHUNK_PREFIX + syncId + "_" + idx, data.substring(start, end)) { _, error ->
            if (error != null) callback(false, error)
            else syncChunk(account, data, syncId, idx + 1, numChunks, callback)
        }
    }

    private fun cleanupStaleChunks(account: Int, syncId: String) {
        CloudStorageHelper.getKeys(account) { keys, _ ->
            val stale = keys?.filter {
                (it.startsWith(GEN_CHUNK_PREFIX) && !it.startsWith(GEN_CHUNK_PREFIX + syncId + "_")) ||
                    it.matches(Regex("^${Regex.escape(CHUNK_KEY_PREFIX)}\\d+$"))
            }?.toTypedArray()
            if (!stale.isNullOrEmpty()) CloudStorageHelper.removeItems(account, stale, null)
        }
    }

    fun restoreFromCloud(account: Int, callback: (SettingsBackupHelper.ParseResult.Ok?, String?) -> Unit) {
        CloudStorageHelper.getItems(account, arrayOf(KEY_PREFIX, ENCODING_KEY)) { meta, error ->
            if (error != null || meta == null) {
                callback(null, error)
                return@getItems
            }
            val raw = meta[KEY_PREFIX].orEmpty()
            val syncId = raw.substringBefore(':').takeIf { raw.contains(':') }
            val numChunks = raw.substringAfter(':').toIntOrNull() ?: raw.toIntOrNull()
            if (numChunks == null || numChunks <= 0) {
                callback(null, null)
                return@getItems
            }
            if (numChunks > MAX_SYNC_CHUNKS) {
                callback(null, "backup too large")
                return@getItems
            }
            val encoding = meta[ENCODING_KEY]
            val chunkPrefix = if (syncId != null) GEN_CHUNK_PREFIX + syncId + "_" else CHUNK_KEY_PREFIX
            fetchChunks(account, numChunks, 0, chunkPrefix, StringBuilder()) { payload, fetchError ->
                if (payload == null) {
                    callback(null, fetchError)
                    return@fetchChunks
                }
                Utilities.globalQueue.postRunnable {
                    val parsed = try {
                        val json = if (encoding == ENCODING_GZIP_BASE64_V1) gzipBase64Decode(payload) else payload
                        SettingsBackupHelper.parse(json)
                    } catch (e: Exception) {
                        AndroidUtilities.runOnUIThread { callback(null, e.message) }
                        return@postRunnable
                    }
                    AndroidUtilities.runOnUIThread {
                        when (parsed) {
                            is SettingsBackupHelper.ParseResult.Ok -> callback(parsed, null)
                            SettingsBackupHelper.ParseResult.BadFormat ->
                                callback(null, LocaleController.getString(R.string.InuBackupImportBadFormat))
                        }
                    }
                }
            }
        }
    }

    private fun fetchChunks(
        account: Int,
        numChunks: Int,
        offset: Int,
        chunkPrefix: String,
        sb: StringBuilder,
        callback: (String?, String?) -> Unit,
    ) {
        if (sb.length > MAX_PAYLOAD_CHARS) {
            callback(null, "backup too large")
            return
        }
        if (offset >= numChunks) {
            callback(sb.toString(), null)
            return
        }
        val end = minOf(offset + RESTORE_BATCH_SIZE, numChunks)
        val keys = Array(end - offset) { chunkPrefix + (offset + it) }
        CloudStorageHelper.getItems(account, keys) { res, error ->
            if (error != null || res == null) {
                callback(null, error)
                return@getItems
            }
            for (i in offset until end) {
                val chunk = res[chunkPrefix + i]
                if (chunk == null) {
                    callback(null, "missing chunk $i")
                    return@getItems
                }
                sb.append(chunk)
            }
            fetchChunks(account, numChunks, end, chunkPrefix, sb, callback)
        }
    }

    fun deleteCloudBackup(account: Int, callback: (Boolean, String?) -> Unit) {
        CloudStorageHelper.getKeys(account) { keys, error ->
            if (error != null) return@getKeys callback(false, error)
            val ours = keys?.filter { it.startsWith(KEY_PREFIX) }?.toTypedArray()
            if (ours.isNullOrEmpty()) return@getKeys callback(false, null)
            CloudStorageHelper.removeItems(account, ours) { _, err ->
                callback(err == null, err)
            }
        }
    }

    private fun gzipBase64Encode(input: String): String {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(input.toByteArray(StandardCharsets.UTF_8)) }
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun gzipBase64Decode(input: String): String {
        if (input.length > MAX_PAYLOAD_CHARS) throw IOException("backup too large")
        val compressed = Base64.decode(input, Base64.DEFAULT)
        val baos = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(compressed)).use { gz ->
            val buf = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = gz.read(buf)
                if (read == -1) break
                total += read
                if (total > MAX_DECOMPRESSED_BYTES) throw IOException("backup too large")
                baos.write(buf, 0, read)
            }
        }
        return baos.toString(StandardCharsets.UTF_8.name())
    }

    fun fetchCloudTimestamp(account: Int, callback: (Long) -> Unit) {
        CloudStorageHelper.getItem(account, UPDATED_AT_KEY) { res, _ ->
            callback(res?.toLongOrNull() ?: 0L)
        }
    }

    private fun resolveAccount(userId: Long): Int {
        if (userId == 0L) return -1
        for (i in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            val u = UserConfig.getInstance(i)
            if (u.isClientActivated && u.clientUserId == userId) return i
        }
        return -1
    }
}
