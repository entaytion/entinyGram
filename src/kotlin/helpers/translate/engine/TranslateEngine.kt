package desu.inugram.helpers.translate.engine

import android.util.Log
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.TranslateController
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.Bulletin
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serial translation coordinator for third-party providers.
 *
 * Design goals (unlike ad-hoc implementations):
 * - **No duplicate requests.** A message is queued at most once: in-flight messages are tracked
 *   by (dialog, message, kind), so the stock 150 ms re-check loop cannot pile up identical
 *   provider calls.
 * - **Bounded retries, no permanent poisoning.** A failed message is remembered for
 *   [FAIL_RETRY_WINDOW_MS] so a dead provider is not hammered on every scroll; after the window
 *   expires the next re-push retries it (and a provider switch clears failures immediately).
 *   Previously a single failure marked a message as failed forever, which silently killed
 *   auto-translate even after the user switched providers.
 * - **Deterministic rate behavior.** One worker thread drains the queue, so bursts of auto
 *   translations cannot trip provider rate limits the way parallel requests would.
 * - **Clean cancellation.** Toggling a dialog off cancels queued work and drops in-flight
 *   callbacks instead of applying stale translations afterwards.
 * - **Visible failures.** Every final failure is logged (Log.d) and a one-per-dialog-per-minute
 *   error bulletin tells the user why nothing translated.
 *
 * All public methods are safe to call from any thread; callbacks are delivered on the UI thread.
 */
object TranslateEngine {

    private const val TAG = "EntinyTranslate"

    private const val KIND_TEXT = 0
    private const val KIND_TRANSCRIPTION = 1
    private const val KIND_POLL = 2
    private const val KIND_WEBPAGE = 3

    /** How long a failed message stays deduped before the stock re-push loop may retry it. */
    private const val FAIL_RETRY_WINDOW_MS = 30_000L

    /** How often a failure bulletin may pop for the same dialog. */
    private const val BULLETIN_COOLDOWN_MS = 60_000L

    private class JobKey(val dialogId: Long, val msgId: Int, val kind: Int) {
        override fun equals(other: Any?): Boolean =
            other is JobKey && other.dialogId == dialogId && other.msgId == msgId && other.kind == kind

        override fun hashCode(): Int {
            var h = dialogId.hashCode()
            h = h * 31 + msgId
            h = h * 31 + kind
            return h
        }
    }

    private interface Job {
        val key: JobKey
        val provider: TranslationProvider
        val epoch: Int

        /** Performs the translation; returns the result object. Throws on failure. */
        fun run(): Any

        /** Delivers the result (or null) to the caller on the UI thread. */
        fun deliver(result: Any?)
    }

    private abstract class BaseJob(
        override val key: JobKey,
        override val provider: TranslationProvider,
        val toLang: String,
        override val epoch: Int,
    ) : Job

    private class TextJob(
        key: JobKey,
        provider: TranslationProvider,
        val text: String,
        val entities: List<TLRPC.MessageEntity>?,
        toLang: String,
        epoch: Int,
        val callback: Utilities.Callback4<Boolean, Int, TLRPC.TL_textWithEntities, String>,
    ) : BaseJob(key, provider, toLang, epoch) {

        private val transcription = key.kind == KIND_TRANSCRIPTION

        override fun run(): Any {
            val result = translateWithEntities(provider, text, entities, toLang)
            val twe = TLRPC.TL_textWithEntities().apply {
                this.text = result.first
                if (result.second.isNotEmpty()) this.entities = result.second
            }
            return twe
        }

        override fun deliver(result: Any?) {
            callback.run(transcription, key.msgId, result as? TLRPC.TL_textWithEntities, toLang)
        }
    }

