package desu.inugram.helpers.media

import desu.inugram.InuConfig

object ShortPositionHelper {
    private const val SHORT_SECONDS = 10

    @JvmStatic
    fun minSeconds(stock: Int): Int = if (InuConfig.REMEMBER_SHORT_POSITION.value) minOf(stock, SHORT_SECONDS) else stock
}
