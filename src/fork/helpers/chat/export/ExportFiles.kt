package desu.inugram.helpers.chat.export

import android.os.Environment
import desu.inugram.InuConfig
import java.io.File

internal object ExportFiles {
    internal fun exportRoot(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "${InuConfig.DOWNLOAD_DIRECTORY.value}/Chat Export")

    internal fun safeTitle(title: String): String =
        title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(60).ifEmpty { "chat" }

    // name -> name(2) -> name(3)... keeping the extension, works for folders too
    internal fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base($n)$ext")
            n++
        }
        return candidate
    }
}
