package desu.inugram.helpers.translate.engine

import android.util.Log
import desu.inugram.InuConfig
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.TranslateAlert2
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

interface TranslationProvider {

    val id: Int
    val nameRes: Int

    /** a provider that comes from a plugin has no string resource for its name */
    fun displayName(): String = LocaleController.getString(nameRes)

    /** what the choice is stored as when the provider comes from a plugin, null for the built-in ones */
    val pluginKey: String? get() = null

    fun isConfigured(): Boolean = true

    @Throws(Exception::class)
    fun translate(text: String, toLang: String): String

    @Throws(Exception::class)
    fun translate(text: String, toLang: String, context: List<String>): String = translate(text, toLang)
}

class ProviderRateLimitException(message: String) : IOException(message)

class ProviderConfigException(message: String) : IOException(message)

object TranslationProviders {

    const val PROVIDER_TELEGRAM = 0
    const val PROVIDER_GOOGLE = 1
    const val PROVIDER_DEEPL = 2
    const val PROVIDER_LLM = 3
    const val PROVIDER_MICROSOFT = 5
    const val PROVIDER_MYMEMORY = 6
    const val PROVIDER_BING = 9

    /** the choice is one of the plugin providers, named by `InuConfig.TRANSLATION_PROVIDER` */
    const val PROVIDER_PLUGIN = 100

    val all: List<TranslationProvider> = listOf(
        GoogleWebProvider,
        DeepLProvider,
        LlmProvider,
        BingProvider,
        MicrosoftProvider,
        MyMemoryProvider,
    )

    /** providers installed plugins registered with `inu.registerTranslationProvider` */
    fun plugins(): List<TranslationProvider> {
        // #if PLUGINS
        return desu.inugram.helpers.plugins.telegram.PluginTranslation.providers
            .filter { it.session.canDispatch() }
            .map { PluginTranslationProvider(it.key, it.name) }
        // #else
        return emptyList()
        // #endif
    }

    fun current(): TranslationProvider? = when (InuConfig.TRANSLATE_PROVIDER.value) {
        PROVIDER_GOOGLE -> GoogleWebProvider
        PROVIDER_DEEPL -> DeepLProvider
        PROVIDER_LLM -> LlmProvider
        // #if PLUGINS
        PROVIDER_PLUGIN -> plugins().firstOrNull { it.pluginKey == InuConfig.TRANSLATION_PROVIDER.value }
        // #endif
        PROVIDER_MICROSOFT -> MicrosoftProvider
        PROVIDER_MYMEMORY -> MyMemoryProvider
        PROVIDER_BING -> BingProvider
        else -> null
    }

}

private fun encodeURIComponent(s: String): String =
    URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%7E", "~")

// entiny: strips HTML style blocks from Google abuse block error response
private fun errorSnippet(text: String): String {
    val trimmed = text.trim()
    if (trimmed.startsWith("<")) return "blocked by the service (HTML error page)"
    return trimmed.take(200)
}

internal fun httpJson(
    url: String,
    method: String = "GET",
    body: String? = null,
    contentType: String? = null,
    headers: Map<String, String> = emptyMap(),
    connectTimeout: Int = 10_000,
    readTimeout: Int = 15_000,
): String {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        this.connectTimeout = connectTimeout
        this.readTimeout = readTimeout
        doOutput = body != null
        contentType?.let { setRequestProperty("Content-Type", it) }
        for ((k, v) in headers) setRequestProperty(k, v)
        if (body != null) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            setFixedLengthStreamingMode(bytes.size)
            outputStream.use { it.write(bytes) }
        }
    }
    val code = conn.responseCode
    val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
        ?.bufferedReader()?.use { it.readText() } ?: ""
    return when {
        code == 429 -> throw ProviderRateLimitException("HTTP 429: ${errorSnippet(text)}")
        code in 200..299 -> text
        code in 400..499 -> throw ProviderConfigException("HTTP $code: ${errorSnippet(text)}")
        else -> throw IOException("HTTP $code: ${errorSnippet(text)}")
    }
}

object GoogleWebProvider : TranslationProvider {

