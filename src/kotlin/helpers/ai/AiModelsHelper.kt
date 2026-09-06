package desu.inugram.helpers.ai

import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.Utilities
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the list of models a provider currently exposes, so settings screens can offer a
 * picker instead of asking the user to type a model id from memory.
 */
object AiModelsHelper {

    /**
     * Gemini's native REST API (not the OpenAI-compat layer) -- used by [ai_transcribe] since it
     * calls generateContent directly. Filters to models that actually support generateContent.
     */
    @JvmStatic
    fun fetchGeminiModels(apiKey: String, callback: (Result<List<String>>) -> Unit) {
        Utilities.globalQueue.postRunnable {
            val result = runCatching {
                val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey&pageSize=200"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                }
                val code = conn.responseCode
                val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()
                if (code !in 200..299) {
                    val msg = try { JSONObject(resp).getJSONObject("error").optString("message", resp) } catch (_: Exception) { resp }
                    throw IOException("Gemini API error ($code): ${msg.take(300)}")
                }
                val models = JSONObject(resp).optJSONArray("models") ?: return@runCatching emptyList()
                (0 until models.length()).mapNotNull { i ->
                    val m = models.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    val supportsGenerate = methods != null && (0 until methods.length()).any { methods.getString(it) == "generateContent" }
                    if (!supportsGenerate) return@mapNotNull null
                    m.optString("name", "").removePrefix("models/").ifBlank { null }
                }.sorted()
            }
            AndroidUtilities.runOnUIThread { callback(result) }
        }
    }

    /**
     * Cloudflare Workers AI's own model catalog search (not OpenAI-compatible). Filters to
     * speech-recognition models so the picker doesn't also list every chat/image/embedding model
     * on the account.
     */
    @JvmStatic
    fun fetchCloudflareModels(accountId: String, apiToken: String, callback: (Result<List<String>>) -> Unit) {
        Utilities.globalQueue.postRunnable {
            val result = runCatching {
                val url = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/models/search?task=Automatic+Speech+Recognition&per_page=100"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Authorization", "Bearer $apiToken")
                }
                val code = conn.responseCode
                val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()
                if (code !in 200..299) {
                    throw IOException("Cloudflare API error ($code): ${resp.take(300)}")
                }
                val body = JSONObject(resp)
                if (!body.optBoolean("success", false)) {
                    val errors = body.optJSONArray("errors")
                    val msg = if (errors != null && errors.length() > 0) errors.getJSONObject(0).optString("message", "Cloudflare error") else "Cloudflare error"
                    throw IOException(msg)
                }
                val models = body.optJSONArray("result") ?: return@runCatching emptyList()
                (0 until models.length()).mapNotNull { i -> models.getJSONObject(i).optString("name", "").ifBlank { null } }
                    .filter { it.contains("whisper", ignoreCase = true) }
                    .sorted()
            }
            AndroidUtilities.runOnUIThread { callback(result) }
        }
    }

    /**
     * Any OpenAI-compatible `/v1/models` endpoint (OpenAI, Groq, OpenRouter, Gemini's own
     * OpenAI-compat layer, and user-provided Custom endpoints used by AI Compose). Optionally
     * filters ids by a substring, since e.g. Groq and OpenAI's `/models` also list every chat
     * model on the account, not just the transcription-capable ones.
     */
    @JvmStatic
    @JvmOverloads
    fun fetchOpenAiCompatModels(baseUrl: String, apiKey: String, filterSubstring: String? = null, callback: (Result<List<String>>) -> Unit) {
        Utilities.globalQueue.postRunnable {
            val result = runCatching {
                val normalized = baseUrl.trimEnd('/')
                val url = if (normalized.endsWith("/models")) normalized else "$normalized/models"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
                }
                val code = conn.responseCode
                val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()
                if (code !in 200..299) {
                    val msg = try { JSONObject(resp).getJSONObject("error").optString("message", resp) } catch (_: Exception) { resp }
                    throw IOException("Models API error ($code): ${msg.take(300)}")
                }
                val data = JSONObject(resp).optJSONArray("data") ?: return@runCatching emptyList()
                (0 until data.length()).mapNotNull { i ->
                    data.getJSONObject(i).optString("id", "").removePrefix("models/").ifBlank { null }
                }
                    .filter { filterSubstring == null || it.contains(filterSubstring, ignoreCase = true) }
                    .sorted()
            }
            AndroidUtilities.runOnUIThread {
                if (result.isFailure) FileLog.e("AiModelsHelper fetch error", result.exceptionOrNull())
                callback(result)
            }
        }
    }
}
