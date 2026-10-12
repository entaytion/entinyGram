package desu.inugram.helpers.update

import android.os.Build
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.security.ParanoiaHelper
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BetaUpdate
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min

object UpdateHelper {
    const val USERNAME = "entinyGramCI"
    private const val CHANGELOG_JSON_URL = "https://raw.githubusercontent.com/entaytion/entinyGram/main/assets/changelog.json"
    private const val CHECK_INTERVAL_MS = 4L * 60 * 60 * 1000
    private const val INFLIGHT_TIMEOUT_MS = 60L * 1000
    private const val RESOLVE_BACKOFF_MS = 30L * 60 * 1000

    // pluginless is the default APK name; plugins builds are "-plugins-" so each variant only sees its own
    private val APK_RE = if (BuildConfig.INU_PLUGINLESS) {
        Regex("^entinygram(?:-beta)?-arm64-(.+)-(\\d+)\\.apk$")
    } else {
        Regex("^entinygram(?:-beta)?-plugins-arm64-(.+)-(\\d+)\\.apk$")
    }

    @Volatile
    private var resolvedChannelId: Long? = null

    val packageInfo by lazy {
        ApplicationLoader.applicationContext.packageManager.getPackageInfo(
            ApplicationLoader.applicationContext.packageName, 0
        )
    }

    @JvmStatic
    val stockVersionName by lazy {
        packageInfo.versionName?.replace(Regex("-[0-9a-f]{7}$"), "") ?: ""
    }

    @JvmStatic
    val commitSha: String? by lazy {
        Regex("-([0-9a-f]{7})$").find(packageInfo.versionName ?: "")?.groupValues?.get(1)
    }

    fun getVersionInfoString(): String {
        // entiny: STOCK_VERSION_CODE is upstream Telegram's build number, not ours -- releases are tagged by packageInfo.versionCode
        val base = LocaleController.formatString(R.string.InuVersion, stockVersionName, packageInfo.versionCode)
        val withBeta = if (BuildVars.isBetaApp()) "$base ${LocaleController.getString(R.string.InuVersionBetaSuffix)}" else base
        val commitSuffix = commitSha?.let { " @$it" } ?: ""
        return "$withBeta$commitSuffix [${BuildConfig.INU_BUILD_TYPE}]"
    }

    @JvmStatic
    fun getFullVersionInfo(): String {
        if (ParanoiaHelper.isDisguised()) {
            val abis = Build.SUPPORTED_ABIS
            return "Telegram for Android v${stockVersionName} (${BuildConfig.STOCK_VERSION_CODE})\ndirect ${abis.getOrNull(0)} ${abis.getOrNull(1)}"
        }
        return "${getVersionInfoString()}\nBuilt on: ${BuildVars.BUILD_DATE}"
    }

    @Volatile
    private var inflight = false
    @Volatile
    private var inflightSince = 0L
    @Volatile
    private var lastResolveFailureMs = 0L
    private val queuedCallbacks = ArrayList<(CheckResult) -> Unit>()

    @Volatile var pendingBetaUpdate: BetaUpdate? = null
        private set

    @Volatile var pendingIsBeta: Boolean = false
        private set

    @Volatile
    private var pendingSourceMessage: TLRPC.Message? = null

    @Volatile var isPendingStart: Boolean = false
        private set

    @Volatile private var lastProgress: Float = 0f

