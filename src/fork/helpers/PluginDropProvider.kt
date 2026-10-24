package desu.inugram.helpers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import desu.inugram.InuConfig
import java.io.File
import java.io.FileNotFoundException

// entiny: some devices deny adb shell access to Android/data, so the plugin cli writes the drop dir through here
class PluginDropProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val appId = Binder.getCallingUid() % PER_USER_RANGE
        if (appId != SHELL_UID && appId != ROOT_UID) throw SecurityException("only adb shell may write plugin-dev files")
        val context = context ?: throw FileNotFoundException("no context")
        // entiny: read prefs directly, a provider call can arrive before Application.onCreate loads InuConfig
        val devMode = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(InuConfig.PLUGINS_DEV_MODE.key, false)
        if (!devMode) throw SecurityException("turn on developer mode in Settings > Plugins")
        if (!mode.contains('w')) throw FileNotFoundException("plugin-dev files are write-only")
        val name = uri.lastPathSegment?.takeIf { it != "." && it != ".." && SAFE_NAME.matches(it) }
            ?: throw FileNotFoundException("bad file name: ${uri.lastPathSegment}")
        val dir = dropDir(context)?.apply { mkdirs() } ?: throw FileNotFoundException("no external files dir")
        return ParcelFileDescriptor.open(
            File(dir, name),
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        )
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val DIR = "plugin-dev"

        private const val PREFS_NAME = "inugram"
        private const val PER_USER_RANGE = 100000
        private const val ROOT_UID = 0
        private const val SHELL_UID = 2000
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")

        @JvmStatic
        fun dropDir(context: Context): File? = context.applicationContext.getExternalFilesDir(DIR)
    }
}
