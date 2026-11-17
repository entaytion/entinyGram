package desu.inugram.helpers.pillstack

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.Utilities

internal class CachedFetch<T : Any>(
    private val ttlMs: Long,
    private val storedRaw: () -> String,
    private val storedTime: () -> Long,
    private val decode: (String) -> T?,
    private val persist: (T) -> Unit,
    private val fetchBlocking: () -> T?,
) {
    private val sync = Any()
    private val pendingCallbacks = ArrayList<Utilities.Callback<T?>>()
    private var requestInFlight = false
    private var cacheValue: T? = null
    private var cacheTimestamp = 0L

    fun clearCache() {
        cacheTimestamp = 0
    }

    private fun isStale(): Boolean =
        getCached() == null || cacheTimestamp == 0L || System.currentTimeMillis() - cacheTimestamp >= ttlMs

    fun getCached(): T? {
        if (cacheValue == null) {
            val raw = storedRaw()
            if (raw.isNotEmpty()) {
                cacheValue = decode(raw)
                cacheTimestamp = storedTime()
            }
        }
        return cacheValue
    }

    fun fetch(callback: Utilities.Callback<T?>) {
        val cached = getCached()
        if (cached != null && !isStale()) {
            AndroidUtilities.runOnUIThread { callback.run(cached) }
            return
        }
        var startRequest: Boolean
        synchronized(sync) {
            pendingCallbacks.add(callback)
            startRequest = !requestInFlight
            requestInFlight = true
        }
        if (!startRequest) return
        Utilities.globalQueue.postRunnable {
            val value = try {
                fetchBlocking()
            } catch (e: Exception) {
                FileLog.e(e)
                null
            }
            if (value != null) {
                cacheValue = value
                cacheTimestamp = System.currentTimeMillis()
                persist(value)
            }
            complete(value ?: getCached())
        }
    }

    private fun complete(value: T?) {
        val callbacks: List<Utilities.Callback<T?>>
        synchronized(sync) {
            requestInFlight = false
            callbacks = ArrayList(pendingCallbacks)
            pendingCallbacks.clear()
        }
        AndroidUtilities.runOnUIThread {
            for (callback in callbacks) callback.run(value)
        }
    }
}
