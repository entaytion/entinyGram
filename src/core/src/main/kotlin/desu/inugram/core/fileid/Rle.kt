package desu.inugram.core.fileid

import java.io.ByteArrayOutputStream

internal fun encodeTelegramRle(buf: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(buf.size)
    var count = 0
    for (byte in buf) {
        if (byte.toInt() == 0) {
            count++
        } else {
            if (count > 0) {
                out.write(0)
                out.write(count)
                count = 0
            }
            out.write(byte.toInt())
        }
    }
    if (count > 0) {
        out.write(0)
        out.write(count)
    }
    return out.toByteArray()
}

internal fun decodeTelegramRle(buf: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(buf.size)
    var prev = -1
    for (byte in buf) {
        val cur = byte.toInt() and 0xff
        if (prev == 0) {
            repeat(cur) { out.write(0) }
            prev = -1
        } else {
            if (prev != -1) out.write(prev)
            prev = cur
        }
    }
    if (prev != -1) out.write(prev)
    return out.toByteArray()
}
