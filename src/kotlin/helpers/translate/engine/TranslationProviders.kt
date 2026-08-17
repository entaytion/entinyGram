package desu.inugram.helpers.translate.engine

import desu.inugram.InuConfig
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.R
import org.telegram.ui.Components.TranslateAlert2
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * A text translation backend. Implementations are plain text → text and must be called off the
 * main thread. Entity/formatting preservation is handled by [EntityKeeper] on top of the raw
 * translation, so providers do not need to know anything about Telegram entities.
 */
interface TranslationProvider {

    /** Stable id matching [TranslationProviders] constants and the stored config value. */
    val id: Int

    val nameRes: Int

    /** Whether the user has configured everything this provider needs. */
    fun isConfigured(): Boolean = true

    /**
     * Translates [text] into [toLang] (a Telegram-style code like "en", "ru", "zh-cn").
     * Blocking; throws [ProviderRateLimitException] on rate limiting and [IOException] on
     * transient network failures so the engine can retry.
     */
    @Throws(Exception::class)
    fun translate(text: String, toLang: String): String

    /**
     * Whether this provider can translate into [toLang]. Providers with a limited language set
     * (Lingo, TranSmart) override this; the engine falls back to a broader provider (Google,
     * Bing) when the selected one does not support the target language.
     */
    fun supportsLanguage(toLang: String): Boolean = true
}

/** Transient HTTP 429 / provider rate limit — retry with backoff. */
class ProviderRateLimitException(message: String) : IOException(message)

/** Permanent configuration/API errors (bad key, unsupported language) — do not retry. */
class ProviderConfigException(message: String) : IOException(message)

object TranslationProviders {

    const val PROVIDER_TELEGRAM = 0
    const val PROVIDER_GOOGLE = 1
    const val PROVIDER_DEEPL = 2
    const val PROVIDER_LLM = 3
    const val PROVIDER_YANDEX = 4
    const val PROVIDER_MICROSOFT = 5
    const val PROVIDER_MYMEMORY = 6
    const val PROVIDER_LINGO = 7
    const val PROVIDER_TRANSMART = 8
    const val PROVIDER_BING = 9

    /** Providers selectable in settings, in display order. Telegram API is handled by stock code. */
    val all: List<TranslationProvider> = listOf(
        GoogleWebProvider,
        DeepLProvider,
        LlmProvider,
        YandexProvider,
        BingProvider,
        MicrosoftProvider,
        MyMemoryProvider,
        LingoProvider,
        TranSmartProvider,
    )

    fun current(): TranslationProvider? = when (InuConfig.TRANSLATE_PROVIDER.value) {
        PROVIDER_GOOGLE -> GoogleWebProvider
        PROVIDER_DEEPL -> DeepLProvider
        PROVIDER_LLM -> LlmProvider
        PROVIDER_YANDEX -> YandexProvider
        PROVIDER_MICROSOFT -> MicrosoftProvider
        PROVIDER_MYMEMORY -> MyMemoryProvider
        PROVIDER_LINGO -> LingoProvider
        PROVIDER_TRANSMART -> TranSmartProvider
        PROVIDER_BING -> BingProvider
        else -> null
    }

    /**
     * Returns [provider] when it supports [toLang], otherwise the first fallback provider that
     * does (Google and Bing both cover ~all Telegram languages, including Ukrainian, which
     * Lingo and TranSmart lack). Never returns a provider that cannot handle the target, so the
     * engine surfaces an accurate error instead of retrying forever.
     */
    fun effectiveProvider(provider: TranslationProvider, toLang: String): TranslationProvider {
        if (provider.supportsLanguage(toLang)) return provider
        val fallback = if (provider == GoogleWebProvider) BingProvider else GoogleWebProvider
        return if (fallback.supportsLanguage(toLang)) fallback else provider
    }

