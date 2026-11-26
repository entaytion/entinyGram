package desu.inugram.helpers.chat

import desu.inugram.InuConfig

object AttachHideHelper {
    @JvmStatic
    fun isHidden(key: String): Boolean = key in InuConfig.ATTACH_HIDDEN.value
}
