package desu.inugram.helpers.ai

import desu.inugram.InuConfig
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.Utilities
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object AiModelsHelper {

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

    @JvmStatic
    fun fetchProviderModels(p: AiProviderStore.Provider, voice: Boolean, callback: (Result<List<String>>) -> Unit) {
        when (p.kind) {
            InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> fetchGeminiModels(p.key, callback)
            InuConfig.TRANSCRIBE_PROVIDER_CF -> callback(Result.success(emptyList()))
            else -> {
                val whisperOnly = voice && (p.kind == InuConfig.TRANSCRIBE_PROVIDER_OPENAI || p.kind == InuConfig.TRANSCRIBE_PROVIDER_GROQ)
                fetchOpenAiCompatModels(AiProviderStore.baseUrl(p), p.key, if (whisperOnly) "whisper" else null, callback)
            }
        }
    }

    // entiny: probes common API roots and returns the first one whose /models answers like an OpenAI-compatible server
    @JvmStatic
    fun detectEndpoint(rawUrl: String, apiKey: String, callback: (Result<String>) -> Unit) {
        Utilities.globalQueue.postRunnable {
            val result = runCatching {
                var root = rawUrl.trim().trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/models").trimEnd('/')
                if (root.isEmpty()) throw IOException("empty url")
                if (!root.contains("://")) root = "https://$root"
                val found = linkedSetOf(root, "$root/v1", "$root/openai/v1", "$root/api/v1").firstOrNull { probeModels(it, apiKey) }
                found ?: throw IOException("no endpoint answered")
            }
            AndroidUtilities.runOnUIThread { callback(result) }
        }
    }

    private fun probeModels(base: String, apiKey: String): Boolean = runCatching {
        val conn = (URL("$base/models").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        val code = conn.responseCode
        val resp = if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else ""
        conn.disconnect()
        code in 200..299 && JSONObject(resp).let { it.optJSONArray("data") != null || it.optJSONArray("models") != null }
    }.getOrDefault(false)
}
