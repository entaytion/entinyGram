package desu.inugram.core.fileid

import org.junit.Assert.assertEquals
import org.junit.Test

class RleTest {
    private fun encode(hex: String) = encodeHex(encodeTelegramRle(decodeHex(hex)))

    private fun decode(hex: String) = encodeHex(decodeTelegramRle(decodeHex(hex)))

    @Test
    fun encodeKeepsInputWithoutZeroes() {
        assertEquals("aaeeff", encode("aaeeff"))
    }

    @Test
    fun encodeCollapsesConsecutiveZeroes() {
        assertEquals("0004aa", encode("00000000aa"))
        assertEquals("0004aa0003aa", encode("00000000aa000000aa"))
        assertEquals("0004aa0002", encode("00000000aa0000"))
        assertEquals("0001aa0001", encode("00aa00"))
    }

    @Test
    fun decodeKeepsInputWithoutZeroes() {
        assertEquals("aaeeff", decode("aaeeff"))
    }

    @Test
    fun decodeExpandsZeroRuns() {
        assertEquals("00000000aa", decode("0004aa"))
        assertEquals("00000000aa", decode("0004aa0000"))
        assertEquals("00000000aa000000aa", decode("0004aa0003aa"))
        assertEquals("00000000aa0000", decode("0004aa0002"))
        assertEquals("00aa00", decode("0001aa0001"))
    }
}
