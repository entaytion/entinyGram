package desu.inugram.helpers.chat

import android.text.TextUtils
import android.util.Base64
import desu.inugram.InuConfig
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.TranscribeButton
import java.io.File
import java.io.IOException
import java.io.FileInputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object TranscribeHelper {

    private val inFlight = ConcurrentHashMap<String, Boolean>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    // This used to run on Utilities.globalQueue, which is a single DispatchQueue thread shared by
    // the whole app: one upload plus a two-minute read timeout stalled every other background task
    // queued behind it, and the two-slot semaphore that was supposed to cap concurrency could never
    // admit a second request anyway because the queue is serial. Own pool - concurrency is real,
    // extra taps queue instead of being rejected, and nothing else in the app waits on us.
    private val worker: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "inu-transcribe").apply { isDaemon = true }
    }

    // Keep transcription off the general memory cliff.
    private const val MAX_TRANSCRIPTION_BYTES = 32L * 1024L * 1024L

    // Gemini takes audio inline, Base64'd inside the JSON body, and rejects any request over 20MB.
    // Base64 inflates by 4/3, so this is the largest file that can still fit - past it the request
    // would come back as an opaque 400 rather than "your voice message is too long".
    private const val GEMINI_MAX_INLINE_BYTES = 14L * 1024L * 1024L

    private const val MAX_ATTEMPTS = 3

    /** HTTP 429 / 5xx: the provider is busy or briefly broken, so the request is worth repeating. */
    private class TransientHttpException(message: String) : IOException(message)

    @JvmStatic
    fun isTranscribing(messageObject: MessageObject?): Boolean {
        if (messageObject == null) return false
        val key = reqKey(messageObject)
        return inFlight[key] == true
    }

    /**
     * Invalidates a pending transcription. A provider request already blocked in
     * HttpURLConnection is allowed to finish, but its result is discarded.
     */
    @JvmStatic
    fun cancel(messageObject: MessageObject?) {
        if (messageObject == null) return
        val key = reqKey(messageObject)
        cancelled.add(key)
        if (inFlight.remove(key) != null) {
            notifyStateChange(messageObject.currentAccount, messageObject)
        }
    }

    /**
      * Whether a tap on the transcribe button goes to our provider instead of Telegram's endpoint.
      *
      * Deliberately just the toggle: it used to also require the account to be non-Premium, which
      * meant a Premium user who had gone to the trouble of configuring Gemini silently kept getting
      * Telegram's transcription instead. The toggle is off by default and switching it on is an
      * explicit choice - honour it either way.
      */
    @JvmStatic
    fun shouldUseCustomTranscribe(account: Int): Boolean = InuConfig.AI_TRANSCRIBE_ENABLED.value

    /**
     * Whether the selected provider has everything it needs to be called. Checked before anything
     * is marked in-flight, so an unconfigured provider produces one bulletin pointing at the
     * settings rather than a spinner that dies with a raw HTTP error.
     */
    private fun isProviderConfigured(): Boolean = when (InuConfig.AI_TRANSCRIBE_PROVIDER.value) {
        InuConfig.TRANSCRIBE_PROVIDER_CF ->
            InuConfig.AI_TRANSCRIBE_CF_ACCOUNT_ID.value.isNotBlank() && InuConfig.AI_TRANSCRIBE_CF_API_TOKEN.value.isNotBlank()
        InuConfig.TRANSCRIBE_PROVIDER_CUSTOM ->
            InuConfig.AI_TRANSCRIBE_CUSTOM_URL.value.isNotBlank()
        InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> InuConfig.AI_PROVIDER_GEMINI_KEY.value.isNotBlank()
        InuConfig.TRANSCRIBE_PROVIDER_OPENAI -> InuConfig.AI_PROVIDER_OPENAI_KEY.value.isNotBlank()
        InuConfig.TRANSCRIBE_PROVIDER_GROQ -> InuConfig.AI_PROVIDER_GROQ_KEY.value.isNotBlank()
        else -> false
    }

    @JvmStatic
    fun canShowTranscribeButton(account: Int, messageObject: MessageObject?): Boolean {
        if (messageObject == null) return false
        if (InuConfig.AI_TRANSCRIBE_ENABLED.value) return true
        return false
    }

    private fun reqKey(messageObject: MessageObject): String {
        return "${messageObject.currentAccount}_${messageObject.dialogId}_${messageObject.id}"
    }

    @JvmStatic
    fun transcribe(account: Int, messageObject: MessageObject?, delegate: Any? = null) {
        if (messageObject?.messageOwner == null) return

        val key = reqKey(messageObject)
        if (inFlight[key] == true) return
        cancelled.remove(key)

        if (!isProviderConfigured()) {
            BulletinFactory.global().createSimpleBulletin(
                R.raw.info,
                LocaleController.getString(R.string.InuAiTranscribeNoKey)
            ).show()
            return
        }

        inFlight[key] = true
        notifyStateChange(account, messageObject)

        val owner = messageObject.messageOwner
        val audioFile = FileLoader.getInstance(account).getPathToMessage(owner)

        if (audioFile != null && audioFile.exists() && audioFile.length() > 0) {
            processAudioFile(account, messageObject, audioFile)
        } else {
            val doc = messageObject.document
            if (doc != null) {
                FileLoader.getInstance(account).loadFile(doc, messageObject, 1, 0)
                pollFileDownload(account, messageObject, doc)
            } else {
                inFlight.remove(key)
                notifyStateChange(account, messageObject)
                showError(LocaleController.getString(R.string.InuAiTranscribeErrorNoAudio))
            }
        }
    }

    private fun pollFileDownload(account: Int, messageObject: MessageObject, doc: TLRPC.Document, attempts: Int = 0) {
        val key = reqKey(messageObject)
        if (attempts > 60) {
            inFlight.remove(key)
            notifyStateChange(account, messageObject)
            showError(LocaleController.getString(R.string.InuAiTranscribeErrorDownload))
            return
        }

        AndroidUtilities.runOnUIThread({
            if (isCancelled(key)) return@runOnUIThread
            val file = FileLoader.getInstance(account).getPathToAttach(doc, true)
            if (file != null && file.exists() && file.length() > 0) {
                processAudioFile(account, messageObject, file)
            } else {
                pollFileDownload(account, messageObject, doc, attempts + 1)
            }
        }, 500)
    }

    private fun processAudioFile(account: Int, messageObject: MessageObject, file: File) {
        val key = reqKey(messageObject)
        worker.execute {
            try {
                if (isCancelled(key)) return@execute
                if (file.length() > MAX_TRANSCRIPTION_BYTES) {
                    throw IOException(LocaleController.getString(R.string.InuAiTranscribeErrorTooLarge))
                }
                val isRound = messageObject.isRoundVideo
                val mime = if (isRound) "video/mp4" else "audio/ogg"
                val fileName = if (isRound) "video.mp4" else "voice.ogg"

                val provider = InuConfig.AI_TRANSCRIBE_PROVIDER.value
                val customPrompt = InuConfig.AI_TRANSCRIBE_PROMPT.value.trim()

                val transcribedText = withRetry {
                    when (provider) {
                        InuConfig.TRANSCRIBE_PROVIDER_GROQ -> transcribeGroq(file, fileName, mime, customPrompt)
                        InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> transcribeGemini(file, mime, customPrompt)
                        InuConfig.TRANSCRIBE_PROVIDER_OPENAI -> transcribeOpenAI(file, fileName, mime, customPrompt)
                        InuConfig.TRANSCRIBE_PROVIDER_CF -> transcribeCloudflare(file, customPrompt)
                        InuConfig.TRANSCRIBE_PROVIDER_CUSTOM -> transcribeCustom(file, fileName, mime, customPrompt)
                        else -> throw IllegalStateException("Unknown provider: $provider")
                    }
                }

                AndroidUtilities.runOnUIThread {
                    if (isCancelled(key)) return@runOnUIThread
                    inFlight.remove(key)
                    if (!TextUtils.isEmpty(transcribedText)) {
                        val owner = messageObject.messageOwner
                        owner.voiceTranscription = transcribedText
                        owner.voiceTranscriptionFinal = true
                        owner.voiceTranscriptionOpen = true
                        TranscribeButton.openVideoTranscription(messageObject)
                        MessagesStorage.getInstance(account).updateMessageVoiceTranscription(
                            messageObject.dialogId,
                            messageObject.id,
                            transcribedText,
                            owner
                        )
                        NotificationCenter.getInstance(account).postNotificationName(
                            NotificationCenter.voiceTranscriptionUpdate,
                            messageObject,
                            null,
                            transcribedText,
                            true,
                            true
                        )
                    } else {
                        notifyStateChange(account, messageObject)
                        showError(LocaleController.getString(R.string.InuAiTranscribeErrorEmpty))
                    }
                }
            } catch (e: Exception) {
                if (isCancelled(key)) return@execute
                FileLog.e("TranscribeHelper error", e)
                AndroidUtilities.runOnUIThread {
                    inFlight.remove(key)
                    notifyStateChange(account, messageObject)
                    showError(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
                }
            } finally {
                cancelled.remove(key)
            }
        }
    }

    /**
     * Repeats the request when the provider answers 429 or 5xx. Everything else - a rejected key,
     * an unknown model, audio the provider will not accept - is permanent and fails immediately;
     * retrying those just burns the user's quota and their patience.
     */
    private fun <T> withRetry(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: TransientHttpException) {
                attempt++
                if (attempt >= MAX_ATTEMPTS) throw e
                try {
                    Thread.sleep(1500L * attempt)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
    }

    /**
     * ISO code of the spoken language, or empty for provider auto-detection. Whisper-family models
     * guess from the first seconds of audio, which is exactly where short or noisy voice notes go
     * wrong - a pinned language is the single biggest accuracy win available here.
     */
    private fun languageHint(): String = InuConfig.AI_TRANSCRIBE_LANGUAGE.value.trim()

    private fun isCancelled(key: String): Boolean = cancelled.contains(key)

    private fun notifyStateChange(account: Int, messageObject: MessageObject) {
        AndroidUtilities.runOnUIThread {
            NotificationCenter.getInstance(account).postNotificationName(
                NotificationCenter.voiceTranscriptionUpdate,
                messageObject
            )
            NotificationCenter.getInstance(account).postNotificationName(
                NotificationCenter.updateTranscriptionLock
            )
        }
    }

    private fun showError(msg: String) {
        BulletinFactory.global().createSimpleBulletin(
            R.raw.error,
            LocaleController.formatString(R.string.InuAiTranscribeFailed, msg)
        ).show()
    }

    // ------------------------------------------------------------------ Providers

    private fun transcribeGroq(file: File, fileName: String, mime: String, prompt: String): String {
        val apiKey = InuConfig.AI_PROVIDER_GROQ_KEY.value.trim()
        val url = "https://api.groq.com/openai/v1/audio/transcriptions"
        val parts = mutableMapOf(
            "model" to InuConfig.AI_TRANSCRIBE_GROQ_MODEL.value.trim().ifBlank { "whisper-large-v3-turbo" },
            "response_format" to "json",
            "temperature" to "0"
        )
        if (prompt.isNotBlank()) parts["prompt"] = prompt
        languageHint().takeIf { it.isNotBlank() }?.let { parts["language"] = it }

        val headers = mapOf("Authorization" to "Bearer $apiKey")
        val resp = postMultipart(url, headers, parts, "file", fileName, mime, file)
        val json = JSONObject(resp)
        if (json.has("error")) {
            throw IOException(json.getJSONObject("error").optString("message", "Groq error"))
        }
        return json.optString("text", "").trim()
    }

    private fun transcribeOpenAI(file: File, fileName: String, mime: String, prompt: String): String {
        val apiKey = InuConfig.AI_PROVIDER_OPENAI_KEY.value.trim()
        val url = "https://api.openai.com/v1/audio/transcriptions"
        val parts = mutableMapOf(
            "model" to InuConfig.AI_TRANSCRIBE_OPENAI_MODEL.value.trim().ifBlank { "whisper-1" },
            "response_format" to "json",
            "temperature" to "0"
        )
        if (prompt.isNotBlank()) parts["prompt"] = prompt
        languageHint().takeIf { it.isNotBlank() }?.let { parts["language"] = it }

        val headers = mapOf("Authorization" to "Bearer $apiKey")
        val resp = postMultipart(url, headers, parts, "file", fileName, mime, file)
        val json = JSONObject(resp)
        if (json.has("error")) {
            throw IOException(json.getJSONObject("error").optString("message", "OpenAI error"))
        }
        return json.optString("text", "").trim()
    }

    private fun transcribeCustom(file: File, fileName: String, mime: String, prompt: String): String {
        var rawUrl = InuConfig.AI_TRANSCRIBE_CUSTOM_URL.value.trim()
        if (rawUrl.isEmpty()) throw IOException("Custom endpoint URL is empty")
        if (!rawUrl.endsWith("/audio/transcriptions")) {
            rawUrl = rawUrl.trimEnd('/') + "/audio/transcriptions"
        }
        val apiKey = InuConfig.AI_TRANSCRIBE_CUSTOM_KEY.value.trim()
        val model = InuConfig.AI_TRANSCRIBE_CUSTOM_MODEL.value.trim().ifBlank { "whisper-1" }
        val parts = mutableMapOf(
            "model" to model,
            "response_format" to "json",
            "temperature" to "0"
        )
        if (prompt.isNotBlank()) parts["prompt"] = prompt
        languageHint().takeIf { it.isNotBlank() }?.let { parts["language"] = it }

        val headers = mutableMapOf<String, String>()
        if (apiKey.isNotBlank()) headers["Authorization"] = "Bearer $apiKey"

        val resp = postMultipart(rawUrl, headers, parts, "file", fileName, mime, file)
        val json = JSONObject(resp)
        if (json.has("error")) {
            throw IOException(json.getJSONObject("error").optString("message", "Custom API error"))
        }
        return json.optString("text", "").trim()
    }

    private fun transcribeGemini(file: File, mime: String, prompt: String): String {
        if (file.length() > GEMINI_MAX_INLINE_BYTES) {
            throw IOException(LocaleController.getString(R.string.InuAiTranscribeErrorTooLargeInline))
        }
        val apiKey = InuConfig.AI_PROVIDER_GEMINI_KEY.value.trim()
        val model = InuConfig.AI_TRANSCRIBE_GEMINI_MODEL.value.trim().ifBlank { "gemini-3.5-flash" }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val instruction = buildString {
            append(
                if (prompt.isNotBlank()) prompt
                else "Transcribe the audio verbatim in its original language. Output ONLY the transcription text without speaker labels, introductions, or commentary."
            )
            languageHint().takeIf { it.isNotBlank() }?.let {
                append(" The audio is spoken in \"")
                append(it)
                append("\"; transcribe it in that language.")
            }
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            // Without a streaming mode HttpURLConnection buffers the entire body in memory before
            // sending - and this body is the audio Base64'd, so it is the single largest allocation
            // the fork makes. Chunked keeps it to one buffer at a time.
            setChunkedStreamingMode(0)
            setRequestProperty("Content-Type", "application/json")
        }

        // The JSON is assembled around the payload rather than built as one object: the alternative
        // materialises the Base64 string, then the JSONObject copy of it, then the byte array of
        // the whole document - three copies of an already-inflated file.
        val head = "{\"contents\":[{\"parts\":[" +
            JSONObject().put("text", instruction).toString() +
            ",{\"inlineData\":{\"mimeType\":" + JSONObject.quote(mime) + ",\"data\":\""
        val tail = "\"}}]}],\"generationConfig\":{\"temperature\":0}}"

        conn.outputStream.buffered().use { out ->
            out.write(head.toByteArray())
            streamBase64(file, out)
            out.write(tail.toByteArray())
        }

        val code = conn.responseCode
        val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()

        if (code !in 200..299) {
            val errJson = try { JSONObject(resp).getJSONObject("error").optString("message", resp) } catch (_: Exception) { resp }
            val message = "Gemini API error ($code): ${errJson.take(300)}"
            throw if (code == 429 || code >= 500) TransientHttpException(message) else IOException(message)
        }

        val parts = JSONObject(resp)
            .optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
            ?: return ""
        // Long answers arrive split across several parts; taking only the first truncated them.
        return buildString {
            for (i in 0 until parts.length()) {
                append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }
        }.trim()
    }

    /**
     * Base64-encodes [file] straight into [out]. Every chunk but the last is a multiple of three
     * bytes on purpose: Base64 of concatenated chunks only equals Base64 of the whole file when
     * each chunk is 3-byte aligned, otherwise padding lands in the middle of the stream and the
     * provider decodes garbage.
     */
    private fun streamBase64(file: File, out: OutputStream) {
        val buf = ByteArray(3 * 16 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read <= 0) break
                out.write(Base64.encode(if (read == buf.size) buf else buf.copyOf(read), Base64.NO_WRAP))
                if (read < buf.size) break
            }
        }
    }

    private fun transcribeCloudflare(file: File, prompt: String): String {
        val accountId = InuConfig.AI_TRANSCRIBE_CF_ACCOUNT_ID.value.trim()
        val apiToken = InuConfig.AI_TRANSCRIBE_CF_API_TOKEN.value.trim()
        if (accountId.isEmpty() || apiToken.isEmpty()) {
            throw IOException("Cloudflare Account ID or API Token missing")
        }
        val model = InuConfig.AI_TRANSCRIBE_CF_MODEL.value.trim().ifBlank { "@cf/openai/whisper" }
        val url = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/$model"

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setChunkedStreamingMode(0)
            setRequestProperty("Authorization", "Bearer $apiToken")
            setRequestProperty("Content-Type", "application/octet-stream")
        }
        FileInputStream(file).use { input ->
            conn.outputStream.use { output -> input.copyTo(output) }
        }
        val code = conn.responseCode
        val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()

        if (code !in 200..299) {
            val message = "Cloudflare error ($code): ${resp.take(300)}"
            throw if (code == 429 || code >= 500) TransientHttpException(message) else IOException(message)
        }

        val json = JSONObject(resp)
        if (json.optBoolean("success", false)) {
            val result = json.optJSONObject("result")
            return result?.optString("text", "")?.trim() ?: ""
        }
        val errors = json.optJSONArray("errors")
        val errMsg = if (errors != null && errors.length() > 0) errors.getJSONObject(0).optString("message", "CF error") else "CF error"
        throw IOException(errMsg)
    }

    // ------------------------------------------------------------------ Multipart HTTP

    private fun postMultipart(
        urlStr: String,
        headers: Map<String, String>,
        parts: Map<String, String>,
        fileField: String,
        fileName: String,
        fileMime: String,
        file: File
    ): String {
        val boundary = "Boundary-" + UUID.randomUUID().toString()
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            // Buffering the whole multipart body (audio included) in memory is what made large
            // voice messages an OOM risk rather than a slow request.
            setChunkedStreamingMode(0)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            for ((k, v) in headers) setRequestProperty(k, v)
        }

        conn.outputStream.buffered().use { out ->
            for ((k, v) in parts) {
                out.write(("$twoHyphens$boundary$lineEnd").toByteArray())
                out.write(("Content-Disposition: form-data; name=\"$k\"$lineEnd$lineEnd").toByteArray())
                out.write(("$v$lineEnd").toByteArray())
            }
            out.write(("$twoHyphens$boundary$lineEnd").toByteArray())
            out.write(("Content-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"$lineEnd").toByteArray())
            out.write(("Content-Type: $fileMime$lineEnd$lineEnd").toByteArray())
            FileInputStream(file).use { input -> input.copyTo(out) }
            out.write(lineEnd.toByteArray())
            out.write(("$twoHyphens$boundary$twoHyphens$lineEnd").toByteArray())
        }

        val code = conn.responseCode
        val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()

        if (code !in 200..299) {
            val err = try {
                val j = JSONObject(resp)
                if (j.has("error")) j.getJSONObject("error").optString("message", resp) else resp
            } catch (_: Exception) {
                resp
            }
            val message = "HTTP $code: ${err.take(300)}"
            throw if (code == 429 || code >= 500) TransientHttpException(message) else IOException(message)
        }
        return resp
    }
}
