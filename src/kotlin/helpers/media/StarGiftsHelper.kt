package desu.inugram.helpers.media

import desu.inugram.InuConfig
import org.telegram.tgnet.tl.TL_stars
import java.util.ArrayList

object StarGiftsHelper {

    @JvmStatic
    fun patchStarGifts(response: TL_stars.StarGifts?): TL_stars.StarGifts? {
        if (response == null || !InuConfig.HIDDEN_STAR_GIFTS.value) return response
        if (response !is TL_stars.TL_starGifts) return response
        try {
            val giftsList = response.gifts ?: return response
            if (giftsList.isEmpty()) return response

            for (i in 0 until giftsList.size) {
                val gift = giftsList[i]
                if (gift != null && gift.attributes == null) {
                    gift.attributes = ArrayList()
                }
            }
        } catch (e: Throwable) {
            android.util.Log.d("StarGiftsHelper", "Failed to patch star gifts", e)
        }
        return response
    }
}