    override val id: Int = TranslationProviders.PROVIDER_GOOGLE
    override val nameRes: Int = R.string.InuTranslateProviderGoogle

    private const val TAG = "EntinyTranslate"
    private const val BLOCK_COOLDOWN_MS = 10 * 60 * 1000L
    private const val MAX_FALLBACK_CHARS = 1800
    private const val MAX_APP_CHARS = 3500
    private const val FAST_CONNECT_TIMEOUT_MS = 5_000
    private const val FAST_READ_TIMEOUT_MS = 7_000

    @Volatile
    private var blockedUntil = 0L

    // entiny: engine runs 8 workers; unthrottled bursts on chat open trip Google's 429 block
    private val gate = java.util.concurrent.Semaphore(2)

    override fun translate(text: String, toLang: String): String {
        gate.acquire()
        try {
            return translateGated(text, toLang)
        } finally {
            gate.release()
        }
    }

    private fun translateGated(text: String, toLang: String): String {
        val tl = normalizeToLang(toLang)
        if (System.currentTimeMillis() < blockedUntil) {
            return translateViaApp(text, tl)
                ?: translateViaDictionary(text, tl)
                ?: try {
                    translateViaGtx(text, tl)
                } catch (e: ProviderRateLimitException) {
                    throw ProviderRateLimitException(LocaleController.getString(R.string.InuTranslateGoogleBlocked))
                }
        }
        return try {
            translateViaGtx(text, tl)
        } catch (e: ProviderRateLimitException) {
            // entiny: only an actual detected block (HTTP 429) should trip the cooldown+fallback
            // chain - a plain IOException (offline, DNS, timeout) used to trip it too, which then
            // mislabeled every unrelated network error as "Google blocked this network"
            blockedUntil = System.currentTimeMillis() + BLOCK_COOLDOWN_MS
            translateViaApp(text, tl)
                ?: translateViaDictionary(text, tl)
                ?: throw ProviderRateLimitException(LocaleController.getString(R.string.InuTranslateGoogleBlocked))
        }
    }

    private fun translateViaGtx(text: String, tl: String): String {
        val body = "client=gtx&sl=auto&dt=t" +
            "&tl=" + encodeURIComponent(tl) +
            "&q=" + encodeURIComponent(text)
        val resp = httpJson(
            "https://translate.googleapis.com/translate_a/single",
            method = "POST",
            body = body,
            contentType = "application/x-www-form-urlencoded",
            headers = mapOf("User-Agent" to USER_AGENT),
            connectTimeout = FAST_CONNECT_TIMEOUT_MS,
            readTimeout = FAST_READ_TIMEOUT_MS,
        )
        val sentences = JSONArray(resp).optJSONArray(0) ?: JSONArray()
        val sb = StringBuilder(text.length)
        for (i in 0 until sentences.length()) {
            sb.append(sentences.getJSONArray(i).optString(0, ""))
        }
        if (sb.isEmpty()) throw IOException("Google Translate returned an empty result")
        return sb.toString()
    }

    private fun translateViaApp(text: String, tl: String): String? {
        if (text.length > MAX_APP_CHARS) return null
        return try {
            val resp = httpJson(
                "https://translate.google.com/translate_a/single?dj=1" +
                    "&sl=auto&tl=" + encodeURIComponent(tl) +
                    "&ie=UTF-8&oe=UTF-8&client=at&dt=t&otf=2" +
                    "&q=" + encodeURIComponent(text),
                headers = mapOf("User-Agent" to APP_USER_AGENT),
                connectTimeout = FAST_CONNECT_TIMEOUT_MS,
                readTimeout = FAST_READ_TIMEOUT_MS,
            )
            val sentences = JSONObject(resp).optJSONArray("sentences") ?: JSONArray()
            val sb = StringBuilder(text.length)
            for (i in 0 until sentences.length()) {
                sb.append(sentences.getJSONObject(i).optString("trans", ""))
            }
            sb.toString().ifBlank { null }
        } catch (e: Exception) {
            Log.d(TAG, "translateViaApp failed: ${e.message}")
            null
        }
    }

