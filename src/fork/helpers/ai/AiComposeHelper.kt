package desu.inugram.helpers.ai

import android.content.Context
import desu.inugram.InuConfig
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.tgnet.RequestDelegate
import org.telegram.tgnet.RequestDelegateTimestamp
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_aicompose
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object AiComposeHelper {

    // entiny: accepts host, /v1 or a full /chat/completions URL
    @JvmStatic
    fun normalizeBase(raw: String): String {
        var url = raw.trim().trimEnd('/').removeSuffix("/chat/completions").trimEnd('/')
        if (url.isEmpty()) return ""
        if (!url.contains("://")) url = "https://$url"
        // entiny: a bare host is never the API root on OpenAI-compatible routers
        if (runCatching { URL(url).path.isEmpty() }.getOrDefault(false)) url += "/v1"
        return url
    }

    @JvmStatic
    fun completionsUrl(base: String): String = normalizeBase(base).let { if (it.isEmpty()) "" else "$it/chat/completions" }

    // entiny: plain http is allowed only for loopback runtimes unless the user opts in
    @JvmStatic
    fun isEndpointSecure(url: String): Boolean = runCatching {
        val u = URL(url)
        if (!u.protocol.equals("http", ignoreCase = true)) return@runCatching true
        if (InuConfig.AI_ALLOW_INSECURE.value) return@runCatching true
        val h = u.host.lowercase()
        h == "localhost" || h == "::1" || h == "[::1]" || h.startsWith("127.")
    }.getOrDefault(false)

    enum class Feature { EDITOR, SUMMARY }

    fun providerFor(feature: Feature): AiProviderStore.Provider? = when (feature) {
        Feature.EDITOR -> AiProviderStore.featureProvider(InuConfig.AI_EDITOR_PROVIDER_ID.value, InuConfig.AI_EDITOR_MODEL.value)
        Feature.SUMMARY -> AiProviderStore.featureProvider(InuConfig.AI_SUMMARY_PROVIDER_ID.value, InuConfig.AI_SUMMARY_MODEL.value)
    }

    @JvmStatic
    fun endpointFor(feature: Feature): AiEndpoint? {
        val p = providerFor(feature) ?: return null
        val url = completionsUrl(AiProviderStore.baseUrl(p))
        if (url.isBlank() || (AiProviderStore.needsKey(p.kind) && p.key.isBlank())) return null
        return AiEndpoint(id = p.id, name = p.name, url = url, apiKey = p.key, model = p.chatModel)
    }

    @JvmStatic
    fun activeEndpoint(): AiEndpoint? = endpointFor(Feature.EDITOR)

    @JvmStatic
    fun useOwnEditor(): Boolean = endpointFor(Feature.EDITOR) != null

    fun temperatureFor(feature: Feature): Float = when (feature) {
        Feature.EDITOR -> InuConfig.AI_TEMPERATURE.value
        Feature.SUMMARY -> InuConfig.AI_SUMMARY_TEMPERATURE.value.takeIf { it >= 0f } ?: InuConfig.AI_TEMPERATURE.value
    }

    @JvmStatic
    fun host(url: String): String = try {
        URL(url).host ?: ""
    } catch (_: Exception) {
        ""
    }

    @JvmStatic
    fun showEditor(context: Context, text: CharSequence, onUse: Utilities.Callback<CharSequence>) {
        if (showProviderSetup(context, providerFor(Feature.EDITOR))) return
        org.telegram.ui.Components.AIEditorAlert(context, null)
            .setText(text)
            .setOnUse(onUse)
            .show()
    }

    fun showProviderSetup(context: Context, provider: AiProviderStore.Provider?): Boolean {
        if (provider == null || AiProviderStore.isReady(provider)) return false
        org.telegram.ui.ActionBar.AlertDialog.Builder(context)
            .setTitle(provider.name)
            .setMessage(desu.inugram.ui.settings.AiFeatureSupport.providerName(provider))
            .setPositiveButton(LocaleController.getString(R.string.InuAiOpenSettings)) { _, _ ->
                org.telegram.ui.LaunchActivity.instance?.presentFragment(desu.inugram.ui.settings.AiProviderEditActivity.forExisting(provider.id))
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null as org.telegram.ui.ActionBar.AlertDialog.OnButtonClickListener?)
            .show()
        return true
    }

    private fun withRole(systemPrompt: String): String {
        val role = AiRolesHelper.activeRole()
        val customPrompt = role?.prompt?.trim().orEmpty()
        val name = role?.text?.trim().orEmpty()
        return when {
            customPrompt.isNotEmpty() -> "$customPrompt $systemPrompt"
            name.isNotEmpty() -> "You are $name. $systemPrompt"
            else -> systemPrompt
        }
    }

    // entiny: some routers return content as a list of {type,text} parts
    private fun messageText(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> (0 until content.length()).joinToString("") { content.optJSONObject(it)?.optString("text", "").orEmpty() }
        else -> ""
    }

    @JvmStatic
    fun request(
        endpoint: AiEndpoint,
        systemPrompt: String,
        userText: String,
        answerOnly: Boolean = InuConfig.AI_ONLY_ANSWER.value,
        temperature: Float = InuConfig.AI_TEMPERATURE.value,
        onResult: (result: String?, error: String?) -> Unit,
    ) {
        Utilities.globalQueue.postRunnable {
            var result: String? = null
            var error: String? = null
            try {
                if (!isEndpointSecure(endpoint.url)) throw IOException("plain HTTP is not allowed: ${host(endpoint.url)}")
                val json = JSONObject()
                    .put("model", endpoint.model.ifBlank { "gpt-4o-mini" })
                    .put(
                        "messages",
                        JSONArray()
                            .put(JSONObject().put("role", "system").put("content", withRole(systemPrompt)))
                            .put(JSONObject().put("role", "user").put("content", userText))
                    )

                if (temperature != 1.0f) {
                    json.put("temperature", temperature.toDouble())
                }

                if (InuConfig.AI_REASONING_ENABLED.value) {
                    val effort = InuConfig.AI_REASONING_EFFORT.value
                    json.put("reasoning_effort", effort)
                    if (endpoint.url.contains("openrouter")) {
                        json.put("include_reasoning", true)
                    }
                }

                val body = json.toString()
                val conn = (URL(endpoint.url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    if (endpoint.apiKey.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer ${endpoint.apiKey}")
                    }
                }
                val resp = try {
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val body2 = (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    if (code !in 200..299) throw IOException("HTTP $code: ${body2.take(300)}")
                    body2
                } finally {
                    conn.disconnect()
                }
                val msgObj = try {
                    JSONObject(resp).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                } catch (_: Exception) {
                    throw IOException("Unexpected response: ${resp.take(200)}")
                }

                val content = messageText(msgObj.opt("content")).trim()
                val reasoning = msgObj.optString("reasoning_content", "").ifBlank { msgObj.optString("reasoning", "") }.trim()

                result = if (answerOnly) {
                    content.ifBlank { reasoning }
                } else if (reasoning.isNotBlank() && content.isNotBlank()) {
                    "💭 $reasoning\n\n$content"
                } else {
                    content.ifBlank { reasoning }
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            val r = result
            val err = error
            AndroidUtilities.runOnUIThread { onResult(r, err) }
        }
    }

    private const val TRANSLATE_PROMPT = "Translate the following text into %s, preserving meaning and tone. Return only the translated text."
    private const val FIX_PROMPT = "Fix grammar, spelling and punctuation in the following text. Do not change the meaning. Return only the corrected text."
    private const val EMOJIFY_SUFFIX = " Additionally, weave relevant emojis into the text where appropriate."
    private const val STYLE_PROMPT = "Rewrite the following text in the style \"%s\". Return only the rewritten text."

    private val STYLE_PROMPTS = mapOf(
        "biblical" to "Rewrite in a biblical, archaic and solemn style using thee/thou language and scripture-like prose. Return only the rewritten text.",
        "corp" to "Rewrite in a professional corporate style — formal, concise and business-appropriate. Return only the rewritten text.",
        "zen" to "Rewrite in a calm, minimalist zen style — peaceful, contemplative, with short serene sentences. Return only the rewritten text.",
        "viking" to "Rewrite in a bold Norse warrior style — heroic, dramatic and battle-ready. Return only the rewritten text.",
        "short" to "Rewrite as an extremely short, punchy version keeping only the core message. Return only the rewritten text.",
    )

    private class ComposeEntry {
        val cancelled = AtomicBoolean(false)
    }

    private val HTTP_STATUS = Regex("HTTP (\\d{3})")

    private val composeRequests = ConcurrentHashMap<Long, ComposeEntry>()

    /** answers the stock AI editor request with the user's own endpoint, false lets Telegram handle it */
    @JvmStatic
    fun maybeCompose(account: Int, token: Int, tlRequest: TLObject, onComplete: RequestDelegate?, onCompleteTimestamp: RequestDelegateTimestamp?): Boolean {
        if (tlRequest !is TLRPC.TL_messages_composeMessageWithAI) return false
        val endpoint = activeEndpoint() ?: return false
        val entry = ComposeEntry()
        val key = (account.toLong() shl 32) or (token.toLong() and 0xffffffffL)
        composeRequests[key] = entry

        fun respond(response: TLRPC.TL_composedMessageWithAI?, error: TLRPC.TL_error?) {
            Utilities.stageQueue.postRunnable {
                if (composeRequests.remove(key) !== entry || entry.cancelled.get()) return@postRunnable
                onComplete?.run(response, error)
                onCompleteTimestamp?.run(response, error, 0)
            }
        }

        request(endpoint, composePrompt(tlRequest), tlRequest.text?.text ?: "") { result, error ->
            if (entry.cancelled.get() || composeRequests[key] !== entry) return@request
            if (result.isNullOrEmpty()) {
                val err = error.orEmpty()
                val status = HTTP_STATUS.find(err)?.groupValues?.get(1)
                val e = TLRPC.TL_error()
                e.code = status?.toIntOrNull() ?: 400
                e.text = "AI editor: " + when {
                    status == "429" || err.contains("quota", ignoreCase = true) -> "quota exceeded (HTTP 429)"
                    status == "401" || status == "403" -> "access denied (HTTP $status)"
                    status != null -> "endpoint error (HTTP $status)"
                    else -> err.take(80).ifBlank { "unknown error" }
                }
                respond(null, e)
            } else {
                val res = TLRPC.TL_composedMessageWithAI()
                res.result_text = TLRPC.TL_textWithEntities().apply { text = result }
                respond(res, null)
            }
        }
        return true
    }

    /** stageQueue, from `ConnectionsManager.cancelRequest`. False for a token this did not answer */
    @JvmStatic
    fun cancelCompose(account: Int, token: Int): Boolean {
        val key = (account.toLong() shl 32) or (token.toLong() and 0xffffffffL)
        val entry = composeRequests.remove(key) ?: return false
        entry.cancelled.set(true)
        return true
    }

    private fun composePrompt(req: TLRPC.TL_messages_composeMessageWithAI): String {
        val base = when {
            req.translate_to_lang != null -> TRANSLATE_PROMPT.format(translateLangName(req.translate_to_lang))
            req.proofread -> FIX_PROMPT
            req.tone != null -> tonePrompt(req.tone)
            else -> ""
        }
        return if (req.emojify) base + EMOJIFY_SUFFIX else base
    }

    private fun translateLangName(code: String): String =
        org.telegram.ui.Components.TranslateAlert2.languageName(code) ?: code

    private fun tonePrompt(tone: TL_aicompose.InputAiComposeTone): String = when (tone) {
        is TL_aicompose.inputAiComposeToneSingleUse -> tone.custom_prompt.orEmpty().trim()
        is TL_aicompose.TL_aiComposeToneDefault -> knownStyle(tone.tone) ?: STYLE_PROMPT.format(tone.title ?: tone.tone)
        is TL_aicompose.TL_aiComposeTone -> tone.prompt?.trim()?.takeIf { it.isNotEmpty() }
            ?: knownStyle(tone.slug) ?: STYLE_PROMPT.format(tone.title ?: tone.slug ?: "")
        else -> ""
    }

    private fun knownStyle(slug: String?): String? {
        if (slug == null) return null
        val key = slug.trim().lowercase()
        return STYLE_PROMPTS[key] ?: STYLE_PROMPTS.entries.firstOrNull { key.contains(it.key) }?.value
    }
}
