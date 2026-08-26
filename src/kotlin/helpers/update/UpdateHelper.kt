package desu.inugram.helpers.update

import android.os.Build
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.maps.MapsHelper
import desu.inugram.helpers.security.ParanoiaHelper
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
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import java.io.File
import kotlin.math.max
import kotlin.math.min

object UpdateHelper {
    // CI channel entinygram-ci-upload.ts posts full/lite APK documents to, tagged #release.
    const val USERNAME = "entinyGramCI"
    private const val CHECK_INTERVAL_MS = 4L * 60 * 60 * 1000
    private const val INFLIGHT_TIMEOUT_MS = 60L * 1000

    // entinygram-arm64-{full|lite}-{appVerName}-{verCode}.apk (see scripts/ci/version.ts)
    private val APK_RE = Regex("^entinygram-arm64-(full|lite)-(.+)-(\\d+)\\.apk$")

    // the current build's variant, matching the filename tag CI produces for it
    private val ourVariant: String get() = if (MapsHelper.hasMapLibre) "full" else "lite"

    // bare channel id for USERNAME, cached from the first successful username resolve so we
    // never have to hardcode entinyGramCI's numeric id (which can differ per-environment/test).
    @Volatile
    private var resolvedChannelId: Long? = null

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

    // cached source message of the current pending update, set by applyUpdate. lets
    // startDownload skip the resolver+RPC dance when the update was detected this session.
    @Volatile
    private var pendingSourceMessage: TLRPC.Message? = null

    // true between the click on Update and FileLoader.loadFile actually firing. lets the row
    // show the Downloading state immediately even while the async file-ref refresh dance is
    // still running.
    @Volatile var isPendingStart: Boolean = false
        private set

    // last known progress for the pending document's download, fed by onFileProgress
    // (NotificationCenter.fileLoadProgressChanged), since FileLoader has no synchronous getter.
    @Volatile private var lastProgress: Float = 0f

    // applyUpdate doesn't post appUpdateAvailable itself — the caller does it, via
    // revealPendingUpdate, once the changelog dialog is on screen (so the bar slides in behind
    // the dialog instead of visibly popping into the page underneath).
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

    /** Feed from NotificationCenter.fileLoadProgressChanged (args = [fileName, loadedSize, totalSize]). */
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
        if (!UserConfig.getInstance(account).isClientActivated) {
            callback?.invoke(CheckResult.Error("Not logged in"))
            return
        }
        if (BuildConfig.INU_BUILD_TYPE == "debug") { callback?.invoke(CheckResult.UpToDate); return }
        val now = System.currentTimeMillis()
        if (inflight && now - inflightSince < INFLIGHT_TIMEOUT_MS) { callback?.invoke(CheckResult.InFlight); return }
        inflight = true
        inflightSince = now
        MessagesController.getInstance(account).userNameResolver.resolve(USERNAME) { peerId ->
            if (peerId == null || peerId == 0L || peerId == Long.MAX_VALUE) {
                finish(callback, CheckResult.Error("resolve failed"))
                return@resolve
            }
            resolvedChannelId = -peerId
            performSearch(account, peerId, callback)
        }
    }

    private fun performSearch(account: Int, peerId: Long, callback: ((CheckResult) -> Unit)?) {
        val mc = MessagesController.getInstance(account)
        val req = TLRPC.TL_messages_search().apply {
            peer = mc.getInputPeer(peerId)
            q = "#release"
            filter = TLRPC.TL_inputMessagesFilterDocument()
            limit = 10
        }
        ConnectionsManager.getInstance(account).sendRequest(req) { resp, err ->
            AndroidUtilities.runOnUIThread {
                if (err != null || resp !is TLRPC.messages_Messages) {
                    finish(callback, CheckResult.Error(err?.text ?: "no response"))
                    return@runOnUIThread
                }
                val match = resp.messages.firstNotNullOfOrNull { msg ->
                    extractApkInfo(msg)?.let { msg to it }
                }
                val currentVerCode = currentVersionCode()
                if (match == null || match.second.verCode <= currentVerCode) {
                    clearPending()
                    finish(callback, CheckResult.UpToDate)
                    return@runOnUIThread
                }
                val (msg, info) = match
                val updateObj = applyUpdate(msg, info, currentVerCode)
                finish(callback, CheckResult.Updated(updateObj))
            }
        }
    }

    @JvmStatic
    fun onNewMessage(msg: TLRPC.Message) {
        if (!InuConfig.UPDATES_ENABLED.value) return
        if (BuildConfig.INU_BUILD_TYPE == "debug") return
        val channelId = resolvedChannelId
        if (channelId != null && msg.peer_id?.channel_id != channelId) return
        if (msg.message?.contains("#release") != true) return
        val info = extractApkInfo(msg) ?: return
        val currentVerCode = currentVersionCode()
        if (info.verCode <= currentVerCode) return
        AndroidUtilities.runOnUIThread {
            applyUpdate(msg, info, currentVerCode)
            revealPendingUpdate()
            InuConfig.UPDATE_LAST_CHECK_MS.value = System.currentTimeMillis()
        }
    }

    private fun applyUpdate(msg: TLRPC.Message, info: ApkInfo, currentVerCode: Int): TLRPC.TL_help_appUpdate {
        val updateObj = TLRPC.TL_help_appUpdate().apply {
            flags = flags or 2
            // stash the source channel message id in the otherwise-unused `id` field
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
        pendingSourceMessage = msg
        return updateObj
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
        // Do not suppress future checks after a transient network/resolve error.
        // The interval is a successful-check throttle, not a failure backoff.
        if (result is CheckResult.UpToDate || result is CheckResult.Updated) {
            InuConfig.UPDATE_LAST_CHECK_MS.value = System.currentTimeMillis()
        }
        callback?.invoke(result)
    }

    @Suppress("DEPRECATION")
    private fun currentVersionCode(): Int = pInfo.versionCode

    private fun extractApkInfo(msg: TLRPC.Message): ApkInfo? {
        val media = msg.media as? TLRPC.TL_messageMediaDocument ?: return null
        val doc = media.document ?: return null
        val nameAttr = doc.attributes.filterIsInstance<TLRPC.TL_documentAttributeFilename>().firstOrNull()
            ?: return null
        val match = APK_RE.matchEntire(nameAttr.file_name) ?: return null
        if (match.groupValues[1] != ourVariant) return null
        val appVerName = match.groupValues[2]
        val verCode = match.groupValues[3].toIntOrNull() ?: return null
        return ApkInfo(verCode, appVerName, doc)
    }

    sealed class CheckResult {
        object InFlight : CheckResult()
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
