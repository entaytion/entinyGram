package desu.inugram.helpers.theme

import desu.inugram.InuConfig

// entiny: flat 11.14.1 list look, ported from the retro-views plugin (@sophietelle)
object RetroListsHelper {

    @Volatile
    private var insideRetroList = false

    @JvmStatic
    fun isFlat(): Boolean = InuConfig.RETRO_LISTS.value

    @JvmStatic
    fun isInList(): Boolean = insideRetroList

    @JvmStatic
    fun isEnabled(): Boolean = isFlat() || insideRetroList

    @JvmStatic
    fun enter() {
        insideRetroList = true
    }

    @JvmStatic
    fun exit() {
        insideRetroList = false
    }
}
