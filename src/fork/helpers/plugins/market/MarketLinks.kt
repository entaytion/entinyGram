package desu.inugram.helpers.plugins.market

import android.content.Intent
import desu.inugram.ui.settings.MarketActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.SharedConfig
import org.telegram.ui.LaunchActivity

/**
 * `tg://entinyPlugin/<slug>` and `https://t.me/entinyPlugins/<slug>` open a plugin's page in the Market. Other clients open
 * the channel on the second one, so a post that links to it still works for everybody.
 */
object MarketLinks {
    @JvmStatic
    fun tryHandleDeepLink(activity: LaunchActivity, intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (AndroidUtilities.needShowPasscode(true) || SharedConfig.isWaitingForPasscodeEnter) return false
        val slug = when (uri.scheme) {
            "tg" -> {
                val host = uri.host ?: return false
                if (!host.equals("entinyPlugin", ignoreCase = true) && !host.equals("entinyPlugins", ignoreCase = true)) return false
                uri.pathSegments.firstOrNull()
            }

            "http", "https" -> {
                if (uri.host != "t.me" && uri.host != "telegram.me") return false
                val segments = uri.pathSegments
                if (segments.size < 2 || !segments[0].equals("entinyPlugins", ignoreCase = true)) return false
                segments[1]
            }

            else -> return false
        }
        if (slug.isNullOrEmpty()) return false
        activity.actionBarLayout.presentFragment(MarketActivity(slug))
        return true
    }
}
