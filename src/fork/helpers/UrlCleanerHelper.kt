package desu.inugram.helpers

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.text.Spannable
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.style.SuggestionSpan
import android.text.style.URLSpan
import android.util.Log
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.core.net.toUri
import desu.inugram.InuConfig
import desu.inugram.core.urlcleaner.UrlCleaner
import desu.inugram.helpers.chat.WebPreviewHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.Utilities
import org.telegram.messenger.browser.Browser
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Components.URLSpanReplacement
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime

object UrlCleanerHelper {
    private const val FILE = "adguard_url_tracking.txt"
    private const val FILTER_URL = "https://filters.adtidy.org/android/filters/17.txt"
    private val TIME_UPDATED_RE = Regex("""^!\s*TimeUpdated:\s*(\S+)""", RegexOption.MULTILINE)
    private val EXTRA_RULES = listOf(
        "||soundcloud.com^\$removeparam=ref",
        "||soundcloud.com^\$removeparam=p",
        "||soundcloud.com^\$removeparam=c",
        "||soundcloud.com^\$removeparam=si",
        "||instagram.com^\$removeparam=/^[a-z]{4}=[A-Za-z0-9]+(=|%3D){0,2}\$/",
        "||pinterest.*^\$removeparam=/^(invite_code|sender|sfo)=/",
        "||yandex.*/maps/\$removeparam=si",
        "||maps.yango.com/\$removeparam=si",
        "||google.*/maps/\$removeparam=entry",
        "||google.*/maps/\$removeparam=skid",
    )
    private val REDIRECT_HOSTS = setOf("vm.tiktok.com", "vt.tiktok.com", "on.soundcloud.com", "share.google", "pin.it", "spotify.link", "maps.app.goo.gl")
    private val REDDIT_SHARE_PATH = Regex("^/r/[^/]+/s/[^/]+/?$")
    private val PINTEREST_HOST = Regex("^(www\\.)?pinterest\\.[a-z.]+$")
    private val YANDEX_HOST = Regex("^(www\\.)?yandex\\.[a-z.]+$")
    private val YANDEX_MAPS_SHARE_PATH = Regex("^/maps/-/[^/]+/?$")

    @Volatile
    private var cleaner: UrlCleaner? = null

    @Volatile
    private var loaded = false

    @Volatile
    var lastUpdated: String? = null
        private set

    val isUsingOverride: Boolean get() = overrideFile().exists()

    private fun overrideFile() = File(ApplicationLoader.applicationContext.filesDir, FILE)

    private fun readSource(): String {
        val bundled = ApplicationLoader.applicationContext.assets.open(FILE).bufferedReader().use { it.readText() }
        val file = overrideFile()
        val override = file.takeIf { it.exists() }?.readText() ?: return bundled
        val bundledTime = parseTimestamp(bundled) ?: return override
        val overrideTime = parseTimestamp(override)
        if (overrideTime != null && !bundledTime.isAfter(overrideTime)) return override
        file.delete()
        return bundled
    }

    private fun parseTimeUpdated(text: String): String? =
        TIME_UPDATED_RE.find(text)?.groupValues?.get(1)?.substringBefore('T')

