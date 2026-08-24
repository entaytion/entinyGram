package desu.inugram.helpers.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import desu.inugram.InuConfig
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BetaUpdate
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.BuildVars
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.tgnet.TLRPC
import java.io.File
import java.net.URL
import desu.inugram.helpers.security.ParanoiaHelper

object UpdateHelper {
    private const val GH_API_URL = "https://api.github.com/repos/entaytion/entinyGram/releases/latest"
    private const val CHECK_INTERVAL_MS = 4L * 60 * 60 * 1000
    private const val INFLIGHT_TIMEOUT_MS = 60L * 1000

    private val APK_RE = Regex("^entinygram-(arm64|universal)-(\\d+)(?:-(\\d+))?\\.apk$")
    private val TAG_RE = Regex("^v.+?(?:-(\\d+))?$")

    private val pInfo by lazy {
        ApplicationLoader.applicationContext.packageManager.getPackageInfo(
            ApplicationLoader.applicationContext.packageName, 0
        )
    }

    @JvmStatic
    val stockVersionName by lazy {
        pInfo.versionName?.replace(Regex("-[0-9a-f]{7}$"), "") ?: ""
    }

    fun getVersionInfoString(): String =
        LocaleController.formatString(R.string.InuVersion, stockVersionName, BuildConfig.STOCK_VERSION_CODE)

    @JvmStatic
    fun getFullVersionInfo(): String {
        if (ParanoiaHelper.isDisguised()) {
            // Build.CPU_ABI/CPU_ABI2 are deprecated (API 21+) in favor of SUPPORTED_ABIS,
            // which lists the same primary/secondary ABIs in preference order.
            val abis = Build.SUPPORTED_ABIS
            return "Telegram for Android v${stockVersionName} (${BuildConfig.STOCK_VERSION_CODE})\ndirect ${abis.getOrNull(0)} ${abis.getOrNull(1)}"
        }
        return "${getVersionInfoString()}\nBuilt on: ${BuildVars.BUILD_DATE}"
    }

    @Volatile private var inflight = false
    @Volatile private var inflightSince = 0L

    @Volatile var pendingBetaUpdate: BetaUpdate? = null
        private set

    @Volatile var activeDownloadId: Long = -1L
        private set

    @Volatile var pendingApkFile: File? = null
        private set

    @Volatile var pendingApkSize: Long = 0L
        private set

    @Volatile var isPendingStart: Boolean = false
        private set

    @JvmStatic
    fun revealPendingUpdate() {
        NotificationCenter.getGlobalInstance()
            .postNotificationName(NotificationCenter.appUpdateAvailable, true)
    }

    fun checkForCustomUpdate(force: Boolean, whenDone: Runnable?) {
        if (!InuConfig.UPDATES_ENABLED.value) { whenDone?.run(); return }
        if (!force && System.currentTimeMillis() - InuConfig.UPDATE_LAST_CHECK_MS.value < CHECK_INTERVAL_MS) {
            whenDone?.run(); return
        }
        check { whenDone?.run() }
    }

    fun clearPending() {
        pendingBetaUpdate = null
        isPendingStart = false
        val prevId = activeDownloadId
        activeDownloadId = -1L
        pendingApkFile = null
        pendingApkSize = 0L
        if (prevId != -1L) {
            (ApplicationLoader.applicationContext
                .getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager)?.remove(prevId)
        }
        SharedConfig.pendingAppUpdate = null
        SharedConfig.saveConfig()
        NotificationCenter.getGlobalInstance()
            .postNotificationName(NotificationCenter.appUpdateAvailable, false)
    }

    @JvmStatic
    fun clearPendingIfInstalled() {
        val pending = SharedConfig.pendingAppUpdate ?: return
        val pendingVer = pending.version?.toIntOrNull() ?: 0
        if (pendingVer <= currentVersionCode()) clearPending()
    }

