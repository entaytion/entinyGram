package desu.inugram.helpers

import android.content.pm.ActivityInfo
import desu.inugram.InuConfig

object CallRotationHelper {
    @JvmStatic
    fun orientation(): Int =
        if (InuConfig.CALL_ROTATION.value) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}