    private fun translateViaDictionary(text: String, tl: String): String? {
        if (text.length > MAX_FALLBACK_CHARS) return null
        return try {
            val resp = httpJson(
                "https://clients5.google.com/translate_a/t" +
                    "?client=dict-chrome-ex&sl=auto" +
                    "&tl=" + encodeURIComponent(tl) +
                    "&q=" + encodeURIComponent(text),
                headers = mapOf("User-Agent" to USER_AGENT),
                connectTimeout = FAST_CONNECT_TIMEOUT_MS,
                readTimeout = FAST_READ_TIMEOUT_MS,
            )
            val root = JSONArray(resp)
            val sb = StringBuilder(text.length)
            for (i in 0 until root.length()) {
                when (val item = root.opt(i)) {
                    is JSONArray -> sb.append(item.optString(0, ""))
                    is String -> sb.append(item)
                }
            }
            sb.toString().ifBlank { null }
        } catch (e: Exception) {
            Log.d(TAG, "translateViaDictionary failed: ${e.message}")
            null
        }
    }

    private fun normalizeToLang(code: String): String {
        if (code.equals("nb", ignoreCase = true)) return "no"
        val dash = code.indexOf('-')
        if (dash > 0) {
            return code.substring(0, dash).lowercase() + "-" + code.substring(dash + 1).uppercase()
        }
        return code.lowercase()
    }
}

// entiny: splits only on newlines/spaces so markers like <inue12> are never cut in half
internal fun splitForProvider(text: String, maxChars: Int): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    val cur = StringBuilder()
    fun flush(tail: String) {
        if (cur.isEmpty()) return
        out.add(cur.toString() to tail)
        cur.setLength(0)
    }
    for (line in text.split('\n')) {
        if (cur.isNotEmpty() && cur.length + 1 + line.length > maxChars) flush("\n")
        if (line.length <= maxChars) {
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(line)
            continue
        }
        flush("\n")
        var rest = line
        while (rest.length > maxChars) {
            var cut = rest.lastIndexOf(' ', maxChars)
            if (cut <= 0) cut = maxChars
            out.add(rest.substring(0, cut) to if (rest[cut] == ' ') " " else "")
            rest = rest.substring(cut).trimStart(' ')
        }
        cur.append(rest)
    }
    flush("")
    return out
}

private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"
private const val APP_USER_AGENT = "GoogleTranslate/6.14.0.04.343003216 (Linux; U; Android 10; Redmi K20 Pro)"

object DeepLProvider : TranslationProvider {

    override val id: Int = TranslationProviders.PROVIDER_DEEPL
    override val nameRes: Int = R.string.InuTranslateProviderDeepL

    override fun isConfigured(): Boolean = InuConfig.TRANSLATE_DEEPL_KEY.value.trim().isNotEmpty()

    override fun translate(text: String, toLang: String): String {
        val key = InuConfig.TRANSLATE_DEEPL_KEY.value.trim()
        val host = if (key.endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
        // entiny: `ignore_tags` tells DeepL to skip translating the CONTENT of those tags, which is
        // the opposite of what we want (we want the enclosed text translated, just re-wrapped in the
        // same marker) - and "inu" never matched the actual "inue0"/"inux0" tag names anyway. Plain
        // tag_handling=xml already preserves inline tag positions around translated text, which is
        // the behavior EntityKeeper's markers need.
        val body = "auth_key=" + encodeURIComponent(key) +
            "&text=" + encodeURIComponent(escapeXmlKeepingMarkers(text)) +
            "&target_lang=" + encodeURIComponent(normalizeToLang(toLang)) +
            "&tag_handling=xml"
        val resp = httpJson(
            host + "/v2/translate",
            method = "POST",
            body = body,
            contentType = "application/x-www-form-urlencoded",
        )
        val translations = JSONObject(resp).optJSONArray("translations") ?: JSONArray()
        if (translations.length() == 0) throw IOException("DeepL returned an empty result")
        return translations.getJSONObject(0).optString("text", "")
    }

    // entiny: tag_handling=xml means DeepL parses the body as XML - a literal '<', '>' or '&' in the
    // user's own text (outside our own markers) would otherwise break the parse or corrupt the round trip
    private fun escapeXmlKeepingMarkers(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length + 16)
        var last = 0
        for (m in EntityKeeper.MARKER_PATTERN.findAll(text)) {
            appendXmlEscaped(sb, text, last, m.range.first)
            sb.append(m.value)
            last = m.range.last + 1
        }
        appendXmlEscaped(sb, text, last, text.length)
        return sb.toString()
    }

