package desu.inugram.helpers.chat

import android.text.TextUtils
import android.util.Base64
import desu.inugram.InuConfig
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.TranscribeButton
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object TranscribeHelper {

    private val inFlight = ConcurrentHashMap<String, Boolean>()

    @JvmStatic
    fun isTranscribing(messageObject: MessageObject?): Boolean {
        if (messageObject == null) return false
        val key = reqKey(messageObject)
        return inFlight[key] == true
    }

    @JvmStatic
    fun shouldUseCustomTranscribe(account: Int): Boolean {
        if (!InuConfig.AI_TRANSCRIBE_ENABLED.value) return false
        val hasPremium = UserConfig.getInstance(account).isPremium
        return !hasPremium
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

        val provider = InuConfig.AI_TRANSCRIBE_PROVIDER.value
        val apiKey = when (provider) {
            InuConfig.TRANSCRIBE_PROVIDER_GROQ -> InuConfig.AI_TRANSCRIBE_GROQ_KEY.value
            InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> InuConfig.AI_TRANSCRIBE_GEMINI_KEY.value
            InuConfig.TRANSCRIBE_PROVIDER_OPENAI -> InuConfig.AI_TRANSCRIBE_OPENAI_KEY.value
            InuConfig.TRANSCRIBE_PROVIDER_CF -> InuConfig.AI_TRANSCRIBE_CF_API_TOKEN.value
            InuConfig.TRANSCRIBE_PROVIDER_CUSTOM -> InuConfig.AI_TRANSCRIBE_CUSTOM_KEY.value
            else -> ""
        }.trim()

        if (apiKey.isEmpty() && provider != InuConfig.TRANSCRIBE_PROVIDER_CUSTOM) {
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
                showError("No audio document found")
            }
        }
    }

    private fun pollFileDownload(account: Int, messageObject: MessageObject, doc: TLRPC.Document, attempts: Int = 0) {
        if (attempts > 60) {
            val key = reqKey(messageObject)
            inFlight.remove(key)
            notifyStateChange(account, messageObject)
            showError("Download timeout")
            return
        }

        Utilities.globalQueue.postRunnable({
            val file = FileLoader.getInstance(account).getPathToAttach(doc, true)
            if (file != null && file.exists() && file.length() > 0) {
                processAudioFile(account, messageObject, file)
            } else {
                AndroidUtilities.runOnUIThread({
                    pollFileDownload(account, messageObject, doc, attempts + 1)
                }, 500)
            }
        }, 500)
    }

    private fun processAudioFile(account: Int, messageObject: MessageObject, file: File) {
        val key = reqKey(messageObject)
        Utilities.globalQueue.postRunnable {
            try {
                val isRound = messageObject.isRoundVideo
                val bytes = file.readBytes()
                val mime = if (isRound) "video/mp4" else "audio/ogg"
                val fileName = if (isRound) "video.mp4" else "voice.ogg"

                val provider = InuConfig.AI_TRANSCRIBE_PROVIDER.value
                val customPrompt = InuConfig.AI_TRANSCRIBE_PROMPT.value.trim()

                val transcribedText = when (provider) {
                    InuConfig.TRANSCRIBE_PROVIDER_GROQ -> transcribeGroq(bytes, fileName, mime, customPrompt)
                    InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> transcribeGemini(bytes, mime, customPrompt)
                    InuConfig.TRANSCRIBE_PROVIDER_OPENAI -> transcribeOpenAI(bytes, fileName, mime, customPrompt)
                    InuConfig.TRANSCRIBE_PROVIDER_CF -> transcribeCloudflare(bytes, customPrompt)
                    InuConfig.TRANSCRIBE_PROVIDER_CUSTOM -> transcribeCustom(bytes, fileName, mime, customPrompt)
                    else -> throw IllegalStateException("Unknown provider: $provider")
                }

                AndroidUtilities.runOnUIThread {
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
                        showError("Empty transcription received")
                    }
                }
            } catch (e: Exception) {
                FileLog.e("TranscribeHelper error", e)
                AndroidUtilities.runOnUIThread {
                    inFlight.remove(key)
                    notifyStateChange(account, messageObject)
                    showError(e.message ?: "Transcription error")
                }
            }
        }
    }

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

    private fun transcribeGroq(bytes: ByteArray, fileName: String, mime: String, prompt: String): String {
        val apiKey = InuConfig.AI_TRANSCRIBE_GROQ_KEY.value.trim()
        val url = "https://api.groq.com/openai/v1/audio/transcriptions"
        val parts = mutableMapOf(
            "model" to "whisper-large-v3-turbo",
            "response_format" to "json",
            "temperature" to "0"
        )
        if (prompt.isNotBlank()) parts["prompt"] = prompt

        val headers = mapOf("Authorization" to "Bearer $apiKey")
        val resp = postMultipart(url, headers, parts, "file", fileName, mime, bytes)
        val json = JSONObject(resp)
        if (json.has("error")) {
            throw IOException(json.getJSONObject("error").optString("message", "Groq error"))
        }
        return json.optString("text", "").trim()
    }

    private fun transcribeOpenAI(bytes: ByteArray, fileName: String, mime: String, prompt: String): String {
        val apiKey = InuConfig.AI_TRANSCRIBE_OPENAI_KEY.value.trim()
        val url = "https://api.openai.com/v1/audio/transcriptions"
        val parts = mutableMapOf(
            "model" to "whisper-1",
            "response_format" to "json",
            "temperature" to "0"
        )
        if (prompt.isNotBlank()) parts["prompt"] = prompt

        val headers = mapOf("Authorization" to "Bearer $apiKey")
        val resp = postMultipart(url, headers, parts, "file", fileName, mime, bytes)
        val json = JSONObject(resp)
        if (json.has("error")) {
            throw IOException(json.getJSONObject("error").optString("message", "OpenAI error"))
        }
        return json.optString("text", "").trim()
    }

    private fun transcribeCustom(bytes: ByteArray, fileName: String, mime: String, prompt: String): String {
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

        val headers = mutableMapOf<String, String>()
        if (apiKey.isNotBlank()) headers["Authorization"] = "Bearer $apiKey"

        val resp = postMultipart(rawUrl, headers, parts, "file", fileName, mime, bytes)
        val json = JSONObject(resp)
        if (json.has("error")) {
            throw IOException(json.getJSONObject("error").optString("message", "Custom API error"))
        }
        return json.optString("text", "").trim()
    }

    private fun transcribeGemini(bytes: ByteArray, mime: String, prompt: String): String {
        val apiKey = InuConfig.AI_TRANSCRIBE_GEMINI_KEY.value.trim()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        val sysInstruction = if (prompt.isNotBlank()) prompt else "Transcribe the audio verbatim in its original language. Output ONLY the transcription text without speaker labels, introductions, or commentary."

        val json = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().apply {
                            put(JSONObject().put("text", sysInstruction))
                            put(
                                JSONObject().put(
                                    "inlineData",
                                    JSONObject().put("mimeType", mime).put("data", base64)
                                )
                            )
                        }
                    )
                )
            )
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(json.toString().toByteArray()) }
        val code = conn.responseCode
        val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()

        if (code !in 200..299) {
            val errJson = try { JSONObject(resp).getJSONObject("error").optString("message", resp) } catch (_: Exception) { resp }
            throw IOException("Gemini API error ($code): ${errJson.take(300)}")
        }

        val resObj = JSONObject(resp)
        val candidates = resObj.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
            val cand = candidates.getJSONObject(0)
            val content = cand.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            if (parts != null && parts.length() > 0) {
                return parts.getJSONObject(0).optString("text", "").trim()
            }
        }
        return ""
    }

    private fun transcribeCloudflare(bytes: ByteArray, prompt: String): String {
        val accountId = InuConfig.AI_TRANSCRIBE_CF_ACCOUNT_ID.value.trim()
        val apiToken = InuConfig.AI_TRANSCRIBE_CF_API_TOKEN.value.trim()
        if (accountId.isEmpty() || apiToken.isEmpty()) {
            throw IOException("Cloudflare Account ID or API Token missing")
        }
        val url = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/@cf/openai/whisper"

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiToken")
            setRequestProperty("Content-Type", "application/octet-stream")
        }
        conn.outputStream.use { it.write(bytes) }
        val code = conn.responseCode
        val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()

        if (code !in 200..299) {
            throw IOException("Cloudflare error ($code): ${resp.take(300)}")
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
        fileBytes: ByteArray
    ): String {
        val boundary = "Boundary-" + UUID.randomUUID().toString()
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            for ((k, v) in headers) setRequestProperty(k, v)
        }

        conn.outputStream.use { out ->
            for ((k, v) in parts) {
                out.write(("$twoHyphens$boundary$lineEnd").toByteArray())
                out.write(("Content-Disposition: form-data; name=\"$k\"$lineEnd$lineEnd").toByteArray())
                out.write(("$v$lineEnd").toByteArray())
            }
            out.write(("$twoHyphens$boundary$lineEnd").toByteArray())
            out.write(("Content-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"$lineEnd").toByteArray())
            out.write(("Content-Type: $fileMime$lineEnd$lineEnd").toByteArray())
            out.write(fileBytes)
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
            throw IOException("HTTP $code: ${err.take(300)}")
        }
        return resp
    }
}
