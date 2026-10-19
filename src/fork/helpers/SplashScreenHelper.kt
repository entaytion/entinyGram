package desu.inugram.helpers

import android.app.Activity
import org.telegram.ui.LaunchActivity

object SplashScreenHelper {
    // entiny: upstream icon set differs from ours, so splash themes come from SplashThemeHelper
    @JvmStatic
    fun sync(activity: Activity?) {
        SplashThemeHelper.apply(activity as? LaunchActivity)
    }
}