    private fun appendXmlEscaped(sb: StringBuilder, text: String, start: Int, end: Int) {
        for (i in start until end) {
            when (val c = text[i]) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                else -> sb.append(c)
            }
        }
    }

    private fun normalizeToLang(code: String): String = when (code.lowercase()) {
        "zh-cn", "zh-hans" -> "ZH-HANS"
        "zh-tw", "zh-hant" -> "ZH-HANT"
        "nb", "no" -> "NB"
        else -> code.uppercase()
    }
}

object LlmProvider : TranslationProvider {

    override val id: Int = TranslationProviders.PROVIDER_LLM
    override val nameRes: Int = R.string.InuTranslateProviderLlm

    override fun isConfigured(): Boolean = InuConfig.TRANSLATE_LLM_URL.value.trim().isNotEmpty()

    override fun translate(text: String, toLang: String): String = translate(text, toLang, emptyList())

    override fun translate(text: String, toLang: String, context: List<String>): String {
        val endpoint = InuConfig.TRANSLATE_LLM_URL.value.trim()
        val key = InuConfig.TRANSLATE_LLM_KEY.value.trim()
        val model = InuConfig.TRANSLATE_LLM_MODEL.value.trim().ifBlank { "gpt-4o-mini" }
        val system = InuConfig.TRANSLATE_LLM_PROMPT.value.trim().ifBlank { DEFAULT_SYSTEM_PROMPT }
        val langName = TranslateAlert2.languageName(toLang) ?: toLang

        // entiny: conversation context goes as separate user turn so model does not translate the context itself
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        if (context.isNotEmpty()) {
            messages.put(
                JSONObject().put("role", "user").put(
                    "content",
                    "Earlier messages in this conversation, for context only. " +
                        "Do not translate, quote or mention them:\n" +
                        context.joinToString("\n") { "- $it" },
                ),
            )
            messages.put(
                JSONObject().put("role", "assistant")
                    .put("content", "Understood. I will output only the translation of the next message."),
            )
        }
        messages.put(JSONObject().put("role", "user").put("content", "Translate to $langName:\n$text"))

        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", InuConfig.TRANSLATE_LLM_TEMPERATURE.value.coerceIn(0f, 2f).toDouble())

        val headers = if (key.isNotEmpty()) mapOf("Authorization" to "Bearer $key") else emptyMap()
        val resp = httpJson(
            endpoint,
            method = "POST",
            body = payload.toString(),
            contentType = "application/json",
            headers = headers,
        )
        val content = JSONObject(resp)
            .optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "")
            ?.trim()
            .orEmpty()
        if (content.isEmpty()) throw IOException("LLM returned empty content")
        return content
    }

    private val DEFAULT_SYSTEM_PROMPT = """
        You are a translation engine. Translate the user's text into the requested language.

        Rules:
        1. Output ONLY the translation. No explanations, no notes, no quotes.
        2. Preserve markup tags such as <inu0>, <inu1> and their closing tags exactly as they appear.
        3. Tags like <inux0> stand for a url, @mention or hashtag. Reproduce them exactly, never translate or reword them, and keep each one exactly once.
        4. Preserve markdown formatting (bold, italic, strikethrough, inline code, code blocks, headings, lists, links) exactly as in the source.
        5. Preserve line breaks and code blocks.
    """.trimIndent()
}

object MicrosoftProvider : TranslationProvider {

    override val id: Int = TranslationProviders.PROVIDER_MICROSOFT
    override val nameRes: Int = R.string.InuTranslateProviderMicrosoftAzure