    private class PollJob(
        key: JobKey,
        provider: TranslationProvider,
        val poll: TranslateController.PollText,
        toLang: String,
        epoch: Int,
        val callback: Utilities.Callback3<Int, TranslateController.PollText, String>,
    ) : BaseJob(key, provider, toLang, epoch) {

        override fun run(): Any {
            val out = TranslateController.PollText()
            poll.question?.let { q ->
                val r = translateWithEntities(provider, q.text, q.entities, toLang)
                out.question = TLRPC.TL_textWithEntities().apply {
                    this.text = r.first
                    if (r.second.isNotEmpty()) this.entities = r.second
                }
            }
            for (answer in poll.answers) {
                val r = translateWithEntities(provider, answer.text.text, answer.text.entities, toLang)
                out.answers.add(
                    TLRPC.TL_pollAnswer().apply {
                        this.text = TLRPC.TL_textWithEntities().apply {
                            this.text = r.first
                            if (r.second.isNotEmpty()) this.entities = r.second
                        }
                        this.option = answer.option
                    }
                )
            }
            poll.solution?.let { s ->
                val r = translateWithEntities(provider, s.text, s.entities, toLang)
                out.solution = TLRPC.TL_textWithEntities().apply {
                    this.text = r.first
                    if (r.second.isNotEmpty()) this.entities = r.second
                }
            }
            return out
        }

        override fun deliver(result: Any?) {
            callback.run(key.msgId, result as? TranslateController.PollText, toLang)
        }
    }

    /**
     * Translates a message web preview (title / description / site name / author parts) as one
     * job, so third-party providers are used for previews too instead of the stock Telegram API.
     * Returns a cloned [TLRPC.TL_webPage] with the translated parts; on failure the caller simply
     * keeps the original preview (the engine already surfaced the error bulletin).
     */
    private class WebPageJob(
        key: JobKey,
        provider: TranslationProvider,
        val original: TLRPC.TL_webPage,
        val parts: List<Pair<Char, String>>,
        toLang: String,
        epoch: Int,
        val callback: Utilities.Callback4<Boolean, Int, TLRPC.TL_webPage, String>,
    ) : BaseJob(key, provider, toLang, epoch) {

        override fun run(): Any {
            val clone = cloneWebPage(original)
            val translated = ArrayList<Pair<Char, TLRPC.TL_textWithEntities>>(parts.size)
            for ((tag, text) in parts) {
                val r = translateWithEntities(provider, text, null, toLang)
                translated.add(
                    tag to TLRPC.TL_textWithEntities().apply {
                        this.text = r.first
                        if (r.second.isNotEmpty()) this.entities = r.second
                    }
                )
            }
            if (clone != null) {
                for ((tag, twe) in translated) {
                    val text = twe.text ?: continue
                    when (tag) {
                        't' -> clone.title = text
                        'd' -> clone.description = text
                        's' -> clone.site_name = text
                        'a' -> clone.author = text
                    }
                }
            }
            return clone ?: original
        }

        override fun deliver(result: Any?) {
            callback.run(false, key.msgId, result as? TLRPC.TL_webPage, toLang)
        }
    }

    private fun cloneWebPage(wp: TLRPC.TL_webPage): TLRPC.TL_webPage? {
        return desu.inugram.helpers.InuUtils.cloneTLObject(wp, TLRPC.WebPage::TLdeserialize) as? TLRPC.TL_webPage
    }

    private const val MAX_ATTEMPTS = 2
    private const val RETRY_BASE_DELAY_MS = 1_000L