    fun startDownload() {
        val update = SharedConfig.pendingAppUpdate ?: return
        val url = update.url?.takeIf { it.isNotBlank() } ?: return
        val fileName = url.substringAfterLast('/').takeIf { it.endsWith(".apk") }
            ?: "entinygram-update.apk"

        isPendingStart = true
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading)

        val dm = ApplicationLoader.applicationContext
            .getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (dm == null) { isPendingStart = false; return }

        val destFile = File(
            ApplicationLoader.applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )
        destFile.delete()

        val req = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("entinyGram update")
            setDescription(fileName)
            setDestinationUri(Uri.fromFile(destFile))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        }

        pendingApkFile = destFile
        activeDownloadId = dm.enqueue(req)
        isPendingStart = false
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading)
    }

    fun cancelDownload() {
        val id = activeDownloadId
        if (id != -1L) {
            (ApplicationLoader.applicationContext
                .getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager)?.remove(id)
            activeDownloadId = -1L
            pendingApkFile = null
        }
        isPendingStart = false
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading)
    }

    fun getDownloadProgress(): Float? {
        val id = activeDownloadId.takeIf { it != -1L } ?: return null
        val dm = ApplicationLoader.applicationContext
            .getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return null
        val cursor = dm.query(DownloadManager.Query().setFilterById(id)) ?: return null
        return try {
            if (!cursor.moveToFirst()) return null
            val dl = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            if (total <= 0) null else (dl.toFloat() / total).coerceIn(0f, 1f)
        } finally {
            cursor.close()
        }
    }

    fun getCompletedApkFile(): File? {
        val id = activeDownloadId.takeIf { it != -1L } ?: return null
        val dm = ApplicationLoader.applicationContext
            .getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return null
        val cursor = dm.query(DownloadManager.Query().setFilterById(id)) ?: return null
        return try {
            if (!cursor.moveToFirst()) return null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status == DownloadManager.STATUS_SUCCESSFUL) pendingApkFile else null
        } finally {
            cursor.close()
        }
    }

    fun isDownloading(): Boolean {
        val id = activeDownloadId.takeIf { it != -1L } ?: return false
        val dm = ApplicationLoader.applicationContext
            .getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false
        val cursor = dm.query(DownloadManager.Query().setFilterById(id)) ?: return false
        return try {
            if (!cursor.moveToFirst()) false
            else cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                .let { it == DownloadManager.STATUS_RUNNING || it == DownloadManager.STATUS_PENDING }
        } finally {
            cursor.close()
        }
    }

    fun check(callback: ((CheckResult) -> Unit)?) {
        if (BuildConfig.INU_BUILD_TYPE == "debug") { callback?.invoke(CheckResult.UpToDate); return }
        val now = System.currentTimeMillis()
        if (inflight && now - inflightSince < INFLIGHT_TIMEOUT_MS) { callback?.invoke(CheckResult.InFlight); return }
        inflight = true
        inflightSince = now
        org.telegram.messenger.Utilities.globalQueue.postRunnable {
            try {
                val json = fetchJson(GH_API_URL)
                AndroidUtilities.runOnUIThread { processRelease(json, callback) }
            } catch (e: Exception) {
                AndroidUtilities.runOnUIThread { finish(callback, CheckResult.Error(e.message ?: "network error")) }
            }
        }
    }

    @JvmStatic
    fun onNewMessage(msg: TLRPC.Message) {
        if (!InuConfig.UPDATES_ENABLED.value) return
        if (BuildConfig.INU_BUILD_TYPE == "debug") return
        if (msg.message?.contains("#release") != true) return
        if (System.currentTimeMillis() - InuConfig.UPDATE_LAST_CHECK_MS.value < 60_000L) return
        check { result -> if (result is CheckResult.Updated) revealPendingUpdate() }
    }

    private fun processRelease(json: JSONObject, callback: ((CheckResult) -> Unit)?) {
        val tagName = json.optString("tag_name", "")
        val remoteVerCode = tagToVerCode(tagName)
        val currentVerCode = currentVersionCode()

        if (remoteVerCode <= currentVerCode) {
            clearPending()
            finish(callback, CheckResult.UpToDate)
            return
        }

        val assets = json.optJSONArray("assets") ?: run {
            finish(callback, CheckResult.Error("no assets"))
            return
        }

        val isArm64 = isArm64Device()
        var arm64Asset: JSONObject? = null
        var universalAsset: JSONObject? = null

        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val m = APK_RE.matchEntire(asset.optString("name", "")) ?: continue
            when (m.groupValues[1]) {
                "arm64"     -> if (isArm64) arm64Asset = asset
                "universal" -> universalAsset = asset
            }
        }

        val chosen = (if (isArm64) arm64Asset ?: universalAsset else universalAsset ?: arm64Asset)
            ?: run { finish(callback, CheckResult.Error("no suitable APK asset")); return }

        val downloadUrl = chosen.optString("browser_download_url", "")
        if (downloadUrl.isEmpty()) { finish(callback, CheckResult.Error("empty URL")); return }

        val updateObj = TLRPC.TL_help_appUpdate().apply {
            flags = flags or 4   // FLAG_2 = url present; document stays null
            id = 0
            version = remoteVerCode.toString()
            text = json.optString("body", "")
            entities = ArrayList()
            url = downloadUrl
            document = null
        }
        pendingApkSize = chosen.optLong("size", 0L)

        SharedConfig.pendingAppUpdate = updateObj
        SharedConfig.pendingAppUpdateBuildVersion = currentVerCode
        SharedConfig.saveConfig()
        // version must stay a plain integer string — SharedConfig.versionBiggerOrEqual splits it
        // on "." and Integer.parseInt()s each part, and the raw GitHub tag (e.g. "v12.9.2-<hash>")
        // crashes on the "v12" / "<hash>" segments.
        pendingBetaUpdate = BetaUpdate(remoteVerCode.toString(), remoteVerCode, updateObj.text)

        finish(callback, CheckResult.Updated(updateObj))
    }

    /** v11.8.2-47 -> buildNum=47 -> verCode=4701 (mirrors scripts/ci/version.ts) */
    private fun tagToVerCode(tag: String): Int {
        val m = TAG_RE.matchEntire(tag) ?: return 0
        val buildNum = m.groupValues[1].toIntOrNull() ?: 0
        return buildNum * 100 + 1
    }

    private fun fetchJson(urlStr: String): JSONObject {
        val conn = (URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "entinyGram/${BuildConfig.BUILD_VERSION_STRING}")
            connectTimeout = 15_000
            readTimeout    = 15_000
        }
        return try {
            val code = conn.responseCode
            if (code != 200) throw Exception("GitHub API $code")
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun finish(callback: ((CheckResult) -> Unit)?, result: CheckResult) {
        inflight = false
        // Do not suppress future checks after a transient GitHub/network error.
        // The interval is a successful-check throttle, not a failure backoff.
        if (result is CheckResult.UpToDate || result is CheckResult.Updated) {
            InuConfig.UPDATE_LAST_CHECK_MS.value = System.currentTimeMillis()
        }
        callback?.invoke(result)
    }

    @Suppress("DEPRECATION")
    private fun currentVersionCode(): Int = pInfo.versionCode

    // Build.SUPPORTED_ABIS (API 21+) has fully replaced the deprecated Build.CPU_ABI fallback —
    // minSdk is 26, so it's always populated here.
    private fun isArm64Device(): Boolean =
        Build.SUPPORTED_ABIS.any {
            it.contains("arm64", ignoreCase = true) || it.contains("aarch64", ignoreCase = true)
        }

    sealed class CheckResult {
        object InFlight : CheckResult()
        object UpToDate : CheckResult()
        data class Updated(val update: TLRPC.TL_help_appUpdate) : CheckResult()
        data class Error(val message: String) : CheckResult()
    }
}
