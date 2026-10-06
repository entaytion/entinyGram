package desu.inugram.helpers.network

import android.util.Base64
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class WsClient private constructor(private val socket: SSLSocket) {
    private val input = DataInputStream(BufferedInputStream(socket.inputStream))
    private val output: OutputStream = socket.outputStream
    private val writeLock = Any()

    fun send(payload: ByteArray) = writeFrame(OP_BINARY, payload)

    fun receive(): ByteArray? {
        val message = ByteArrayOutputStream()
        while (true) {
            val b0 = input.readUnsignedByte()
            val b1 = input.readUnsignedByte()
            var len = (b1 and 0x7f).toLong()
            if (len == 126L) len = input.readUnsignedShort().toLong()
            else if (len == 127L) len = input.readLong()
            if (len < 0 || len > MAX_FRAME) throw IOException("ws frame too large: $len")
            val mask = if (b1 and 0x80 != 0) ByteArray(4).also { input.readFully(it) } else null
            val data = ByteArray(len.toInt())
            input.readFully(data)
            if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
            when (b0 and 0x0f) {
                OP_CLOSE -> return null
                OP_PING -> writeFrame(OP_PONG, data)
                OP_PONG -> {}
                else -> {
                    message.write(data)
                    if (b0 and 0x80 != 0) return message.toByteArray()
                }
            }
        }
    }

    fun close() {
        try {
            writeFrame(OP_CLOSE, ByteArray(0))
        } catch (_: Throwable) {
        }
        try {
            socket.close()
        } catch (_: Throwable) {
        }
    }

    private fun writeFrame(opcode: Int, payload: ByteArray) {
        val header = ByteArrayOutputStream(14)
        header.write(0x80 or opcode)
        val len = payload.size
        when {
            len < 126 -> header.write(0x80 or len)
            len <= 0xffff -> {
                header.write(0x80 or 126); header.write(len ushr 8); header.write(len and 0xff)
            }
            else -> {
                header.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) header.write(((len.toLong() ushr shift) and 0xff).toInt())
            }
        }
        val mask = ByteArray(4).also { random.nextBytes(it) }
        header.write(mask)
        val masked = ByteArray(len)
        for (i in 0 until len) masked[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        synchronized(writeLock) {
            output.write(header.toByteArray())
            output.write(masked)
            output.flush()
        }
    }

    companion object {
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xa
        private const val MAX_FRAME = 16L * 1024 * 1024
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
        private val random = SecureRandom()

        fun connect(host: String, path: String, timeoutMs: Int): WsClient {
            val raw = Socket(Proxy.NO_PROXY)
            raw.connect(InetSocketAddress(host, 443), timeoutMs)
            raw.tcpNoDelay = true
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
            try {
                ssl.soTimeout = timeoutMs
                ssl.startHandshake()
                val key = Base64.encodeToString(ByteArray(16).also { random.nextBytes(it) }, Base64.NO_WRAP)
                val request = "GET $path HTTP/1.1\r\n" +
                    "Host: $host\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "Sec-WebSocket-Protocol: binary\r\n" +
                    "Origin: https://web.telegram.org\r\n" +
                    "User-Agent: $USER_AGENT\r\n\r\n"
                ssl.outputStream.write(request.toByteArray(Charsets.ISO_8859_1))
                ssl.outputStream.flush()
                val client = WsClient(ssl)
                val status = client.readHeaders()
                if (!status.startsWith("HTTP/1.1 101")) throw IOException("ws upgrade refused: $status")
                ssl.soTimeout = 0
                return client
            } catch (t: Throwable) {
                try {
                    ssl.close()
                } catch (_: Throwable) {
                }
                throw t
            }
        }
    }

    private fun readHeaders(): String {
        val sb = StringBuilder()
        var status: String? = null
        while (true) {
            val c = input.read()
            if (c < 0) throw IOException("ws closed during handshake")
            if (c == '\n'.code) {
                val line = sb.toString().trimEnd('\r')
                sb.setLength(0)
                if (status == null) status = line else if (line.isEmpty()) return status
            } else {
                sb.append(c.toChar())
                if (sb.length > 8192) throw IOException("ws header too long")
            }
        }
    }
}