    @JvmStatic
    fun revealPendingUpdate() {
        // entiny: a stale pending entry for the installed version must never pop up
        val pendingVer = SharedConfig.pendingAppUpdate?.version?.toIntOrNull()
        if (pendingVer != null && pendingVer <= currentVersionCode()) {
            clearPending()
            return
        }
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
        pendingIsBeta = false
        pendingSourceMessage = null
        isPendingStart = false
        lastProgress = 0f
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

    fun startDownload(account: Int) {
        val update = SharedConfig.pendingAppUpdate ?: return
        val doc = update.document ?: return

        isPendingStart = true
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading)

        val cached = pendingSourceMessage
        if (cached != null) {
            beginLoad(account, doc, MessageObject(account, cached, false, false))
            return
        }

        val messageId = update.id
        if (messageId <= 0) {
            refreshPendingAndStart(account)
            return
        }
        val mc = MessagesController.getInstance(account)
        mc.userNameResolver.resolve(USERNAME) { peerId ->
            AndroidUtilities.runOnUIThread {
                if (!isPendingStart) return@runOnUIThread
                if (peerId == null || peerId == 0L || peerId == Long.MAX_VALUE) {
                    stopPendingStart()
                    return@runOnUIThread
                }
                resolvedChannelId = -peerId
                val req = TLRPC.TL_channels_getMessages().apply {
                    channel = mc.getInputChannel(-peerId)
                    id.add(messageId)
                }
                ConnectionsManager.getInstance(account).sendRequest(req) { resp, _ ->
                    AndroidUtilities.runOnUIThread {
                        if (!isPendingStart) return@runOnUIThread
                        val msg = (resp as? TLRPC.messages_Messages)?.messages
                            ?.firstOrNull { it.id == messageId }
                        val freshDoc = msg?.let { extractApkInfo(it)?.document }
                        if (msg == null || freshDoc == null) {
                            beginLoad(account, doc, sourceMessageParent(messageId))
                        } else {
                            pendingSourceMessage = msg
                            beginLoad(account, freshDoc, MessageObject(account, msg, false, false))
                        }
                    }
                }
            }
        }
    }

