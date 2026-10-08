package desu.inugram.helpers

import android.content.Context
import android.content.SharedPreferences
import org.telegram.messenger.ApplicationLoader

object InuPrefs {
    @JvmStatic
    fun of(name: String): SharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
}
