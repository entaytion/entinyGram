package desu.inugram.helpers.proxy

import android.os.SystemClock
import org.telegram.messenger.FileLog
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class WsTunnel(private val secret: ByteArray, private val onDied: (WsTunnel) -> Unit) {
    @Volatile private var server: ServerSocket? = null
    @Volatile var isRunning = false
        private set
    private val threadId = AtomicInteger()
    private val pool = Executors.newCachedThreadPool(ThreadFactory { r ->
        Thread(r, "inu-ws-tunnel-${threadId.incrementAndGet()}").apply { isDaemon = true }
    })
    private val open = ConcurrentHashMap.newKeySet<Closeable>()
    private val routes = ConcurrentHashMap<Int, Route>()

    private class Route {
        @Volatile var preferred = 0
        @Volatile var failures = 0
        @Volatile var wsBlockedUntil = 0L
    }

    val port: Int get() = if (isRunning) server?.localPort ?: 0 else 0

    fun start(preferredPort: Int): Int {
        server?.let { return it.localPort }
        val srv = ServerSocket()
        srv.reuseAddress = true
        try {
            srv.bind(InetSocketAddress(InetAddress.getByName(HOST), preferredPort))
        } catch (_: Throwable) {
            try {
                srv.bind(InetSocketAddress(InetAddress.getByName(HOST), 0))
            } catch (t: Throwable) {
                closeQuietly(srv)
                throw t
            }
        }
        server = srv
        isRunning = true
        pool.execute { acceptLoop(srv) }
        pool.execute { watchdog() }
        return srv.localPort
    }

    fun stop() {
        isRunning = false
        closeQuietly(server)
        server = null
        for (c in open) closeQuietly(c)
        open.clear()
        pool.shutdownNow()
    }

    private fun acceptLoop(srv: ServerSocket) {
        while (isRunning && !srv.isClosed) {
            val client = try {
                srv.accept()
            } catch (_: Throwable) {
                break
            }
            track(client)
            try {
                pool.execute {
                    try {
                        handle(client)
                    } catch (_: Throwable) {
                        release(client)
                    }
                }
            } catch (_: Throwable) {
                release(client)
            }
        }
        if (isRunning) {
            isRunning = false
            onDied(this)
        }
    }

    private fun watchdog() {
        try {
            while (isRunning) {
                Thread.sleep(WATCHDOG_INTERVAL_MS)
                val now = SystemClock.elapsedRealtime()
                for (c in open) {
                    if (c is WsClient && c.writingSince != 0L && now - c.writingSince > WRITE_STALL_MS) release(c)
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun handle(client: Socket) {
        client.tcpNoDelay = true
        val input = DataInputStream(client.getInputStream())
        val init = ByteArray(Obfuscated2.INIT_LEN)
        val session = try {
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            input.readFully(init)
            client.soTimeout = 0
            Obfuscated2.acceptClient(init, secret)
        } catch (_: Throwable) {
            null
        }
        if (session == null) {
            release(client)
            return
        }
        val dc = abs(session.dc).let { DC_ALIASES[it] ?: it }
        val media = session.dc < 0
        val upstream = Obfuscated2.connectServer(session.tag, session.dc)
        val ws = openWs(dc, media)
        if (ws != null) {
            track(ws)
            bridgeWs(client, input, session, upstream, ws)
        } else {
            bridgeTcp(client, input, session, upstream, dc, if (media) -dc else dc)
        }
    }

    private fun openWs(dc: Int, media: Boolean): WsClient? {
        if (dc !in 1..5) return null
        val route = routes.getOrPut(if (media) -dc else dc) { Route() }
        if (SystemClock.elapsedRealtime() < route.wsBlockedUntil) return null
        val hosts = if (media) arrayOf("kws$dc-1.$WS_DOMAIN", "kws$dc.$WS_DOMAIN") else arrayOf("kws$dc.$WS_DOMAIN", "kws$dc-1.$WS_DOMAIN")
        val index = route.preferred
        val host = hosts[index]
        return try {
            WsClient.connect(host, "/apiws", CONNECT_TIMEOUT_MS, WS_HANDSHAKE_TIMEOUT_MS).also { route.failures = 0 }
        } catch (t: Throwable) {
            FileLog.d("inu ws tunnel: $host failed: ${t.message}")
            route.preferred = 1 - index
            route.failures++
            if (route.failures >= hosts.size) {
                route.failures = 0
                route.wsBlockedUntil = SystemClock.elapsedRealtime() + WS_COOLDOWN_MS
            }
            null
        }
    }

    private fun bridgeWs(client: Socket, input: InputStream, session: Obfuscated2.Session, upstream: Obfuscated2.Outgoing, ws: WsClient) {
        val out = try {
            client.getOutputStream()
        } catch (t: Throwable) {
            release(ws)
            throw t
        }
        pool.execute {
            try {
                while (true) {
                    val frame = ws.receive() ?: break
                    out.write(session.encrypt.process(upstream.decrypt.process(frame)))
                    out.flush()
                }
            } catch (_: Throwable) {
            } finally {
                release(ws)
                release(client)
            }
        }
        try {
            ws.send(upstream.header)
            val splitter = PacketSplitter(session.tag)
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                for (packet in splitter.feed(session.decrypt.process(buf, 0, n))) {
                    ws.send(upstream.encrypt.process(packet))
                }
            }
        } catch (_: Throwable) {
        } finally {
            release(ws)
            release(client)
        }
    }

    private fun bridgeTcp(client: Socket, input: InputStream, session: Obfuscated2.Session, upstream: Obfuscated2.Outgoing, dc: Int, routeKey: Int) {
        val ip = DC_IPS[dc]
        if (ip == null) {
            release(client)
            return
        }
        val remote = Socket(Proxy.NO_PROXY)
        track(remote)
        val remoteOut: OutputStream
        val remoteIn: InputStream
        val clientOut: OutputStream
        try {
            remote.connect(InetSocketAddress(ip, 443), CONNECT_TIMEOUT_MS)
            remote.tcpNoDelay = true
            remoteOut = remote.getOutputStream()
            remoteIn = remote.getInputStream()
            clientOut = client.getOutputStream()
            remoteOut.write(upstream.header)
            remoteOut.flush()
        } catch (t: Throwable) {
            FileLog.d("inu ws tunnel: tcp dc$dc failed: ${t.message}")
            routes[routeKey]?.wsBlockedUntil = 0L
            release(remote)
            release(client)
            return
        }
        pool.execute { pump(remoteIn, clientOut, upstream.decrypt, session.encrypt, client, remote) }
        pump(input, remoteOut, session.decrypt, upstream.encrypt, client, remote)
    }

    private fun pump(from: InputStream, to: OutputStream, dec: Obfuscated2.Ctx, enc: Obfuscated2.Ctx, a: Socket, b: Socket) {
        try {
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(enc.process(dec.process(buf, 0, n)))
                to.flush()
            }
        } catch (_: Throwable) {
        } finally {
            release(a)
            release(b)
        }
    }

    private fun track(c: Closeable) {
        open.add(c)
        if (!isRunning) release(c)
    }

    private fun release(c: Closeable) {
        open.remove(c)
        closeQuietly(c)
    }

    private class PacketSplitter(private val tag: Int) {
        private val pending = ByteArrayOutputStream()

        fun feed(data: ByteArray): List<ByteArray> {
            pending.write(data)
            val bytes = pending.toByteArray()
            val packets = ArrayList<ByteArray>()
            var pos = 0
            while (true) {
                val size = packetSize(bytes, pos) ?: break
                if (bytes.size - pos < size) break
                packets.add(bytes.copyOfRange(pos, pos + size))
                pos += size
            }
            pending.reset()
            pending.write(bytes, pos, bytes.size - pos)
            return packets
        }

        private fun packetSize(b: ByteArray, pos: Int): Int? {
            val left = b.size - pos
            if (tag == Obfuscated2.TAG_ABRIDGED) {
                if (left < 1) return null
                val first = b[pos].toInt() and 0x7f
                if (first < 0x7f) return 1 + first * 4
                if (left < 4) return null
                val len = (b[pos + 1].toInt() and 0xff) or ((b[pos + 2].toInt() and 0xff) shl 8) or ((b[pos + 3].toInt() and 0xff) shl 16)
                return 4 + len * 4
            }
            if (left < 4) return null
            val len = ((b[pos].toInt() and 0xff) or ((b[pos + 1].toInt() and 0xff) shl 8) or
                ((b[pos + 2].toInt() and 0xff) shl 16) or ((b[pos + 3].toInt() and 0x7f) shl 24))
            return 4 + len
        }
    }

    companion object {
        const val HOST = "127.0.0.1"
        private const val WS_DOMAIN = "web.telegram.org"
        // entiny: tgnet drops a generic connection after 8-12 s without data, so one ws try plus the tcp fallback must fit in that
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val WS_HANDSHAKE_TIMEOUT_MS = 3500
        private const val HANDSHAKE_TIMEOUT_MS = 10000
        private const val WS_COOLDOWN_MS = 60_000L
        private const val WATCHDOG_INTERVAL_MS = 10_000L
        private const val WRITE_STALL_MS = 30_000L
        private const val BUFFER_SIZE = 64 * 1024
        private val DC_ALIASES = mapOf(203 to 2)
        private val DC_IPS = mapOf(
            1 to "149.154.175.53",
            2 to "149.154.167.51",
            3 to "149.154.175.100",
            4 to "149.154.167.91",
            5 to "91.108.56.130",
        )

        private fun closeQuietly(c: Closeable?) {
            try {
                c?.close()
            } catch (_: Throwable) {
            }
        }
    }
}