    override fun isConfigured(): Boolean = InuConfig.TRANSLATE_MICROSOFT_KEY.value.trim().isNotEmpty()

    override fun translate(text: String, toLang: String): String {
        val key = InuConfig.TRANSLATE_MICROSOFT_KEY.value.trim()
        val region = InuConfig.TRANSLATE_MICROSOFT_REGION.value.trim()
        val headers = mutableMapOf("Ocp-Apim-Subscription-Key" to key)
        if (region.isNotEmpty()) headers["Ocp-Apim-Subscription-Region"] = region
        val url = "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0" +
            "&to=" + encodeURIComponent(normalizeToLang(toLang))
        val resp = httpJson(
            url,
            method = "POST",
            body = JSONArray().put(JSONObject().put("Text", text)).toString(),
            contentType = "application/json",
            headers = headers,
        )
        val translations = JSONArray(resp).optJSONObject(0)?.optJSONArray("translations") ?: JSONArray()
        if (translations.length() == 0) throw IOException("Microsoft Translator returned an empty result")
        return translations.getJSONObject(0).optString("text", "")
    }

    private fun normalizeToLang(code: String): String = when (code.lowercase()) {
        "zh-cn", "zh-hans" -> "zh-Hans"
        "zh-hant", "zh-tw" -> "zh-Hant"
        "no" -> "nb"
        else -> code.lowercase()
    }
}

object MyMemoryProvider : TranslationProvider {

    override val id: Int = TranslationProviders.PROVIDER_MYMEMORY
    override val nameRes: Int = R.string.InuTranslateProviderMyMemory

    private const val MAX_CHUNK_CHARS = 450

    override fun translate(text: String, toLang: String): String {
        val lang = normalizeToLang(toLang)
        if (text.length <= MAX_CHUNK_CHARS) {
            return translateChunk(text, lang)
        }
        val sb = StringBuilder(text.length + 32)
        for ((chunk, tail) in splitForProvider(text, MAX_CHUNK_CHARS)) {
            sb.append(translateChunk(chunk, lang)).append(tail)
        }
        return sb.toString()
    }

    private fun translateChunk(text: String, lang: String): String {
        if (text.isBlank()) return text
        val url = "https://api.mymemory.translated.net/get?q=" + encodeURIComponent(text) +
            "&langpair=autodetect%7C" + encodeURIComponent(lang)
        val resp = httpJson(url)
        val json = JSONObject(resp)
        val status = json.optInt("responseStatus", 500)
        if (status != 200) {
            throw ProviderConfigException("MyMemory status $status: " + json.optString("responseDetails"))
        }
        val translated = json.optJSONObject("responseData")?.optString("translatedText", "").orEmpty()
        if (translated.isEmpty()) throw IOException("MyMemory returned an empty result")
        return translated
    }

    private fun normalizeToLang(code: String): String = when (code.lowercase()) {
        "zh-cn", "zh-hans" -> "zh-CN"
        "zh-hant", "zh-tw" -> "zh-TW"
        "no" -> "nb"
        else -> code.lowercase()
    }
}

object BingProvider : TranslationProvider {

    override val id: Int = TranslationProviders.PROVIDER_BING
    override val nameRes: Int = R.string.InuTranslateProviderMicrosoft

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Safari/537.36 Edg/122.0.0.0"

    private const val MAX_CHUNK_CHARS = 2500

    @Volatile private var ig: String? = null
    @Volatile private var iid: String? = null
    @Volatile private var key: String? = null
    @Volatile private var token: String? = null
    @Volatile private var tokenTs: Long = 0L
    @Volatile private var tokenExpiry: Long = 3_600_000L

    override fun translate(text: String, toLang: String): String {
        if (text.length <= MAX_CHUNK_CHARS) return translateChunk(text, toLang)
        val sb = StringBuilder(text.length + 32)
        for ((chunk, tail) in splitForProvider(text, MAX_CHUNK_CHARS)) {
            sb.append(translateChunk(chunk, toLang)).append(tail)
        }
        return sb.toString()
    }