    fun cancelDownload(account: Int) {
        if (isPendingStart) {
            isPendingStart = false
        } else {
            SharedConfig.pendingAppUpdate?.document?.let {
                FileLoader.getInstance(account).cancelLoadFile(it)
            }
        }
        lastProgress = 0f
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading)
    }

    private fun refreshPendingAndStart(account: Int) {
        check {
            AndroidUtilities.runOnUIThread {
                if (!isPendingStart) return@runOnUIThread
                if ((SharedConfig.pendingAppUpdate?.id ?: 0) > 0) {
                    startDownload(account)
                } else {
                    stopPendingStart()
                }
            }
        }
    }

    private fun sourceMessageParent(messageId: Int) =
        "sent_${resolvedChannelId ?: 0L}_${messageId}"

    private fun stopPendingStart() {
        isPendingStart = false
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading)
    }

    private fun beginLoad(account: Int, document: TLRPC.Document, parent: Any) {
        isPendingStart = false
        FileLoader.getInstance(account).loadFile(document, parent, FileLoader.PRIORITY_NORMAL, 1)
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading)
    }

    @JvmStatic
    fun onFileProgress(fileName: String, loadedSize: Long, totalSize: Long) {
        val doc = SharedConfig.pendingAppUpdate?.document ?: return
        if (FileLoader.getAttachFileName(doc) != fileName) return
        if (totalSize <= 0) return
        lastProgress = (loadedSize.toFloat() / totalSize).coerceIn(0f, 1f)
    }

    fun isDownloading(): Boolean {
        val doc = SharedConfig.pendingAppUpdate?.document ?: return false
        return FileLoader.getInstance(UserConfig.selectedAccount)
            .isLoadingFile(FileLoader.getAttachFileName(doc))
    }

    fun getDownloadProgress(): Float? {
        if (!isDownloading()) return null
        return lastProgress
    }

    fun getCompletedApkFile(): File? {
        val doc = SharedConfig.pendingAppUpdate?.document ?: return null
        if (isDownloading()) return null
        val file = FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(doc, true)
        return file?.takeIf { it.exists() }
    }

    fun check(callback: ((CheckResult) -> Unit)?) {
        val account = UserConfig.selectedAccount
        if (!InuConfig.UPDATES_ENABLED.value) {
            callback?.invoke(CheckResult.Error("updates are disabled"))
            return
        }
        if (!UserConfig.getInstance(account).isClientActivated) {
            callback?.invoke(CheckResult.Error("Not logged in"))
            return
        }
        if (BuildConfig.INU_BUILD_TYPE == "debug") { callback?.invoke(CheckResult.UpToDate); return }
        val now = System.currentTimeMillis()
        // entiny: back off after a failed resolve so offline/censored networks don't retry per message
        if (now - lastResolveFailureMs < RESOLVE_BACKOFF_MS) {
            callback?.invoke(CheckResult.Error("resolve backoff"))
            return
        }
        if (inflight && now - inflightSince < INFLIGHT_TIMEOUT_MS) {
            if (callback != null) {
                synchronized(queuedCallbacks) { queuedCallbacks.add(callback) }
            }
            return
        }
        inflight = true
        inflightSince = now
        MessagesController.getInstance(account).userNameResolver.resolve(USERNAME) { peerId ->
            if (peerId == null || peerId == 0L || peerId == Long.MAX_VALUE) {
                lastResolveFailureMs = System.currentTimeMillis()
                finish(callback, CheckResult.Error("resolve failed"))
                return@resolve
            }
            lastResolveFailureMs = 0L
            resolvedChannelId = -peerId
            performSearch(account, peerId, callback)
        }
    }

    private fun performSearch(account: Int, peerId: Long, callback: ((CheckResult) -> Unit)?) {
        searchByTag(account, peerId, "#release") { releaseMessages, err ->
            if (err != null) {
                finish(callback, CheckResult.Error(err))
                return@searchByTag
            }
            if (!InuConfig.UPDATES_INCLUDE_BETA.value) {
                resolveBestUpdate(releaseMessages, callback)
                return@searchByTag
            }
            searchByTag(account, peerId, "#prerelease") { betaMessages, betaErr ->
                if (betaErr != null) {
                    finish(callback, CheckResult.Error(betaErr))
                    return@searchByTag
                }
                resolveBestUpdate(releaseMessages + betaMessages, callback)
            }
        }
    }

    private data class AlbumCaption(
        val groupedId: Long,
        val message: String,
        val entities: ArrayList<TLRPC.MessageEntity>,
    )

    @Volatile
    private var lastAlbumCaption: AlbumCaption? = null

    @Volatile
    private var lastAlbumApkMsg: TLRPC.Message? = null

    private fun searchByTag(
        account: Int,
        peerId: Long,
        tag: String,
        callback: (List<TLRPC.Message>, String?) -> Unit,
    ) {
        val mc = MessagesController.getInstance(account)
        val req = TLRPC.TL_messages_search().apply {
            peer = mc.getInputPeer(peerId)
            q = tag
            filter = TLRPC.TL_inputMessagesFilterDocument()
            limit = 10
        }
        ConnectionsManager.getInstance(account).sendRequest(req) { resp, err ->
            AndroidUtilities.runOnUIThread {
                if (err != null || resp !is TLRPC.messages_Messages) {
                    callback(emptyList(), err?.text ?: "no response")
                    return@runOnUIThread
                }
                val messages = resp.messages
                // entiny: in multi-APK albums only one file holds the caption; fetch sibling files (-3..3) so pluginless/plugins both match
                val albumHeads = messages.filter { it.grouped_id != 0L && extractApkInfo(it) == null }
                if (albumHeads.isEmpty() || peerId >= 0L) {
                    callback(messages, null)
                    return@runOnUIThread
                }
                val siblingIds = ArrayList<Int>()
                for (head in albumHeads) {
                    for (delta in -3..3) {
                        if (delta != 0 && head.id + delta > 0) siblingIds.add(head.id + delta)
                    }
                }
                val chReq = TLRPC.TL_channels_getMessages().apply {
                    channel = mc.getInputChannel(-peerId)
                    id.addAll(siblingIds)
                }
                ConnectionsManager.getInstance(account).sendRequest(chReq) { chResp, _ ->
                    AndroidUtilities.runOnUIThread {
                        val combined = ArrayList<TLRPC.Message>(messages)
                        val extra = (chResp as? TLRPC.messages_Messages)?.messages.orEmpty()
                        for (sib in extra) {
                            if (sib.grouped_id == 0L) continue
                            val head = albumHeads.firstOrNull { it.grouped_id == sib.grouped_id } ?: continue
                            if (sib.message.isNullOrEmpty()) {
                                sib.message = head.message
                                sib.entities = cloneEntities(head.entities)
                            }
                            combined.add(sib)
                        }
                        callback(combined, null)
                    }
                }
            }
        }
    }

    private fun resolveBestUpdate(candidates: List<TLRPC.Message>, callback: ((CheckResult) -> Unit)?) {
        val match = candidates.mapNotNull { msg -> extractApkInfo(msg)?.let { msg to it } }
            .maxByOrNull { it.second.verCode }
        val currentVerCode = currentVersionCode()
        if (match == null || match.second.verCode <= currentVerCode) {
            clearPending()
            finish(callback, CheckResult.UpToDate)
            return
        }
        val (msg, info) = match
        val isBeta = msg.message?.contains("#prerelease") == true
        val updateObj = applyUpdate(msg, info, currentVerCode, isBeta)
        if (!isBeta) {
            fetchChangelogJson(info, updateObj) {
                finish(callback, CheckResult.Updated(updateObj))
            }
        } else {
            finish(callback, CheckResult.Updated(updateObj))
        }
    }

    @JvmStatic
    fun onNewMessage(msg: TLRPC.Message) {
        if (!InuConfig.UPDATES_ENABLED.value) return
        if (BuildConfig.INU_BUILD_TYPE == "debug") return
        val channelId = resolvedChannelId
        if (channelId == null) {
            check(null)
            return
        }
        if (msg.peer_id?.channel_id != channelId) return
        var targetMsg = msg
        if (msg.grouped_id != 0L) {
            if (!msg.message.isNullOrEmpty()) {
                lastAlbumCaption = AlbumCaption(msg.grouped_id, msg.message, cloneEntities(msg.entities))
                val prevApk = lastAlbumApkMsg
                if (extractApkInfo(msg) == null && prevApk != null && prevApk.grouped_id == msg.grouped_id) {
                    prevApk.message = msg.message
                    prevApk.entities = cloneEntities(msg.entities)
                    targetMsg = prevApk
                }
            } else {
                if (extractApkInfo(msg) != null) {
                    lastAlbumApkMsg = msg
                }
                val cached = lastAlbumCaption
                if (cached != null && cached.groupedId == msg.grouped_id) {
                    msg.message = cached.message
                    msg.entities = cloneEntities(cached.entities)
                }
            }
        }
        val text = targetMsg.message ?: return
        val isBeta = text.contains("#prerelease")
        if (isBeta) {
            if (!InuConfig.UPDATES_INCLUDE_BETA.value) return
        } else if (!text.contains("#release")) {
            return
        }
        val info = extractApkInfo(targetMsg) ?: return
        val currentVerCode = currentVersionCode()
        if (info.verCode <= currentVerCode) return
        AndroidUtilities.runOnUIThread {
            val updateObj = applyUpdate(targetMsg, info, currentVerCode, isBeta)
            if (!isBeta) {
                fetchChangelogJson(info, updateObj) {
                    revealPendingUpdate()
                    InuConfig.UPDATE_LAST_CHECK_MS.value = System.currentTimeMillis()
                }
            } else {
                revealPendingUpdate()
                InuConfig.UPDATE_LAST_CHECK_MS.value = System.currentTimeMillis()
            }
        }
    }

    private fun applyUpdate(msg: TLRPC.Message, info: ApkInfo, currentVerCode: Int, isBeta: Boolean): TLRPC.TL_help_appUpdate {
        val updateObj = TLRPC.TL_help_appUpdate().apply {
            flags = flags or 2
            id = msg.id
            version = info.verCode.toString()
            text = msg.message ?: ""
            entities = cloneEntities(msg.entities)
            document = info.document
        }

        val blockquote = updateObj.entities.firstOrNull { it is TLRPC.TL_messageEntityBlockquote }
        if (blockquote != null) {
            val start = blockquote.offset
            val end = blockquote.offset + blockquote.length
            val newEntities = arrayListOf<TLRPC.MessageEntity>()
            for (entity in updateObj.entities) {
                if (entity === blockquote) continue
                if (entity.offset + entity.length <= start) continue
                if (entity.offset >= end) continue
                val clippedStart = max(entity.offset, start)
                val clippedEnd = min(entity.offset + entity.length, end)
                entity.offset = clippedStart - start
                entity.length = clippedEnd - clippedStart
                newEntities.add(entity)
            }
            updateObj.text = updateObj.text.substring(start, end)
            updateObj.entities = newEntities
        }

        SharedConfig.pendingAppUpdate = updateObj
        SharedConfig.pendingAppUpdateBuildVersion = currentVerCode
        SharedConfig.saveConfig()
        pendingBetaUpdate = BetaUpdate(info.appVerName, info.verCode, updateObj.text)
        pendingIsBeta = isBeta
        pendingSourceMessage = msg
        return updateObj
    }

    private fun fetchChangelogJson(info: ApkInfo, updateObj: TLRPC.TL_help_appUpdate, onDone: () -> Unit) {
        Utilities.globalQueue.postRunnable {
            var fetchedText: String? = null
            try {
                val conn = (URL(CHANGELOG_JSON_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3500
                    readTimeout = 3500
                    requestMethod = "GET"
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONObject(body)
                    val exactKey = "entinyGram ${info.appVerName} (${info.verCode})"
                    var entry = root.optJSONObject(exactKey)
                    if (entry == null) {
                        val keys = root.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            if (k.contains("(${info.verCode})")) {
                                entry = root.optJSONObject(k)
                                break
                            }
                        }
                    }
                    if (entry != null) {
                        val ua = entry.optString("ua").trim()
                        val en = entry.optString("en").trim()
                        if (ua.isNotEmpty() || en.isNotEmpty()) {
                            fetchedText = buildString {
                                if (ua.isNotEmpty()) append("🇺🇦 UK:\n").append(ua)
                                if (en.isNotEmpty()) {
                                    if (isNotEmpty()) append("\n\n")
                                    append("🇺🇸 EN:\n").append(en)
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Throwable) {
            }
            AndroidUtilities.runOnUIThread {
                if (!fetchedText.isNullOrEmpty()) {
                    updateObj.text = fetchedText
                    updateObj.entities = ArrayList()
                    SharedConfig.saveConfig()
                    pendingBetaUpdate = BetaUpdate(info.appVerName, info.verCode, updateObj.text)
                }
                onDone()
            }
        }
    }

    private fun cloneEntities(entities: ArrayList<TLRPC.MessageEntity>?): ArrayList<TLRPC.MessageEntity> {
        val out = ArrayList<TLRPC.MessageEntity>(entities?.size ?: 0)
        entities?.forEach { entity ->
            InuUtils.cloneTLObject(entity, TLRPC.MessageEntity::TLdeserialize)?.let(out::add)
        }
        return out
    }

    private fun finish(callback: ((CheckResult) -> Unit)?, result: CheckResult) {
        inflight = false
        if (result is CheckResult.UpToDate || result is CheckResult.Updated) {
            InuConfig.UPDATE_LAST_CHECK_MS.value = System.currentTimeMillis()
        }
        callback?.invoke(result)
        val queued = synchronized(queuedCallbacks) {
            if (queuedCallbacks.isEmpty()) null
            else ArrayList(queuedCallbacks).also { queuedCallbacks.clear() }
        }
        queued?.forEach { it.invoke(result) }
    }

    @Suppress("DEPRECATION")
    private fun currentVersionCode(): Int = packageInfo.versionCode

    private fun extractApkInfo(msg: TLRPC.Message): ApkInfo? {
        val media = msg.media as? TLRPC.TL_messageMediaDocument ?: return null
        val doc = media.document ?: return null
        val nameAttr = doc.attributes.filterIsInstance<TLRPC.TL_documentAttributeFilename>().firstOrNull()
            ?: return null
        val match = APK_RE.matchEntire(nameAttr.file_name) ?: return null
        val appVerName = match.groupValues[1]
        val verCode = match.groupValues[2].toIntOrNull() ?: return null
        return ApkInfo(verCode, appVerName, doc)
    }

    sealed class CheckResult {
        object UpToDate : CheckResult()
        data class Updated(val update: TLRPC.TL_help_appUpdate) : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    private data class ApkInfo(
        val verCode: Int,
        val appVerName: String,
        val document: TLRPC.Document,
    )
}
