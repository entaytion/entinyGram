package desu.inugram.helpers.network

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Obfuscated2 {
    const val INIT_LEN = 64
    const val TAG_ABRIDGED = 0xefefefef.toInt()
    const val TAG_INTERMEDIATE = 0xeeeeeeee.toInt()
    const val TAG_PADDED = 0xdddddddd.toInt()

    private val random = SecureRandom()

    class Ctx(key: ByteArray, iv: ByteArray) {
        private val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }

        fun process(data: ByteArray, off: Int = 0, len: Int = data.size - off): ByteArray =
            cipher.update(data, off, len) ?: ByteArray(0)
    }

    class Session(val decrypt: Ctx, val encrypt: Ctx, val tag: Int, val dc: Int)

    fun acceptClient(init: ByteArray, secret: ByteArray): Session? {
        if (init.size != INIT_LEN) return null
        val decrypt = Ctx(sha256(init.copyOfRange(8, 40), secret), init.copyOfRange(40, 56))
        val rev = init.copyOfRange(8, 56).reversedArray()
        val encrypt = Ctx(sha256(rev.copyOfRange(0, 32), secret), rev.copyOfRange(32, 48))
        val plain = decrypt.process(init)
        val buf = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
        val tag = buf.getInt(56)
        if (tag != TAG_ABRIDGED && tag != TAG_INTERMEDIATE && tag != TAG_PADDED) return null
        return Session(decrypt, encrypt, tag, buf.getShort(60).toInt())
    }

    class Outgoing(val header: ByteArray, val encrypt: Ctx, val decrypt: Ctx)

    fun connectServer(tag: Int, dc: Int): Outgoing {
        val init = ByteArray(INIT_LEN)
        while (true) {
            random.nextBytes(init)
            if (isValidPrefix(init)) break
        }
        ByteBuffer.wrap(init).order(ByteOrder.LITTLE_ENDIAN).putInt(56, tag).putShort(60, dc.toShort())
        val encrypt = Ctx(init.copyOfRange(8, 40), init.copyOfRange(40, 56))
        val rev = init.copyOfRange(8, 56).reversedArray()
        val decrypt = Ctx(rev.copyOfRange(0, 32), rev.copyOfRange(32, 48))
        val encrypted = encrypt.process(init)
        System.arraycopy(encrypted, 56, init, 56, 8)
        return Outgoing(init, encrypt, decrypt)
    }

    private fun isValidPrefix(b: ByteArray): Boolean {
        if (b[0] == 0xef.toByte()) return false
        val first = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
        if (first == TAG_INTERMEDIATE || first == TAG_PADDED) return false
        val ascii = String(b, 0, 4, Charsets.ISO_8859_1)
        if (ascii == "HEAD" || ascii == "POST" || ascii == "GET " || ascii == "OPTI" || ascii == "\u0016\u0003\u0001\u0002") return false
        return b[4].toInt() or b[5].toInt() or b[6].toInt() or b[7].toInt() != 0
    }

    fun randomSecret(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

    private fun sha256(a: ByteArray, b: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run { update(a); update(b); digest() }
}
