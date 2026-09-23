package desu.inugram.helpers.network

import android.os.SystemClock
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.Utilities
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

object DatacenterPing {
    private const val TIMEOUT_MS = 10000
    private const val REQ_PQ_MULTI = 0xbe7e8ef1.toInt()
    private const val RES_PQ = 0x05162463
    private val random = SecureRandom()

    sealed class Result {
        object Idle : Result()
        object Checking : Result()
        data class Available(val pingMs: Long) : Result()
        data class Slow(val pingMs: Long) : Result()
        object Unavailable : Result()
        object LeakGuardBlocked : Result()
    }

    class DcStatus(
        val dc: TelegramDc.Info,
        var result: Result = Result.Idle,
        var checkTime: Long = 0L,
    ) {
        val isChecking: Boolean get() = result is Result.Checking
    }

    private val statuses = TelegramDc.ALL.associate { it.id to DcStatus(it) }

    fun getStatus(dcId: Int): DcStatus =
        statuses[dcId] ?: DcStatus(TelegramDc.find(dcId) ?: TelegramDc.Info(dcId, "DC$dcId", "", ""))

    fun getAllStatuses(): List<DcStatus> = TelegramDc.ALL.map { getStatus(it.id) }

    fun check(dcId: Int, force: Boolean = false, onUpdated: () -> Unit) {
        val status = getStatus(dcId)
        if (status.isChecking) return
        val now = SystemClock.elapsedRealtime()
        if (!force && status.result !is Result.Idle && now - status.checkTime < 2 * 60 * 1000) {
            return
        }
        if (InuConfig.LEAK_GUARD.value) {
            status.result = Result.LeakGuardBlocked
            status.checkTime = now
            onUpdated()
            return
        }
        status.result = Result.Checking
        onUpdated()

        Utilities.globalQueue.postRunnable {
            val res = pingInternal(dcId)
            AndroidUtilities.runOnUIThread {
                status.result = res
                status.checkTime = SystemClock.elapsedRealtime()
                onUpdated()
            }
        }
    }

    fun checkAll(force: Boolean = false, onUpdated: () -> Unit) {
        for (dc in TelegramDc.ALL) {
            check(dc.id, force, onUpdated)
        }
    }

    private fun pingInternal(dc: Int): Result {
        val ip = TelegramDc.ip(dc) ?: return Result.Unavailable
        val nonce = ByteArray(16).also { random.nextBytes(it) }
        val body = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0)
            .putLong((System.currentTimeMillis() / 1000) shl 32)
            .putInt(20)
            .putInt(REQ_PQ_MULTI)
            .put(nonce)
            .array()
        val framed = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).array() + body
        val session = Obfuscated2.connectServer(Obfuscated2.TAG_INTERMEDIATE, dc)
        return try {
            Socket(Proxy.NO_PROXY).use { socket ->
                val start = SystemClock.elapsedRealtime()
                socket.connect(InetSocketAddress(ip, 443), TIMEOUT_MS)
                socket.soTimeout = TIMEOUT_MS
                socket.tcpNoDelay = true
                socket.getOutputStream().apply {
                    write(session.header)
                    write(session.encrypt.process(framed))
                    flush()
                }
                val input = DataInputStream(socket.getInputStream())
                val len = ByteBuffer.wrap(session.decrypt.process(ByteArray(4).also { input.readFully(it) }))
                    .order(ByteOrder.LITTLE_ENDIAN).int and 0x7fffffff
                if (len < 40 || len > 4096) return Result.Unavailable
                val payload = session.decrypt.process(ByteArray(len).also { input.readFully(it) })
                val elapsed = SystemClock.elapsedRealtime() - start
                val ctor = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt(20)
                if (ctor == RES_PQ && payload.copyOfRange(24, 40).contentEquals(nonce)) {
                    if (elapsed >= 1000) Result.Slow(elapsed) else Result.Available(elapsed)
                } else {
                    Result.Unavailable
                }
            }
        } catch (_: Throwable) {
            Result.Unavailable
        }
    }
}
