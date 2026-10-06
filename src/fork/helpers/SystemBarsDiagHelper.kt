package desu.inugram.helpers

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLog
import org.telegram.ui.LaunchActivity
import java.lang.ref.WeakReference

// entiny: temporary diagnostics for the status bar disappearing; writes to the app log only while logs are enabled
object SystemBarsDiagHelper {
    private var attached = WeakReference<View>(null)
    private var lastState = ""

    @JvmStatic
    fun init(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity !is LaunchActivity) return
                attach(activity)
                dump(activity, "resume")
            }

            override fun onActivityPaused(activity: Activity) {
                if (activity is LaunchActivity) dump(activity, "pause")
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun attach(activity: Activity) {
        val decor = activity.window.decorView
        if (attached.get() === decor) return
        attached = WeakReference(decor)
        decor.viewTreeObserver.addOnGlobalLayoutListener(ViewTreeObserver.OnGlobalLayoutListener { dump(activity, "layout") })
        decor.setOnSystemUiVisibilityChangeListener { dump(activity, "sysui") }
    }

    @Suppress("DEPRECATION")
    private fun dump(activity: Activity, source: String) {
        if (!BuildVars.LOGS_ENABLED) return
        val window = activity.window
        val decor = window.decorView
        val insets = ViewCompat.getRootWindowInsets(decor)
        val fragment = (activity as? LaunchActivity)?.let { it.actionBarLayout?.lastFragment?.javaClass?.simpleName }
        val state = "statusVisible=${insets?.isVisible(WindowInsetsCompat.Type.statusBars())} " +
            "statusTop=${insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top} " +
            "navVisible=${insets?.isVisible(WindowInsetsCompat.Type.navigationBars())} " +
            "sysUi=0x${decor.systemUiVisibility.toString(16)} " +
            "flags=0x${window.attributes.flags.toString(16)} " +
            "focus=${window.decorView.hasWindowFocus()} fragment=$fragment"
        if (state == lastState) return
        lastState = state
        FileLog.d("SystemBarsDiag[$source] $state")
    }
}
