package desu.inugram.helpers.network

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class WsTunnel(private val secret: ByteArray) {
    @Volatile private var server: ServerSocket? = null
    private val threadId = AtomicInteger()
    private val pool = Executors.newCachedThreadPool(ThreadFactory { r ->
        Thread(r, "inu-ws-tunnel-${threadId.incrementAndGet()}").apply { isDaemon = true }
    })

    val port: Int get() = server?.localPort ?: 0

    fun start(preferredPort: Int): Int {
        server?.let { return it.localPort }
        val srv = ServerSocket()
        srv.reuseAddress = true
        try {
            srv.bind(InetSocketAddress(InetAddress.getByName(HOST), preferredPort))
        } catch (_: Throwable) {
            srv.bind(InetSocketAddress(InetAddress.getByName(HOST), 0))
        }
        server = srv
        pool.execute { acceptLoop(srv) }
        return srv.localPort
    }

    fun stop() {
        try {
            server?.close()
        } catch (_: Throwable) {
        }
        server = null
    }

    private fun acceptLoop(srv: ServerSocket) {
        while (!srv.isClosed) {
            val client = try {
                srv.accept()
            } catch (_: Throwable) {
                break
            }
            pool.execute { handle(client) }
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
            closeQuietly(client)
            return
        }
        val dc = abs(session.dc).let { DC_ALIASES[it] ?: it }
        val media = session.dc < 0
        val upstream = Obfuscated2.connectServer(session.tag, session.dc)
        val ws = openWs(dc, media)
        if (ws != null) {
            bridgeWs(client, input, session, upstream, ws)
        } else {
            bridgeTcp(client, input, session, upstream, dc)
        }
    }

    private fun openWs(dc: Int, media: Boolean): WsClient? {
        if (dc !in 1..5) return null
        val hosts = if (media) listOf("kws$dc-1.$WS_DOMAIN", "kws$dc.$WS_DOMAIN") else listOf("kws$dc.$WS_DOMAIN", "kws$dc-1.$WS_DOMAIN")
        for (host in hosts) {
            try {
                return WsClient.connect(host, "/apiws", CONNECT_TIMEOUT_MS)
            } catch (t: Throwable) {
                Log.d(TAG, "ws $host failed: ${t.message}")
            }
        }
        return null
    }

    private fun bridgeWs(client: Socket, input: InputStream, session: Obfuscated2.Session, upstream: Obfuscated2.Outgoing, ws: WsClient) {
        val out = client.getOutputStream()
        pool.execute {
            try {
                while (true) {
                    val frame = ws.receive() ?: break
                    out.write(session.encrypt.process(upstream.decrypt.process(frame)))
                    out.flush()
                }
            } catch (_: Throwable) {
            } finally {
                ws.close()
                closeQuietly(client)
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
            ws.close()
            closeQuietly(client)
        }
    }

    private fun bridgeTcp(client: Socket, input: InputStream, session: Obfuscated2.Session, upstream: Obfuscated2.Outgoing, dc: Int) {
        val ip = TelegramDc.ip(dc)
        if (ip == null) {
            closeQuietly(client)
            return
        }
        val remote = Socket(Proxy.NO_PROXY)
        try {
            remote.connect(InetSocketAddress(ip, 443), CONNECT_TIMEOUT_MS)
            remote.tcpNoDelay = true
        } catch (t: Throwable) {
            Log.d(TAG, "tcp dc$dc failed: ${t.message}")
            closeQuietly(remote)
            closeQuietly(client)
            return
        }
        val remoteOut = remote.getOutputStream()
        remoteOut.write(upstream.header)
        remoteOut.flush()
        pool.execute { pump(remote.getInputStream(), client.getOutputStream(), upstream.decrypt, session.encrypt, client, remote) }
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
            closeQuietly(a)
            closeQuietly(b)
        }
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
        private const val TAG = "InuWsTunnel"
        private const val WS_DOMAIN = "web.telegram.org"
        private const val CONNECT_TIMEOUT_MS = 8000
        private const val HANDSHAKE_TIMEOUT_MS = 10000
        private const val BUFFER_SIZE = 64 * 1024
        private val DC_ALIASES = mapOf(203 to 2)

        private fun closeQuietly(s: Socket) {
            try {
                s.close()
            } catch (_: Throwable) {
            }
        }
    }
}
