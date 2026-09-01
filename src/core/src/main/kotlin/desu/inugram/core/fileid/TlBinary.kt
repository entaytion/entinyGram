package desu.inugram.core.fileid

import java.io.ByteArrayOutputStream

internal class TlWriter {
    private val out = ByteArrayOutputStream(64)

    fun writeByte(value: Int) {
        out.write(value and 0xff)
    }

    fun writeInt(value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 24) and 0xff)
    }

    fun writeLong(value: Long) {
        writeInt(value.toInt())
        writeInt((value ushr 32).toInt())
    }

    fun writeBytes(value: ByteArray) {
        val padding: Int
        if (value.size <= 253) {
            out.write(value.size)
            padding = (value.size + 1) % 4
        } else {
            out.write(254)
            out.write(value.size and 0xff)
            out.write((value.size ushr 8) and 0xff)
            out.write((value.size ushr 16) and 0xff)
            padding = value.size % 4
        }
        out.write(value)
        if (padding > 0) repeat(4 - padding) { out.write(0) }
    }

    fun writeString(value: String) = writeBytes(value.toByteArray(Charsets.UTF_8))

    fun toByteArray(): ByteArray = out.toByteArray()
}

internal class TlReader(private val buf: ByteArray) {
    var pos = 0

    val remaining: Int get() = buf.size - pos

    fun peekByte(): Int {
        ensureAvailable(1)
        return buf[pos].toInt() and 0xff
    }

    fun readByte(): Int {
        ensureAvailable(1)
        return buf[pos++].toInt() and 0xff
    }

    fun readInt(): Int {
        ensureAvailable(4)
        val value = (buf[pos].toInt() and 0xff) or
            ((buf[pos + 1].toInt() and 0xff) shl 8) or
            ((buf[pos + 2].toInt() and 0xff) shl 16) or
            ((buf[pos + 3].toInt() and 0xff) shl 24)
        pos += 4
        return value
    }

    fun readLong(): Long {
        val lo = readInt().toLong() and 0xffffffffL
        val hi = readInt().toLong()
        return (hi shl 32) or lo
    }

    fun readRaw(count: Int): ByteArray {
        ensureAvailable(count)
        return buf.copyOfRange(pos, pos + count).also { pos += count }
    }

    fun readBytes(): ByteArray {
        val first = readByte()
        val length: Int
        val padding: Int
        if (first == 254) {
            length = readByte() or (readByte() shl 8) or (readByte() shl 16)
            padding = length % 4
        } else {
            length = first
            padding = (length + 1) % 4
        }
        val data = readRaw(length)
        if (padding > 0) pos = minOf(pos + 4 - padding, buf.size)
        return data
    }

    fun readString(): String = String(readBytes(), Charsets.UTF_8)

    private fun ensureAvailable(count: Int) {
        if (remaining < count) {
            throw InvalidFileIdException("Unexpected end of file ID (need $count bytes, have $remaining)")
        }
    }
}
