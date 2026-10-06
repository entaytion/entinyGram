package desu.inugram.helpers.translate

import android.util.Log
import org.telegram.messenger.Utilities
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

object LocaleCacheHelper {
    private const val TAG = "LocaleCacheHelper"
    private const val CACHE_VERSION = 1

    @JvmStatic
    fun readCache(file: File): HashMap<String, String>? {
        val cacheFile = File(file.absolutePath + ".bin")
        if (!cacheFile.exists() || cacheFile.length() <= 0L || cacheFile.lastModified() < file.lastModified()) {
            return null
        }
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(cacheFile), 32768)).use { input ->
                val version = input.readInt()
                if (version != CACHE_VERSION) {
                    return null
                }
                val size = input.readInt()
                if (size < 0 || size > 500_000) {
                    return null
                }
                val map = HashMap<String, String>(size * 4 / 3 + 1)
                for (i in 0 until size) {
                    val key = readString(input)
                    val value = readString(input)
                    map[key] = value
                }
                map
            }
        } catch (e: Throwable) {
            Log.d(TAG, "failed to read locale cache: ${e.message}")
            try {
                cacheFile.delete()
            } catch (_: Throwable) {
            }
            null
        }
    }

    @JvmStatic
    fun writeCache(file: File, map: HashMap<String, String>) {
        if (!file.exists() || map.isEmpty()) return
        val cacheFile = File(file.absolutePath + ".bin")
        val originalLastModified = file.lastModified()
        Utilities.globalQueue.postRunnable {
            val tempFile = File(cacheFile.absolutePath + ".tmp")
            try {
                DataOutputStream(BufferedOutputStream(FileOutputStream(tempFile), 32768)).use { output ->
                    output.writeInt(CACHE_VERSION)
                    output.writeInt(map.size)
                    for ((k, v) in map) {
                        writeString(output, k)
                        writeString(output, v)
                    }
                    output.flush()
                }
                if (tempFile.renameTo(cacheFile)) {
                    cacheFile.setLastModified(originalLastModified)
                }
            } catch (e: Throwable) {
                Log.d(TAG, "failed to write locale cache: ${e.message}")
                try {
                    tempFile.delete()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun writeString(out: DataOutputStream, str: String) {
        val bytes = str.toByteArray(StandardCharsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val len = input.readInt()
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}

