package desu.inugram.helpers.media

import android.os.SystemClock
import desu.inugram.helpers.DebugLogUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AnimatedFileDrawableStream
import org.telegram.messenger.FileLoadOperation
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.ImageReceiver
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.AnimatedFileDrawable
import java.io.File
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

object WebmStickerDebugHelper {
    private val WATCH_CHECKPOINTS_MS = longArrayOf(5_000, 20_000, 60_000)

    private val watches = WeakHashMap<ImageReceiver, Watch>()
    private val waiters = Collections.synchronizedMap(WeakHashMap<AnimatedFileDrawableStream, Waiter>())

    private class Watch(val document: TLRPC.Document, val account: Int, val context: String)

    private class Waiter(val thread: Thread, val offset: Long, val length: Int, val since: Long)

    @JvmStatic
    fun isEnabled(): Boolean = DebugLogUtils.isEnabled()

    @JvmStatic
    fun watch(imageReceiver: ImageReceiver, messageObject: MessageObject) {
        if (!isEnabled()) return
        val document = messageObject.document ?: return
        if (watches[imageReceiver]?.document?.id == document.id) return
        val watch = Watch(
            document,
            messageObject.currentAccount,
            "mid=${messageObject.id} did=${messageObject.dialogId} account=${messageObject.currentAccount}",
        )
        watches[imageReceiver] = watch
        scheduleCheck(WeakReference(imageReceiver), watch, 0)
    }

    private fun scheduleCheck(receiverRef: WeakReference<ImageReceiver>, watch: Watch, step: Int) {
        if (step >= WATCH_CHECKPOINTS_MS.size) return
        val delay = WATCH_CHECKPOINTS_MS[step] - (if (step > 0) WATCH_CHECKPOINTS_MS[step - 1] else 0)
        AndroidUtilities.runOnUIThread({
            val receiver = receiverRef.get() ?: return@runOnUIThread
            if (watches[receiver] !== watch) return@runOnUIThread
            if (!isEnabled() || !isStuck(receiver, watch.document)) {
                watches.remove(receiver)
                return@runOnUIThread
            }
            FileLog.d(
                "InuWebm stuck ${FileLoader.getAttachFileName(watch.document)} for ${WATCH_CHECKPOINTS_MS[step]}ms " +
                    "${watch.context} ${describeState(receiver, watch)}"
            )
            scheduleCheck(receiverRef, watch, step + 1)
        }, delay)
    }

    private fun isStuck(receiver: ImageReceiver, document: TLRPC.Document): Boolean =
        receiver.isAttachedToWindow &&
            receiver.mediaLocation?.document?.id == document.id &&
            receiver.animation?.hasBitmap() != true

    @JvmStatic
    fun onStreamCreated(stream: AnimatedFileDrawableStream) {
        if (!isTracked(stream)) return
        FileLog.d("InuWebm stream created ${describeStream(stream)} from ${DebugLogUtils.getCaller()}")
    }

    @JvmStatic
    fun onReadWaitBegin(stream: AnimatedFileDrawableStream, offset: Long, length: Int) {
        if (!isTracked(stream)) return
        FileLog.d("InuWebm read wait offset=$offset length=$length ${describeStream(stream)}")
        waiters[stream] = Waiter(Thread.currentThread(), offset, length, SystemClock.elapsedRealtime())
    }

    @JvmStatic
    fun onReadWaitEnd(stream: AnimatedFileDrawableStream) {
        if (!isTracked(stream)) return
        val waiter = waiters.remove(stream) ?: return
        FileLog.d(
            "InuWebm read woke offset=${waiter.offset} after=${SystemClock.elapsedRealtime() - waiter.since}ms " +
                describeStream(stream)
        )
    }

    @JvmStatic
    fun onStreamCanceled(stream: AnimatedFileDrawableStream, removeLoading: Boolean) {
        if (!isTracked(stream)) return
        FileLog.d("InuWebm stream cancel removeLoading=$removeLoading ${describeStream(stream)} from ${DebugLogUtils.getCaller()}")
    }

