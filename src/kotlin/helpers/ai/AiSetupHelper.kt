package desu.inugram.helpers.ai

import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.Utilities
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Helpers for the AI provider setup wizard.
 * Knows about built-in providers and handles the model-list fetch.
 */
object AiSetupHelper {

    // ---- Known providers ----

    data class Provider(
        val id: String,
        val displayName: String,
        val endpointUrl: String,
        /** null = provider uses key in URL param, not Bearer header */
        val authHeader: String? = "Bearer",
        /** How to fetch models for this provider. */
        val modelsUrl: (apiKey: String) -> String,
    )

    val GOOGLE = Provider(
        id = "google",
        displayName = "Google Gemini",
        endpointUrl = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        authHeader = null, // key goes as ?key= in modelsUrl; chat completions use Bearer separately
        modelsUrl = { key -> "https://generativelanguage.googleapis.com/v1beta/models?key=$key" },
    )

    val OPENAI = Provider(
        id = "openai",
        displayName = "OpenAI",
        endpointUrl = "https://api.openai.com/v1/chat/completions",
        authHeader = "Bearer",
        modelsUrl = { _ -> "https://api.openai.com/v1/models" },
    )

    val OPENROUTER = Provider(
        id = "openrouter",
        displayName = "OpenRouter",
        endpointUrl = "https://openrouter.ai/api/v1/chat/completions",
        authHeader = "Bearer",
        modelsUrl = { _ -> "https://openrouter.ai/api/v1/models" },
    )

    val PROVIDERS: List<Provider> = listOf(GOOGLE, OPENAI, OPENROUTER)

    // ---- Model fetch ----

    data class AiModel(
        val id: String,       // e.g. "gemini-2.5-flash", "gpt-4o", "anthropic/claude-3.7-sonnet"
        val displayName: String,
    )

    sealed class FetchResult {
        data class Success(val models: List<AiModel>) : FetchResult()
        data class Error(val message: String) : FetchResult()
    }

    /**
     * Fetches available chat-completion models for [provider] using [apiKey].
     * Runs on a background thread and posts [onResult] on the UI thread.
     */
    @JvmStatic
    fun fetchModels(
        provider: Provider,
        apiKey: String,
        onResult: (FetchResult) -> Unit,
    ) {
        Utilities.globalQueue.postRunnable {
            val result = try {
                val url = provider.modelsUrl(apiKey)
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    if (provider.authHeader != null && apiKey.isNotBlank()) {
                        setRequestProperty("Authorization", "${provider.authHeader} $apiKey")
                    }
                }
                try {
                    val code = conn.responseCode
                    val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() } ?: ""

                    if (code !in 200..299) {
                        val msg = try {
                            val errJson = JSONObject(body.trim().removePrefix("[").removeSuffix("]").trim())
                            errJson.optJSONObject("error")?.optString("message") ?: "HTTP $code"
                        } catch (_: Exception) {
                            "HTTP $code"
                        }
                        FetchResult.Error(msg)
                    } else {
                        val models = parseModels(provider, body)
                        if (models.isEmpty()) FetchResult.Error("No models found")
                        else FetchResult.Success(models)
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                FetchResult.Error(e.message ?: e.javaClass.simpleName)
            } catch (e: Exception) {
                FetchResult.Error(e.message ?: e.javaClass.simpleName)
            }

            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(result) }
        }
    }

    private fun parseModels(provider: Provider, body: String): List<AiModel> {
        return when (provider.id) {
            "google" -> parseGeminiModels(body)
            "openai" -> parseOpenAiModels(body)
            "openrouter" -> parseOpenRouterModels(body)
            else -> emptyList()
        }
    }

    private fun parseGeminiModels(body: String): List<AiModel> {
        val arr: JSONArray = try {
            JSONObject(body).getJSONArray("models")
        } catch (_: Exception) {
            return emptyList()
        }
        val result = mutableListOf<AiModel>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val methods = obj.optJSONArray("supportedGenerationMethods") ?: continue
            var supportsChat = false
            for (j in 0 until methods.length()) {
                if (methods.getString(j) == "generateContent") {
                    supportsChat = true
                    break
                }
            }
            if (!supportsChat) continue

            val fullName = obj.optString("name", "")
            val id = fullName.removePrefix("models/")
            if (id.isBlank()) continue

            val display = obj.optString("displayName", id)
            result.add(AiModel(id = id, displayName = display))
        }
        return result
    }

    private fun parseOpenAiModels(body: String): List<AiModel> {
        val arr: JSONArray = try {
            JSONObject(body).getJSONArray("data")
        } catch (_: Exception) {
            return emptyList()
        }
        val result = mutableListOf<AiModel>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val id = obj.optString("id", "")
            if (id.isBlank()) continue
            if (id.startsWith("gpt-") || id.startsWith("o1") || id.startsWith("o3") || id.startsWith("chatgpt")) {
                result.add(AiModel(id = id, displayName = id))
            }
        }
        result.sortBy { it.id }
        return result
    }

    private fun parseOpenRouterModels(body: String): List<AiModel> {
        val arr: JSONArray = try {
            JSONObject(body).getJSONArray("data")
        } catch (_: Exception) {
            return emptyList()
        }
        val result = mutableListOf<AiModel>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val id = obj.optString("id", "")
            if (id.isBlank()) continue
            val display = obj.optString("name", id)
            result.add(AiModel(id = id, displayName = display))
        }
        result.sortBy { it.displayName }
        return result
    }
}