    /**
     * Whether the currently selected provider can translate into [code] (a Telegram-style code
     * like "en", "uk", "zh-cn"). Used to filter language pickers so they only list languages
     * the chosen provider actually knows instead of the full stock list.
     */
    @JvmStatic
    fun providerSupportsTarget(code: String): Boolean {
        val provider = current() ?: return true
        return provider.supportsLanguage(code)
    }
}

private fun encodeURIComponent(s: String): String =
    URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%7E", "~")

/** Minimal blocking HTTP helper shared by providers. */
internal fun httpJson(
    url: String,
    method: String = "GET",
    body: String? = null,
    contentType: String? = null,
    headers: Map<String, String> = emptyMap(),
    connectTimeout: Int = 10_000,
    readTimeout: Int = 45_000,
): String {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        this.connectTimeout = connectTimeout
        this.readTimeout = readTimeout
        doOutput = body != null
        contentType?.let { setRequestProperty("Content-Type", it) }
        for ((k, v) in headers) setRequestProperty(k, v)
        if (body != null) {
            setFixedLengthStreamingMode(body.toByteArray().size)
            outputStream.use { it.write(body.toByteArray()) }
        }
    }
    return try {
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        when {
            code == 429 -> throw ProviderRateLimitException("HTTP 429: ${text.take(200)}")
            code in 200..299 -> text
            code in 400..499 -> throw ProviderConfigException("HTTP $code: ${text.take(200)}")
            else -> throw IOException("HTTP $code: ${text.take(200)}")
        }
    } finally {
        conn.disconnect()
    }
}

/**
 * Google's public web translate endpoint (translate.googleapis.com, `client=gtx`). No API key
 * needed; subject to Google's unofficial rate limits, which the engine's serial queue and
 * backoff keep in check. A POST body avoids URL-length limits on long messages and the
 * `client=at` variant is more often blocked/throttled from mobile IPs.
 */
object GoogleWebProvider : TranslationProvider {

    override val id: Int = TranslationProviders.PROVIDER_GOOGLE
    override val nameRes: Int = R.string.InuTranslateProviderGoogle

    override fun translate(text: String, toLang: String): String {
        val body = "client=gtx&sl=auto&dt=t" +
            "&tl=" + encodeURIComponent(normalizeToLang(toLang)) +
            "&q=" + encodeURIComponent(text)
        val resp = httpJson(
            "https://translate.googleapis.com/translate_a/single",
            method = "POST",
            body = body,
            contentType = "application/x-www-form-urlencoded",
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"),
        )
        val sentences = JSONArray(resp).optJSONArray(0) ?: JSONArray()
        val sb = StringBuilder(text.length)
        for (i in 0 until sentences.length()) {
            sb.append(sentences.getJSONArray(i).optString(0, ""))
        }
        if (sb.isEmpty()) throw IOException("Google Translate returned an empty result")
        return sb.toString()
    }

    private fun normalizeToLang(code: String): String {
        // Google uses "no" for Norwegian; region suffixes like "zh-cn" are uppercased to "zh-CN".
        if (code.equals("nb", ignoreCase = true)) return "no"
        val dash = code.indexOf('-')
        if (dash > 0) {
            return code.substring(0, dash).lowercase() + "-" + code.substring(dash + 1).uppercase()
        }
        return code.lowercase()
    }
}

/** DeepL API v2. Uses the free endpoint automatically when the key carries the ":fx" suffix. */
object DeepLProvider : TranslationProvider {

    override val id: Int = TranslationProviders.PROVIDER_DEEPL
    override val nameRes: Int = R.string.InuTranslateProviderDeepL

    override fun isConfigured(): Boolean = InuConfig.TRANSLATE_DEEPL_KEY.value.trim().isNotEmpty()