    @JvmStatic
    fun onDecoderCreateAttempt(drawable: AnimatedFileDrawable) {
        if (!drawable.isWebmSticker || !isEnabled()) return
        FileLog.d("InuWebm decoder attempt ${describeDrawable(drawable)}")
    }

    @JvmStatic
    fun onDrawableRecycled(drawable: AnimatedFileDrawable) {
        if (!drawable.isWebmSticker || !isEnabled()) return
        FileLog.d("InuWebm drawable recycle ${describeDrawable(drawable)} from ${DebugLogUtils.getCaller()}")
    }

    @JvmStatic
    fun onLoadCanceled(fileName: String?) {
        if (fileName == null || !fileName.endsWith(".webm") || !isEnabled()) return
        FileLog.d("InuWebm load cancel $fileName from ${DebugLogUtils.getCaller()}")
    }

    private fun isTracked(stream: AnimatedFileDrawableStream): Boolean =
        isEnabled() && MessageObject.isVideoStickerDocument(stream.document)

    private fun describeState(receiver: ImageReceiver, watch: Watch): String {
        val name = FileLoader.getAttachFileName(watch.document)
        val loader = FileLoader.getInstance(watch.account)
        val mediaKey = receiver.mediaKey
        val imageLoader = ImageLoader.getInstance()
        val tempFile = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE)
            ?.let { File(it, name.substringBeforeLast('.') + ".temp") }
        return "size=${watch.document.size}" +
            " final=${describeFile(loader.getPathToAttach(watch.document, true))}" +
            " temp=${describeFile(tempFile)}" +
            " mediaKey=$mediaKey mediaSet=${receiver.hasMediaSet()}" +
            " memCache=${mediaKey != null && imageLoader.isInMemCache(mediaKey, true)}" +
            " imageLoading=${mediaKey != null && imageLoader.imageLoadingByKeys.containsKey(mediaKey)}" +
            " fileLoading=${loader.isLoadingFile(name)}" +
            " op=${describeOperation(loader.loadOperationPaths[name])}" +
            " drawable=${receiver.animation?.let(::describeDrawable)}"
    }

    private fun describeDrawable(drawable: AnimatedFileDrawable): String =
        "[@${identity(drawable)} file=${drawable.filePath?.name} recycled=${drawable.isRecycled} running=${drawable.isRunning}" +
            " decoderCreated=${drawable.decoderCreated} decoder=${drawable.mDecoder != null} tries=${drawable.decoderTryCount}" +
            " ptrFail=${drawable.ptrFail} rendering=${drawable.renderingBuffer != null} next=${drawable.nextRenderingBuffer != null}" +
            " frameTask=${drawable.loadFrameTask != null} stream=${drawable.stream?.let(::describeStream)}]"

    private fun describeStream(stream: AnimatedFileDrawableStream): String {
        val waiter = waiters[stream]
        val waiting = waiter?.let {
            " waitingFor=${SystemClock.elapsedRealtime() - it.since}ms at offset=${it.offset} length=${it.length}" +
                " thread=${it.thread.name} stack=${DebugLogUtils.describeStack(it.thread)}"
        } ?: ""
        return "[@${identity(stream)} doc=${FileLoader.getAttachFileName(stream.document)} canceled=${stream.isCanceled}" +
            " waiting=${stream.isWaitingForLoad} finished=${stream.isFinishedLoadingFile}" +
            " op=${describeOperation(stream.loadOperation)}$waiting]"
    }

    private fun describeOperation(operation: FileLoadOperation?): String {
        operation ?: return "null"
        val position = runCatching { operation.positionInQueue }.getOrDefault(-1)
        return "[@${identity(operation)} state=${operation.state} paused=${operation.isPaused}" +
            " bytes=${operation.downloadedBytes}/${operation.totalBytesCount} requests=${operation.requestInfos?.size}" +
            " listeners=${operation.streamListeners?.size} queuePos=$position]"
    }

    private fun describeFile(file: File?): String =
        when {
            file == null -> "null"
            file.exists() -> "${file.name}:${file.length()}"
            else -> "${file.name}:missing"
        }

    private fun identity(obj: Any): String = Integer.toHexString(System.identityHashCode(obj))
}