    private val lock = Object()
    private val queue = ArrayDeque<Job>()
    private val inFlight = HashSet<JobKey>()
    private val failedAt = HashMap<JobKey, Long>()
    private val epochs = HashMap<Long, Int>()
    private val bulletinsShown = HashMap<Long, Long>()
    private val running = AtomicBoolean(false)

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "entiny-translate").apply { isDaemon = true }
    }

    /** Translates a message body or voice transcription. Returns true when the engine took it over. */
    fun enqueueText(
        dialogId: Long,
        msgId: Int,
        transcription: Boolean,
        text: String,
        entities: List<TLRPC.MessageEntity>?,
        toLang: String,
        provider: TranslationProvider,
        callback: Utilities.Callback4<Boolean, Int, TLRPC.TL_textWithEntities, String>,
    ): Boolean {
        val kind = if (transcription) KIND_TRANSCRIPTION else KIND_TEXT
        val key = JobKey(dialogId, msgId, kind)
        synchronized(lock) {
            if (inFlight.contains(key)) return true // already queued or running
            val failedAtTs = failedAt[key]
            if (failedAtTs != null && System.currentTimeMillis() - failedAtTs < FAIL_RETRY_WINDOW_MS) {
                return true // still deduped; retry after the window
            }
            if (failedAtTs != null) failedAt.remove(key) // window expired -> retry
            if (text.isBlank()) {
                // Nothing to translate; still complete the contract so the caller stops re-pushing.
                AndroidUtilities.runOnUIThread {
                    callback.run(transcription, msgId, TLRPC.TL_textWithEntities().apply { this.text = "" }, toLang)
                }
                return true
            }
            Log.d(TAG, "enqueue dialog=$dialogId msg=$msgId kind=$kind to=$toLang provider=${provider.id} chars=${text.length}")
            inFlight.add(key)
            queue.addLast(TextJob(key, provider, text, entities, toLang, epochs[dialogId] ?: 0, callback))
        }
        pump()
        return true
    }

    /**
     * Translates a web preview. Returns true when the engine took it over; false when [text] is
     * empty (nothing to do) — the caller then keeps its own stock path.
     */
    fun enqueueWebPage(
        dialogId: Long,
        msgId: Int,
        original: TLRPC.TL_webPage,
        parts: List<Pair<Char, String>>,
        toLang: String,
        provider: TranslationProvider,
        callback: Utilities.Callback4<Boolean, Int, TLRPC.TL_webPage, String>,
    ): Boolean {
        if (parts.isEmpty()) return false
        val key = JobKey(dialogId, msgId, KIND_WEBPAGE)
        synchronized(lock) {
            if (inFlight.contains(key)) return true
            val failedAtTs = failedAt[key]
            if (failedAtTs != null && System.currentTimeMillis() - failedAtTs < FAIL_RETRY_WINDOW_MS) {
                return true
            }
            if (failedAtTs != null) failedAt.remove(key)
            Log.d(TAG, "enqueue webpage dialog=$dialogId msg=$msgId to=$toLang provider=${provider.id} parts=${parts.size}")
            inFlight.add(key)
            queue.addLast(WebPageJob(key, provider, original, parts, toLang, epochs[dialogId] ?: 0, callback))
        }
        pump()
        return true
    }

    /** Translates a poll. Returns true when the engine took it over. */
    fun enqueuePoll(
        dialogId: Long,
        msgId: Int,
        poll: TranslateController.PollText,
        toLang: String,
        provider: TranslationProvider,
        callback: Utilities.Callback3<Int, TranslateController.PollText, String>,
    ): Boolean {
        val key = JobKey(dialogId, msgId, KIND_POLL)
        synchronized(lock) {
            if (inFlight.contains(key)) return true
            val failedAtTs = failedAt[key]
            if (failedAtTs != null && System.currentTimeMillis() - failedAtTs < FAIL_RETRY_WINDOW_MS) {
                return true
            }
            if (failedAtTs != null) failedAt.remove(key)
            Log.d(TAG, "enqueue poll dialog=$dialogId msg=$msgId to=$toLang provider=${provider.id}")
            inFlight.add(key)
            queue.addLast(PollJob(key, provider, poll, toLang, epochs[dialogId] ?: 0, callback))
        }
        pump()
        return true
    }

    fun isInFlight(dialogId: Long, msgId: Int, transcription: Boolean): Boolean =
        synchronized(lock) {
            inFlight.contains(JobKey(dialogId, msgId, if (transcription) KIND_TRANSCRIPTION else KIND_TEXT))
        }

    /** Cancels queued and in-flight work for one dialog; in-flight results are discarded. */
    fun cancelDialog(dialogId: Long) {
        synchronized(lock) {
            epochs[dialogId] = (epochs[dialogId] ?: 0) + 1
            val it = queue.iterator()
            while (it.hasNext()) {
                val job = it.next()
                if (job.key.dialogId == dialogId) {
                    it.remove()
                    inFlight.remove(job.key)
                }
            }
        }
    }

    /** Clears failure marks for a dialog so previously failed messages can be retried. */
    fun resetDialog(dialogId: Long) {
        synchronized(lock) {
            failedAt.keys.removeIf { it.dialogId == dialogId }
        }
    }

    /** Clears failure marks and bulletin cooldowns for every dialog (provider switch / app start). */
    fun resetAll() {
        synchronized(lock) {
            failedAt.clear()
            bulletinsShown.clear()
        }
    }

    /** Cancels everything (app teardown / account switch). */
    fun cancelAll() {
        synchronized(lock) {
            epochs.clear()
            queue.clear()
            inFlight.clear()
            failedAt.clear()
        }
    }

    /** Clears the failure mark for a single message (e.g. after the user invalidates a translation). */
    fun unfailMessage(dialogId: Long, msgId: Int, transcription: Boolean) {
        synchronized(lock) {
            failedAt.remove(JobKey(dialogId, msgId, if (transcription) KIND_TRANSCRIPTION else KIND_TEXT))
        }
    }

    private fun pump() {
        if (running.getAndSet(true)) return
        worker.execute { loop() }
    }

    private fun loop() {
        while (true) {
            val job: Job = synchronized(lock) {
                if (queue.isEmpty()) {
                    running.set(false)
                    // Re-check: a job may have been enqueued right after the emptiness check.
                    if (queue.isEmpty()) return
                    running.set(true)
                }
                queue.removeFirst()
            }

            val cancelled = synchronized(lock) { job.epoch != (epochs[job.key.dialogId] ?: 0) }
            if (cancelled) {
                finish(job, null, cancelled = true)
                continue
            }

            val result: Any?
            try {
                Log.d(TAG, "start dialog=${job.key.dialogId} msg=${job.key.msgId} provider=${job.provider.id}")
                result = job.run()
            } catch (e: Exception) {
                val error = e.message ?: e.javaClass.simpleName
                Log.d(TAG, "FAIL dialog=${job.key.dialogId} msg=${job.key.msgId} provider=${job.provider.id} error=$error", e)
                notifyFailure(job.key.dialogId, error)
                synchronized(lock) { failedAt[job.key] = System.currentTimeMillis() }
                finish(job, null, cancelled = false)
                continue
            }

            Log.d(TAG, "ok dialog=${job.key.dialogId} msg=${job.key.msgId} provider=${job.provider.id}")
            synchronized(lock) { failedAt.remove(job.key) }
            finish(job, result, cancelled = false)
        }
    }

    private fun finish(job: Job, result: Any?, cancelled: Boolean) {
        synchronized(lock) {
            inFlight.remove(job.key)
        }
        if (cancelled) return // toggled off mid-flight: do not apply stale translations
        AndroidUtilities.runOnUIThread { job.deliver(result) }
    }

    private fun notifyFailure(dialogId: Long, error: String) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val last = bulletinsShown[dialogId]
            if (last != null && now - last < BULLETIN_COOLDOWN_MS) return
            bulletinsShown[dialogId] = now
        }
        val message = LocaleController.formatString(R.string.InuTranslateProviderFailed, error)
        AndroidUtilities.runOnUIThread {
            NotificationCenter.getGlobalInstance().postNotificationName(
                NotificationCenter.showBulletin,
                Bulletin.TYPE_ERROR,
                message,
            )
        }
    }

    /**
     * Translates [text] with entity preservation and retry/backoff.
     * Throws the last failure when the provider is unreachable; [ProviderConfigException] is
     * rethrown immediately (bad key / unsupported language — no retry).
     */
    private fun translateWithEntities(
        provider: TranslationProvider,
        text: String,
        entities: List<TLRPC.MessageEntity>?,
        toLang: String,
    ): Pair<String, ArrayList<TLRPC.MessageEntity>> {
        var attempt = 0
        while (true) {
            try {
                val marked = EntityKeeper.mark(text, entities)
                val translated = provider.translate(marked, toLang)
                val (resultText, resultEntities) = EntityKeeper.unmark(translated, entities)
                if (resultText.isBlank()) throw IOException("Provider returned empty translation")
                return resultText to resultEntities
            } catch (e: ProviderRateLimitException) {
                attempt++
                if (attempt > MAX_ATTEMPTS) throw e
                sleepBackoff(attempt)
            } catch (e: ProviderConfigException) {
                throw e // permanent — do not retry
            } catch (e: IOException) {
                attempt++
                if (attempt > MAX_ATTEMPTS) throw e
                sleepBackoff(attempt)
            } catch (e: Exception) {
                throw e // unexpected — fail fast
            }
        }
    }

    private fun sleepBackoff(attempt: Int) {
        try {
            Thread.sleep(RETRY_BASE_DELAY_MS * attempt)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
