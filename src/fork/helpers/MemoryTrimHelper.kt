package desu.inugram.helpers

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.Utilities

object MemoryTrimHelper {
    private const val BACKGROUND_DELAY_MS = 45_000L
    private const val MIN_INTERVAL_MS = 10_000L

    private var started = 0
    private var lastPurge = 0L
    private val delayedPurge = Runnable { purge() }

    @JvmStatic
    fun init(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                AndroidUtilities.cancelRunOnUIThread(delayedPurge)
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) AndroidUtilities.runOnUIThread(delayedPurge, BACKGROUND_DELAY_MS)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                    level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                    level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                ) purge()
            }

            override fun onConfigurationChanged(newConfig: Configuration) {}
            override fun onLowMemory() {}
        })
    }

    private fun purge() {
        if (!InuConfig.MEMORY_TRIM.value) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPurge < MIN_INTERVAL_MS) return
        lastPurge = now
        ImageLoader.getInstance().clearMemory()
        Utilities.globalQueue.postRunnable { Runtime.getRuntime().gc() }
    }
}
