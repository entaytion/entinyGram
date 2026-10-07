package desu.inugram.helpers.plugins.market

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.Utilities
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

// entiny: the catalog lives in the SDK plugin; this only fetches a plugin file and checks it against the index's hash
object Market {
    /** [onDone] runs on the UI thread with the plugin's source, or with a reason it could not be had */
    fun download(url: String, sha256: String?, onDone: (String?, String?) -> Unit) {
        Utilities.globalQueue.postRunnable {
            val result = try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                val bytes = try {
                    if (connection.responseCode !in 200..299) throw java.io.IOException("HTTP ${connection.responseCode}")
                    connection.inputStream.use { it.readBytes() }
                } finally {
                    connection.disconnect()
                }
                if (sha256 != null && !hash(bytes).equals(sha256, ignoreCase = true)) null to "checksum"
                else bytes.toString(Charsets.UTF_8) to null
            } catch (e: Exception) {
                null to (e.message ?: e.javaClass.simpleName)
            }
            AndroidUtilities.runOnUIThread { onDone(result.first, result.second) }
        }
    }

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