    private fun parseTimestamp(text: String): OffsetDateTime? =
        TIME_UPDATED_RE.find(text)?.groupValues?.get(1)?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }

    @Synchronized
    private fun getCleaner(): UrlCleaner? {
        if (loaded) return cleaner
        cleaner = try {
            val text = readSource()
            lastUpdated = parseTimeUpdated(text)
            UrlCleaner.fromAdGuardFilter(text + "\n" + EXTRA_RULES.joinToString("\n"))
        } catch (e: Throwable) {
            Log.e("UrlCleanerHelper", "failed to load filter", e)
            null
        }
        loaded = true
        return cleaner
    }

    @Synchronized
    fun invalidate() {
        cleaner = null
        loaded = false
        lastUpdated = null
    }

    fun preload() {
        getCleaner()
    }

    @Throws(java.io.IOException::class)
    fun fetchLatest(): Boolean {
        getCleaner()
        val currentTime = lastUpdated

        val conn = (URL(FILTER_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        val downloaded = try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }

        if (parseTimeUpdated(downloaded)?.let { it == currentTime } == true) return false

        val target = overrideFile()
        val tmp = File(target.parentFile, "$FILE.tmp")
        tmp.writeText(downloaded)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("rename failed")
        }
        invalidate()
        return true
    }

    fun resetToBundled() {
        overrideFile().delete()
        invalidate()
    }

    @JvmStatic
    fun clean(uri: Uri): Uri {
        if (!InuConfig.STRIP_TRACKING_PARAMS_ON_OPEN.value) return uri
        val original = uri.toString()
        val cleaned = cleanUrl(original)
        return if (cleaned === original) uri else cleaned.toUri()
    }

    private fun cleanUrl(url: String): String {
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme != "http" && scheme != "https") return url
        return getCleaner()?.clean(url) ?: url
    }

    private fun isPasteTransformEnabled(): Boolean =
        InuConfig.STRIP_TRACKING_PARAMS_ON_PASTE.value ||
            InuConfig.WEB_PREVIEW_REPLACE_ON_PASTE.value && InuConfig.WEB_PREVIEW_REPLACEMENTS_ENABLED.value

    private fun transformPastedUrl(url: String): String {
        val cleaned = if (InuConfig.STRIP_TRACKING_PARAMS_ON_PASTE.value) cleanUrl(url) else url
        return if (InuConfig.WEB_PREVIEW_REPLACE_ON_PASTE.value) WebPreviewHelper.applyReplacements(cleaned) else cleaned
    }

    @JvmStatic
    fun cleanUrlString(url: CharSequence): CharSequence {
        if (!isPasteTransformEnabled()) return url
        val s = url.toString()
        val cleaned = transformPastedUrl(s)
        return if (cleaned === s) url else cleaned
    }

    @JvmStatic
    fun cleanText(text: CharSequence?): CharSequence? {
        if (text.isNullOrEmpty() || !isPasteTransformEnabled()) return text
        return replaceUrls(text, ::transformPastedUrl)
    }

    private fun replaceUrls(text: CharSequence, transform: (String) -> String): CharSequence {
        val matcher = AndroidUtilities.WEB_URL?.matcher(text) ?: return text
        var out: SpannableStringBuilder? = null
        var shift = 0
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            val original = text.subSequence(start, end).toString()
            val cleaned = transform(original)
            if (cleaned === original) continue
            if (out == null) out = SpannableStringBuilder(text)
            out.replace(start + shift, end + shift, cleaned)
            shift += cleaned.length - (end - start)
        }
        if (out != null && out.length != text.length) {
            BaseInputConnection.removeComposingSpans(out)
            for (span in out.getSpans(0, out.length, SuggestionSpan::class.java)) {
                out.removeSpan(span)
            }
        }
        return out ?: text
    }

    @JvmStatic
    fun handleContextMenuPaste(editText: EditText): Boolean {
        if (!isPasteTransformEnabled() && !InuConfig.RESOLVE_TRACKABLE_REDIRECTS.value) return false
        val ctx = editText.context ?: return false
        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val clip = cb.primaryClip?.takeIf { it.itemCount >= 1 } ?: return false
        try {
            val raw = clip.getItemAt(0).coerceToText(ctx) ?: return false
            val text = editText.text ?: return false
            val start = maxOf(0, editText.selectionStart)
            val end = minOf(text.length, editText.selectionEnd)
            if (insertResolvingRedirects(editText, raw, start, end)) return true
            val cleaned = cleanText(raw) ?: return false
            if (cleaned === raw) return false
            text.replace(start, end, cleaned)
            editText.setSelection(start + cleaned.length)
            return true
        } catch (e: Throwable) {
            FileLog.e(e)
            return false
        }
    }

    @JvmStatic
    fun wrapInputConnection(ic: InputConnection?, editText: EditText): InputConnection? {
        if (ic == null) return null
        return object : InputConnectionWrapper(ic, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val targets = if (text != null && text.toString().contains("https://")) findRedirectTargets(text) else emptyList()
                if (text != null && targets.isNotEmpty()) {
                    val committed = super.commitText("", newCursorPosition)
                    val position = maxOf(0, editText.selectionStart)
                    resolveAndInsert(editText, text, targets, position, position)
                    return committed
                }
                val cleaned = if (text != null && InuConfig.STRIP_TRACKING_PARAMS_ON_PASTE.value && text.toString().contains("https://")) cleanText(text) else text
                return super.commitText(cleaned, newCursorPosition)
            }
        }
    }

    @JvmStatic
    fun cleanSpannedUrls(text: Spannable) {
        if (!isPasteTransformEnabled()) return
        replaceSpanUrls(text, ::transformPastedUrl)
    }

    private fun replaceSpanUrls(text: Spannable, transform: (String) -> String) {
        for (span in text.getSpans(0, text.length, URLSpan::class.java)) {
            val url = span.url ?: continue
            val cleaned = transform(url)
            if (cleaned === url) continue
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            val flags = text.getSpanFlags(span)
            text.removeSpan(span)
            val replacement = if (span is URLSpanReplacement) URLSpanReplacement(cleaned, span.textStyleRun)
            else URLSpan(cleaned)
            text.setSpan(replacement, start, end, flags)
        }
    }

    private fun isTrackableRedirect(url: String): Boolean {
        val uri = url.toUri()
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase()
        val path = uri.path.orEmpty()
        return host in REDIRECT_HOSTS && path.length > 1
            || (host == "www.reddit.com" || host == "reddit.com") && REDDIT_SHARE_PATH.matches(path)
            || YANDEX_HOST.matches(host.orEmpty()) && YANDEX_MAPS_SHARE_PATH.matches(path)
    }

    private fun findRedirectTargets(text: CharSequence): List<String> {
        if (!InuConfig.RESOLVE_TRACKABLE_REDIRECTS.value) return emptyList()
        val urls = mutableListOf<String>()
        AndroidUtilities.WEB_URL?.matcher(text)?.let { matcher ->
            while (matcher.find()) urls.add(matcher.group())
        }
        if (text is Spanned) {
            text.getSpans(0, text.length, URLSpan::class.java).mapNotNullTo(urls) { it.url }
        }
        return urls.filter(::isTrackableRedirect).distinct()
    }

    /** Blocks. */
    private fun resolveTrackableRedirect(url: String): String? = when (val host = url.toUri().host?.lowercase().orEmpty()) {
        "vm.tiktok.com", "vt.tiktok.com" -> fetchRedirectLocation(url)
            ?.takeIf { it.host == "www.tiktok.com" && it.path.orEmpty().length > 1 }

        "on.soundcloud.com" -> fetchRedirectLocation(url)
            ?.takeIf { it.host == "soundcloud.com" }

        "share.google" -> fetchRedirectLocation(url)
            ?.takeIf { it.host == "www.google.com" && it.path == "/share.google" }
            ?.let { fetchRedirectLocation(it.toString()) }
            ?.takeIf { it.scheme == "https" || it.scheme == "http" }

        "www.reddit.com", "reddit.com" -> fetchRedirectLocation(url)
            ?.takeIf { it.host == "www.reddit.com" && it.path.orEmpty().contains("/comments/") }

        "pin.it" -> fetchRedirectLocation(url)
            ?.takeIf { it.host == "api.pinterest.com" && it.path.orEmpty().startsWith("/url_shortener/") }
            ?.let { fetchRedirectLocation(it.toString()) }
            ?.takeIf { PINTEREST_HOST.matches(it.host.orEmpty()) && it.path.orEmpty().startsWith("/pin/") }
            ?.let { it.buildUpon().path(it.path.orEmpty().removeSuffix("sent/")).build() }

        "spotify.link" -> fetchRedirectLocation(url)
            ?.takeIf { it.host == "spotify.app.link" }
            ?.let { fetchRedirectLocation(it.toString()) }
            ?.takeIf { it.host == "open.spotify.com" && it.path.orEmpty().length > 1 }

        "maps.app.goo.gl" -> fetchRedirectLocation(url)
            ?.takeIf { it.host == "www.google.com" && it.path.orEmpty().startsWith("/maps/") }

        else -> if (YANDEX_HOST.matches(host)) {
            fetchRedirectLocation(url)?.takeIf { it.host == host && it.path.orEmpty().startsWith("/maps/") }
        } else null
    }?.toString()

    /** Blocks. */
    private fun fetchRedirectLocation(url: String): Uri? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        try {
            if (conn.responseCode !in 300..399) return null
            val location = conn.getHeaderField("Location") ?: return null
            return URL(URL(url), location).toString().toUri()
        } finally {
            conn.disconnect()
        }
    }

    /** Returns true if insertion of [text] at [start, end) was taken over to resolve redirects first. */
    @JvmStatic
    fun insertResolvingRedirects(editText: EditText, text: CharSequence, start: Int, end: Int): Boolean {
        val targets = findRedirectTargets(text)
        if (targets.isEmpty()) return false
        resolveAndInsert(editText, text, targets, start, end)
        return true
    }

    private fun resolveAndInsert(editText: EditText, text: CharSequence, targets: List<String>, start: Int, end: Int) {
        val insert: (Map<String, String>) -> Unit = { resolved ->
            val transform = { url: String -> transformPastedUrl(resolved[url] ?: url) }
            val result = SpannableStringBuilder(replaceUrls(text, transform))
            replaceSpanUrls(result, transform)
            editText.text?.let { editable ->
                val s = start.coerceIn(0, editable.length)
                val e = end.coerceIn(s, editable.length)
                editable.replace(s, e, result)
                editText.setSelection(s + result.length)
            }
        }
        resolveWithLoader(editText.context, targets, null, onCancel = { insert(emptyMap()) }, onResolved = insert)
    }

    /** Returns true if opening [uri] was taken over to resolve a trackable redirect first. */
    @JvmStatic
    fun openResolvingRedirects(context: Context, uri: Uri, progress: Browser.Progress?, open: Utilities.Callback<Uri>): Boolean {
        if (!InuConfig.STRIP_TRACKING_PARAMS_ON_OPEN.value || !InuConfig.RESOLVE_TRACKABLE_REDIRECTS.value) return false
        val url = uri.toString()
        if (!isTrackableRedirect(url)) return false
        resolveWithLoader(context, listOf(url), progress, onCancel = {}) { resolved ->
            open.run(resolved[url]?.toUri() ?: uri)
        }
        return true
    }

    private fun resolveWithLoader(
        context: Context,
        targets: List<String>,
        progress: Browser.Progress?,
        onCancel: () -> Unit,
        onResolved: (Map<String, String>) -> Unit,
    ) {
        val dialog = if (progress == null && AndroidUtilities.findActivity(context) != null) {
            AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER)
        } else null
        var done = false
        val finish = { block: () -> Unit ->
            if (!done) {
                done = true
                dialog?.dismiss()
                progress?.end()
                block()
            }
        }
        dialog?.setOnCancelListener { finish(onCancel) }
        dialog?.showDelayed(300)
        progress?.onCancel { done = true }
        progress?.init()
        Utilities.externalNetworkQueue.postRunnable {
            val resolved = targets.mapNotNull { url ->
                val target = try {
                    resolveTrackableRedirect(url)
                } catch (e: Exception) {
                    Log.e("UrlCleanerHelper", "failed to resolve $url", e)
                    null
                }
                target?.let { url to it }
            }.toMap()
            AndroidUtilities.runOnUIThread { finish { onResolved(resolved) } }
        }
    }
}
