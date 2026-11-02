package desu.inugram.helpers

import desu.inugram.InuConfig

object SwipeSensitivity {
    @JvmStatic
    fun isEnabled(): Boolean = InuConfig.SHORTER_SWIPE_GESTURES.value

    @JvmStatic
    fun adjustDpCompletion(stockValue: Int): Int =
        if (isEnabled()) stockValue / 2 else stockValue

    @JvmStatic
    fun adjustFractionCompletion(stockValue: Float): Float =
        if (isEnabled()) stockValue * 0.5f else stockValue

    @JvmStatic
    fun adjustPixelCompletion(stockValue: Float): Float =
        if (isEnabled()) stockValue * 0.5f else stockValue
}