    override fun translate(text: String, toLang: String): String {
        val key = InuConfig.TRANSLATE_DEEPL_KEY.value.trim()
        val host = if (key.endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
        val body = "auth_key=" + encodeURIComponent(key) +
            "&text=" + encodeURIComponent(text) +
            "&target_lang=" + encodeURIComponent(normalizeToLang(toLang)) +
            "&tag_handling=xml&ignore_tags=inu"
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

    private fun normalizeToLang(code: String): String = when (code.lowercase()) {
        "zh-cn", "zh-hans" -> "ZH-HANS"
        "zh-tw", "zh-hant" -> "ZH-HANT"
        "nb", "no" -> "NB"
        else -> code.uppercase()
    }
}

/**
 * Any OpenAI-compatible chat completions endpoint. The user points it at their own server/LLM
 * gateway; the system prompt tells the model to translate only and preserve the `<inuN>` markup
 * used for entity preservation.
 */
object LlmProvider : TranslationProvider {

    override val id: Int = TranslationProviders.PROVIDER_LLM
    override val nameRes: Int = R.string.InuTranslateProviderLlm

    override fun isConfigured(): Boolean = InuConfig.TRANSLATE_LLM_URL.value.trim().isNotEmpty()

    override fun translate(text: String, toLang: String): String {
        val endpoint = InuConfig.TRANSLATE_LLM_URL.value.trim()
        val key = InuConfig.TRANSLATE_LLM_KEY.value.trim()
        val model = InuConfig.TRANSLATE_LLM_MODEL.value.trim().ifBlank { "gpt-4o-mini" }
        val system = InuConfig.TRANSLATE_LLM_PROMPT.value.trim().ifBlank { DEFAULT_SYSTEM_PROMPT }
        val langName = TranslateAlert2.languageName(toLang) ?: toLang

        val payload = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", "Translate to $langName:\n$text")),
            )
            .put("temperature", 0.3)

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
        3. Preserve markdown formatting (bold, italic, strikethrough, inline code, code blocks, headings, lists, links) exactly as in the source.
        4. Preserve line breaks and code blocks.
    """.trimIndent()
}

/**
 * Yandex Cloud Translate API v2. New Yandex Cloud accounts include a free trial allocation
 * (1M chars/month); the API key is created in the cloud console. Source language is left out
 * so the service auto-detects it.
 */
object YandexProvider : TranslationProvider {

    override val id: Int = TranslationProviders.PROVIDER_YANDEX
    override val nameRes: Int = R.string.InuTranslateProviderYandex

    override fun isConfigured(): Boolean = InuConfig.TRANSLATE_YANDEX_KEY.value.trim().isNotEmpty()

    override fun translate(text: String, toLang: String): String {
        val key = InuConfig.TRANSLATE_YANDEX_KEY.value.trim()
        val payload = JSONObject()
            .put("targetLanguageCode", normalizeToLang(toLang))
            .put("texts", JSONArray().put(text))
        val resp = httpJson(
            "https://translate.api.cloud.yandex.net/translate/v2/translate",
            method = "POST",
            body = payload.toString(),
            contentType = "application/json",
            headers = mapOf("Authorization" to "Api-Key $key"),
        )
        val translations = JSONObject(resp).optJSONArray("translations") ?: JSONArray()
        if (translations.length() == 0) throw IOException("Yandex Translate returned an empty result")
        return translations.getJSONObject(0).optString("text", "")
    }

    private fun normalizeToLang(code: String): String = when (code.lowercase()) {
        "zh-cn", "zh-hans", "zh-hant", "zh-tw" -> "zh"
        "iw" -> "he"
        else -> code.lowercase()
    }
}

/**
 * Microsoft Translator (Azure Cognitive Services). The F0 tier is free (2M chars/month) and
 * only needs an API key; the region header is optional when the resource is global.
 */
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

/**
 * MyMemory — free translation API, no key required (Google-backed MT plus community memory).
 * Anonymous use is rate-limited (~5k chars/day per IP), so it suits light use; the engine's
 * serial queue keeps bursts in check. The free GET endpoint caps `q` at 500 chars, so longer
 * messages are split into chunks (on line boundaries where possible) and translated per part.
 */
object MyMemoryProvider : TranslationProvider {

    override val id: Int = TranslationProviders.PROVIDER_MYMEMORY
    override val nameRes: Int = R.string.InuTranslateProviderMyMemory

    /** Hard cap of the free GET API; stay below it to leave room for URL escaping. */
    private const val MAX_CHUNK_CHARS = 450

    override fun translate(text: String, toLang: String): String {
        val lang = normalizeToLang(toLang)
        if (text.length <= MAX_CHUNK_CHARS) {
            return translateChunk(text, lang)
        }
        val chunks = splitChunks(text)
        val sb = StringBuilder(text.length + 32)
        for ((i, chunk) in chunks.withIndex()) {
            if (i > 0) sb.append('\n')
            sb.append(translateChunk(chunk, lang))
        }
        return sb.toString()
    }

    private fun translateChunk(text: String, lang: String): String {
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

    /** Splits [text] into ≤ [MAX_CHUNK_CHARS]-char pieces, preferring line boundaries. */
    private fun splitChunks(text: String): List<String> {
        val out = ArrayList<String>()
        var current = StringBuilder()
        for (line in text.split('\n')) {
            if (line.length <= MAX_CHUNK_CHARS &&
                current.length + (if (current.isEmpty()) 0 else 1) + line.length <= MAX_CHUNK_CHARS
            ) {
                if (current.isNotEmpty()) current.append('\n')
                current.append(line)
                continue
            }
            if (current.isNotEmpty()) {
                out.add(current.toString())
                current = StringBuilder()
            }
            if (line.length <= MAX_CHUNK_CHARS) {
                current.append(line)
            } else {
                var start = 0
                while (start < line.length) {
                    val end = minOf(start + MAX_CHUNK_CHARS, line.length)
                    out.add(line.substring(start, end))
                    start = end
                }
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    private fun normalizeToLang(code: String): String = when (code.lowercase()) {
        "zh-cn", "zh-hans" -> "zh-CN"
        "zh-hant", "zh-tw" -> "zh-TW"
        "no" -> "nb"
        else -> code.lowercase()
    }
}

/**
 * Bing Translator — the free web endpoint used by translator.bing.com (no API key).
 * Requires scraping the IG/IID/key/token values from the translator page; they are cached
 * for their expiry window. Adapted from NagramX's BingTranslatorRaw. Verified live.
 */
object BingProvider : TranslationProvider {

    override val id: Int = TranslationProviders.PROVIDER_BING
    override val nameRes: Int = R.string.InuTranslateProviderMicrosoft

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Safari/537.36 Edg/122.0.0.0"

    @Volatile private var ig: String? = null
    @Volatile private var iid: String? = null
    @Volatile private var key: String? = null
    @Volatile private var token: String? = null
    @Volatile private var tokenTs: Long = 0L
    @Volatile private var tokenExpiry: Long = 3_600_000L

    override fun translate(text: String, toLang: String): String {
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
        val translations = JSONArray(resp).optJSONObject(0)?.optJSONArray("translations") ?: JSONArray()
        if (translations.length() == 0) throw IOException("Bing returned an empty result")
        return translations.getJSONObject(0).optString("text", "")
    }

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

/**
 * Lingo (Caiyun Xiaoyi) — free Chinese translation service with a public shared token,
 * no registration. Only supports zh/en/es/fr/ja/ru targets. Adapted from NagramX; verified live.
 */
object LingoProvider : TranslationProvider {

    override val id: Int = TranslationProviders.PROVIDER_LINGO
    override val nameRes: Int = R.string.InuTranslateProviderLingo

    private const val TOKEN = "9sdftiq37bnv410eon2l"
    private val supported = setOf("zh", "en", "es", "fr", "ja", "ru")

    override fun supportsLanguage(toLang: String): Boolean = normalizeToLang(toLang) in supported

    override fun translate(text: String, toLang: String): String {
        val lang = normalizeToLang(toLang)
        if (lang !in supported) throw ProviderConfigException("Lingo does not support target $toLang")
        val lines = text.split("\n")
        val payload = JSONObject()
            .put("source", JSONArray().apply { lines.forEach { put(it) } })
            .put("trans_type", "auto2$lang")
            .put("request_id", System.currentTimeMillis().toString())
            .put("detect", true)
        val resp = httpJson(
            "https://api.interpreter.caiyunai.com/v1/translator",
            method = "POST",
            body = payload.toString(),
            contentType = "application/json",
            headers = mapOf(
                "X-Authorization" to "token $TOKEN",
                "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 10_0 like Mac OS X)",
            ),
        )
        val json = JSONObject(resp)
        if (json.optInt("rc", -1) != 0) {
            throw ProviderConfigException("Lingo rc=" + json.optInt("rc", -1))
        }
        val target = json.optJSONArray("target") ?: JSONArray()
        val sb = StringBuilder(text.length)
        for (i in 0 until target.length()) {
            val line = target.getString(i)
            if (line == "\ud835") continue // NagramX: bogus surrogate emitted for empty lines
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        if (sb.isEmpty()) throw IOException("Lingo returned an empty result")
        return sb.toString()
    }

    private fun normalizeToLang(code: String): String = when (code.lowercase()) {
        "zh-cn", "zh-hans", "zh-hant", "zh-tw" -> "zh"
        else -> code.lowercase()
    }
}

/**
 * TranSmart (Tencent) — free web translator with a spoofed browser client_key, no API key.
 * Adapted from NagramX; verified live with source.lang=auto.
 */
object TranSmartProvider : TranslationProvider {

    override val id: Int = TranslationProviders.PROVIDER_TRANSMART
    override val nameRes: Int = R.string.InuTranslateProviderTranSmart

    private val supported = setOf(
        "ar", "fr", "fil", "lo", "ja", "it", "hi", "id", "vi", "de",
        "km", "ms", "th", "tr", "zh", "ru", "ko", "pt", "es",
    )
    private val operatingSystems = arrayOf("Mac OS", "Windows")

    override fun supportsLanguage(toLang: String): Boolean = normalizeToLang(toLang) in supported

    override fun translate(text: String, toLang: String): String {
        val lang = normalizeToLang(toLang)
        if (lang !in supported) throw ProviderConfigException("TranSmart does not support target $toLang")
        val clientKey = "browser-chrome-${randomVersion()}-${operatingSystems.random()}-" +
            UUID.randomUUID() + "-" + System.currentTimeMillis()
        val lines = JSONArray().apply { text.split("\n").forEach { put(it) } }
        val payload = JSONObject()
            .put(
                "header",
                JSONObject()
                    .put("client_key", clientKey)
                    .put("fn", "auto_translation")
                    .put("session", "")
                    .put("user", ""),
            )
            .put("source", JSONObject().put("lang", "auto").put("text_list", lines))
            .put("target", JSONObject().put("lang", lang))
            .put("model_category", "normal")
            .put("text_domain", "")
            .put("type", "plain")
        val resp = httpJson(
            "https://transmart.qq.com/api/imt",
            method = "POST",
            body = payload.toString(),
            contentType = "application/json",
            headers = mapOf("User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 10_0 like Mac OS X)"),
        )
        val json = JSONObject(resp)
        val ret = json.optJSONObject("header")?.optString("ret_code", "").orEmpty()
        if (ret != "succ") throw ProviderConfigException("TranSmart failed: $ret")
        val result = json.optJSONArray("auto_translation") ?: JSONArray()
        val sb = StringBuilder(text.length)
        for (i in 0 until result.length()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(result.getString(i))
        }
        if (sb.isEmpty()) throw IOException("TranSmart returned an empty result")
        return sb.toString()
    }

    private fun randomVersion(): String {
        val major = (Math.random() * 17).toInt() + 100
        val minor = (Math.random() * 20).toInt()
        val patch = (Math.random() * 20).toInt()
        return "$major.$minor.$patch"
    }

    private fun normalizeToLang(code: String): String = when (code.lowercase()) {
        "zh-cn", "zh-hans", "zh-hant", "zh-tw" -> "zh"
        else -> code.lowercase()
    }
}
