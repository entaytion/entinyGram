package desu.inugram.helpers.font

import android.content.Context
import android.widget.Toast
import desu.inugram.helpers.CrashReporter
import desu.inugram.helpers.font.FontConfig.FontMode
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities

object FontBootGuard {
    private const val TAG = "InuFonts"
    private const val PREFS = "inu_font_guard"
    private const val SURVIVE_MS = 8000L
    private const val MAX_FAILS = 2

    // entiny: a custom font that kills startup would also block the settings page that turns it off
    fun begin(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!isCustomFontActive()) {
            if (prefs.getInt("fails", 0) != 0) prefs.edit().putInt("fails", 0).apply()
            return
        }
        val fails = prefs.getInt("fails", 0)
        val stamp = prefs.getLong("stamp", 0L)
        val javaCrashed = fails > 0 && runCatching { CrashReporter.getLogFile().lastModified() >= stamp }.getOrDefault(false)
        if (fails >= MAX_FAILS || javaCrashed) {
            FileLog.e("$TAG: startup did not survive $fails time(s) with a custom font (java crash=$javaCrashed), resetting fonts")
            FontHelper.resetAppFont()
            FontHelper.resetMonoFont()
            prefs.edit().putInt("fails", 0).commit()
            AndroidUtilities.runOnUIThread({
                try {
                    Toast.makeText(
                        ApplicationLoader.applicationContext,
                        LocaleController.getString(R.string.InuFontResetAfterCrash),
                        Toast.LENGTH_LONG,
                    ).show()
                } catch (_: Throwable) {
                }
            }, 2500)
            return
        }
        prefs.edit().putInt("fails", fails + 1).putLong("stamp", System.currentTimeMillis()).commit()
        Utilities.globalQueue.postRunnable({ prefs.edit().putInt("fails", 0).apply() }, SURVIVE_MS)
    }

    private fun isCustomFontActive(): Boolean =
        FontConfig.FONT.value is FontMode.Custom || FontConfig.MONO_FONT.value.isNotEmpty()
}