    private fun translateChunk(text: String, toLang: String): String {
        if (text.isBlank()) return text
        var attempt = 0
        while (true) {
            ensureConfig()
            val body = "fromLang=auto-detect" +
                "&to=" + encodeURIComponent(normalizeToLang(toLang)) +
                "&text=" + encodeURIComponent(text) +
                "&token=" + encodeURIComponent(token!!) +
                "&key=" + encodeURIComponent(key!!) +
                "&tryFetchingGenderDebiasedTranslations=true"
            val resp = httpJson(
                "https://www.bing.com/ttranslatev3?isVertical=1&IG=" + encodeURIComponent(ig!!) +
                    "&IID=" + encodeURIComponent(iid!!) + ".1&ref=TThis&edgepdftranslator=1",
                method = "POST",
                body = body,
                contentType = "application/x-www-form-urlencoded",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://www.bing.com/translator",
                ),
            )
            val root = try {
                JSONTokener(resp).nextValue()
            } catch (e: JSONException) {
                throw IOException("Bing returned an unreadable response")
            }
            if (root is JSONArray) {
                val translations = root.optJSONObject(0)?.optJSONArray("translations") ?: JSONArray()
                if (translations.length() == 0) throw IOException("Bing returned an empty result")
                return translations.getJSONObject(0).optString("text", "")
            }
            val code = (root as? JSONObject)?.optInt("statusCode", 0) ?: 0
            if (code == 429) throw ProviderRateLimitException("Bing: too many requests")
            // entiny: an error object usually means a stale token, so refresh the config once before giving up
            if (++attempt > 1) throw IOException("Bing error $code")
            invalidateConfig()
        }
    }

    private fun invalidateConfig() {
        synchronized(this) { tokenTs = 0L }
    }

    @Synchronized
    private fun ensureConfig() {
        val cached = token
        if (cached != null && System.currentTimeMillis() - tokenTs < tokenExpiry) return
        val html = httpJson(
            "https://www.bing.com/translator",
            headers = mapOf("User-Agent" to USER_AGENT),
        )
        ig = Regex("IG:\"([^\"]*)\"").find(html)?.groupValues?.get(1)
        iid = Regex("data-iid=\"([^\"]*)\"").find(html)?.groupValues?.get(1)
        val params = Regex("params_AbusePreventionHelper = \\[([^\\]]+)\\]")
            .find(html)?.groupValues?.getOrNull(1)
            ?.split(",")?.map { it.trim().trim('"') } ?: emptyList()
        if (ig == null || iid == null || params.size < 3) {
            throw IOException("Bing config parse failed (page layout changed?)")
        }
        key = params[0]
        token = params[1]
        tokenExpiry = params[2].toLongOrNull() ?: 3_600_000L
        tokenTs = System.currentTimeMillis()
    }

    private fun normalizeToLang(code: String): String = when (code.lowercase()) {
        "zh", "zh-cn", "zh-hans" -> "zh-Hans"
        "zh-hant", "zh-tw" -> "zh-Hant"
        "no" -> "nb"
        else -> code.lowercase()
    }
}

// #if PLUGINS
/** Runs the text through the plugin, waiting for the answer: the engine calls providers off the main thread. */
class PluginTranslationProvider(val key: String, private val name: String) : TranslationProvider {
    override val id: Int = TranslationProviders.PROVIDER_PLUGIN
    override val pluginKey: String get() = key
    override val nameRes: Int = 0

    override fun displayName(): String = name

    override fun translate(text: String, toLang: String): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: String? = null
        val item = org.telegram.tgnet.TLRPC.TL_textWithEntities().apply {
            this.text = text
            entities = ArrayList()
        }
        desu.inugram.helpers.plugins.EngineDispatch.scheduler.postRunnable {
            desu.inugram.helpers.plugins.telegram.PluginTranslation.translate(key, listOf(item), listOf(null), toLang, null) { texts ->
                result = texts?.firstOrNull()?.text
                latch.countDown()
            }
        }
        if (!latch.await(35, java.util.concurrent.TimeUnit.SECONDS)) throw IOException("The translation plugin did not answer in time")
        return result ?: throw IOException("The translation plugin failed, its log says why")
    }
}
// #endif
