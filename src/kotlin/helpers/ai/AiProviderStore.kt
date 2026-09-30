package desu.inugram.helpers.ai

import desu.inugram.InuConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object AiProviderStore {

    data class Provider(
        val id: String,
        val kind: Int,
        val name: String,
        val url: String,
        val key: String,
        val accountId: String,
        val chatModel: String,
        val voiceModel: String,
    )

    val PRESET_ORDER = listOf(
        InuConfig.TRANSCRIBE_PROVIDER_GEMINI,
        InuConfig.TRANSCRIBE_PROVIDER_OPENAI,
        InuConfig.TRANSCRIBE_PROVIDER_GROQ,
        InuConfig.AI_PROVIDER_OPENROUTER,
        InuConfig.TRANSCRIBE_PROVIDER_CF,
        InuConfig.TRANSCRIBE_PROVIDER_CUSTOM,
    )

    const val NONE = "-"

    private var cache: List<Provider>? = null

    fun canChat(kind: Int) = kind != InuConfig.TRANSCRIBE_PROVIDER_CF

    fun canVoice(kind: Int) = kind != InuConfig.AI_PROVIDER_OPENROUTER

    fun needsKey(kind: Int) = kind != InuConfig.TRANSCRIBE_PROVIDER_CUSTOM

    fun defaultName(kind: Int) = when (kind) {
        InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> "Gemini"
        InuConfig.TRANSCRIBE_PROVIDER_OPENAI -> "OpenAI"
        InuConfig.TRANSCRIBE_PROVIDER_GROQ -> "Groq"
        InuConfig.AI_PROVIDER_OPENROUTER -> "OpenRouter"
        InuConfig.TRANSCRIBE_PROVIDER_CF -> "Cloudflare"
        else -> "Custom"
    }

    fun presetBaseUrl(kind: Int) = when (kind) {
        InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai"
        InuConfig.TRANSCRIBE_PROVIDER_OPENAI -> "https://api.openai.com/v1"
        InuConfig.TRANSCRIBE_PROVIDER_GROQ -> "https://api.groq.com/openai/v1"
        InuConfig.AI_PROVIDER_OPENROUTER -> "https://openrouter.ai/api/v1"
        else -> ""
    }

    fun defaultChatModel(kind: Int) = when (kind) {
        InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> "gemini-3.1-flash-lite"
        InuConfig.TRANSCRIBE_PROVIDER_OPENAI -> "gpt-4o-mini"
        InuConfig.TRANSCRIBE_PROVIDER_GROQ -> "llama-3.3-70b-versatile"
        InuConfig.AI_PROVIDER_OPENROUTER -> "openai/gpt-4o-mini"
        else -> ""
    }

    fun defaultVoiceModel(kind: Int) = when (kind) {
        InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> "gemini-3.5-flash"
        InuConfig.TRANSCRIBE_PROVIDER_OPENAI -> "whisper-1"
        InuConfig.TRANSCRIBE_PROVIDER_GROQ -> "whisper-large-v3-turbo"
        InuConfig.TRANSCRIBE_PROVIDER_CF -> "@cf/openai/whisper"
        else -> ""
    }

    fun create(kind: Int) = Provider(
        id = UUID.randomUUID().toString(),
        kind = kind,
        name = defaultName(kind),
        url = "",
        key = "",
        accountId = "",
        chatModel = if (canChat(kind)) defaultChatModel(kind) else "",
        voiceModel = if (canVoice(kind)) defaultVoiceModel(kind) else "",
    )

    // entiny: chat and voice requests share one base URL; only the custom kind lets the user set it
    fun baseUrl(p: Provider): String =
        if (p.kind == InuConfig.TRANSCRIBE_PROVIDER_CUSTOM) AiComposeHelper.normalizeBase(p.url) else presetBaseUrl(p.kind)

    @Synchronized
    fun all(): List<Provider> {
        cache?.let { return it }
        val raw = InuConfig.AI_PROVIDERS.value
        val list = if (raw.isBlank()) migrateLegacy().also { save(it) } else parse(raw)
        cache = list
        return list
    }

    fun get(id: String?): Provider? = if (id.isNullOrBlank()) null else all().firstOrNull { it.id == id }

    // entiny: without an explicit choice the first ready provider serves, so a configured card never looks dead
    fun chatProvider(): Provider? {
        val id = InuConfig.AI_CHAT_PROVIDER_ID.value
        if (id == NONE) return null
        return get(id)?.takeIf { canChat(it.kind) } ?: all().firstOrNull { canChat(it.kind) && isReady(it) }
    }

    fun voiceProvider(): Provider? {
        val id = InuConfig.AI_VOICE_PROVIDER_ID.value
        if (id == NONE) return null
        return get(id)?.takeIf { canVoice(it.kind) } ?: all().firstOrNull { canVoice(it.kind) && isReady(it) }
    }

    private fun isReady(p: Provider): Boolean = when (p.kind) {
        InuConfig.TRANSCRIBE_PROVIDER_CUSTOM -> p.url.isNotBlank()
        InuConfig.TRANSCRIBE_PROVIDER_CF -> p.accountId.isNotBlank() && p.key.isNotBlank()
        else -> p.key.isNotBlank()
    }

    @Synchronized
    fun upsert(p: Provider) {
        val list = all().toMutableList()
        val index = list.indexOfFirst { it.id == p.id }
        if (index >= 0) list[index] = p else list.add(p)
        save(list)
    }

    @Synchronized
    fun remove(id: String) {
        save(all().filter { it.id != id })
        if (InuConfig.AI_CHAT_PROVIDER_ID.value == id) InuConfig.AI_CHAT_PROVIDER_ID.value = ""
        if (InuConfig.AI_VOICE_PROVIDER_ID.value == id) InuConfig.AI_VOICE_PROVIDER_ID.value = ""
    }

    private fun save(list: List<Provider>) {
        cache = list
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject()
                    .put("id", p.id).put("kind", p.kind).put("name", p.name).put("url", p.url)
                    .put("key", p.key).put("account", p.accountId)
                    .put("chat", p.chatModel).put("voice", p.voiceModel)
            )
        }
        InuConfig.AI_PROVIDERS.value = arr.toString()
    }

    private fun parse(raw: String): List<Provider> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Provider(
                id = o.getString("id"),
                kind = o.getInt("kind"),
                name = o.optString("name"),
                url = o.optString("url"),
                key = o.optString("key"),
                accountId = o.optString("account"),
                chatModel = o.optString("chat"),
                voiceModel = o.optString("voice"),
            )
        }
    }.getOrDefault(emptyList())

    private fun keyed(kind: Int, key: String, block: (Provider) -> Provider): Provider? =
        key.trim().takeIf { it.isNotBlank() }?.let { block(create(kind).copy(key = it)) }

    private fun migrateLegacy(): List<Provider> {
        val gemini = keyed(InuConfig.TRANSCRIBE_PROVIDER_GEMINI, InuConfig.AI_PROVIDER_GEMINI_KEY.value) {
            it.copy(
                chatModel = InuConfig.AI_CHAT_GEMINI_MODEL.value.ifBlank { it.chatModel },
                voiceModel = InuConfig.AI_TRANSCRIBE_GEMINI_MODEL.value.ifBlank { it.voiceModel },
            )
        }
        val openai = keyed(InuConfig.TRANSCRIBE_PROVIDER_OPENAI, InuConfig.AI_PROVIDER_OPENAI_KEY.value) {
            it.copy(
                chatModel = InuConfig.AI_CHAT_OPENAI_MODEL.value.ifBlank { it.chatModel },
                voiceModel = InuConfig.AI_TRANSCRIBE_OPENAI_MODEL.value.ifBlank { it.voiceModel },
            )
        }
        val groq = keyed(InuConfig.TRANSCRIBE_PROVIDER_GROQ, InuConfig.AI_PROVIDER_GROQ_KEY.value) {
            it.copy(
                chatModel = InuConfig.AI_CHAT_GROQ_MODEL.value.ifBlank { it.chatModel },
                voiceModel = InuConfig.AI_TRANSCRIBE_GROQ_MODEL.value.ifBlank { it.voiceModel },
            )
        }
        val openrouter = keyed(InuConfig.AI_PROVIDER_OPENROUTER, InuConfig.AI_CHAT_OPENROUTER_KEY.value) {
            it.copy(chatModel = InuConfig.AI_CHAT_OPENROUTER_MODEL.value.ifBlank { it.chatModel })
        }
        val cf = keyed(InuConfig.TRANSCRIBE_PROVIDER_CF, InuConfig.AI_TRANSCRIBE_CF_API_TOKEN.value) {
            it.copy(
                accountId = InuConfig.AI_TRANSCRIBE_CF_ACCOUNT_ID.value.trim(),
                voiceModel = InuConfig.AI_TRANSCRIBE_CF_MODEL.value.ifBlank { it.voiceModel },
            )
        }

        val custom = InuConfig.TRANSCRIBE_PROVIDER_CUSTOM
        val chatUrl = InuConfig.AI_CHAT_CUSTOM_URL.value.trim()
        val voiceUrl = InuConfig.AI_TRANSCRIBE_CUSTOM_URL.value.trim().removeSuffix("/audio/transcriptions")
        val sameCustom = chatUrl.isNotBlank() && voiceUrl.isNotBlank() &&
            AiComposeHelper.normalizeBase(chatUrl) == AiComposeHelper.normalizeBase(voiceUrl)
        val customChat = if (chatUrl.isNotBlank()) create(custom).copy(
            name = InuConfig.AI_CHAT_CUSTOM_NAME.value.ifBlank { defaultName(custom) },
            url = chatUrl,
            key = InuConfig.AI_CHAT_CUSTOM_KEY.value.trim(),
            chatModel = InuConfig.AI_CHAT_CUSTOM_MODEL.value.trim(),
            voiceModel = if (sameCustom) InuConfig.AI_TRANSCRIBE_CUSTOM_MODEL.value.trim() else "",
        ) else null
        val customVoice = if (voiceUrl.isNotBlank() && !sameCustom) create(custom).copy(
            name = InuConfig.AI_TRANSCRIBE_CUSTOM_NAME.value.ifBlank { defaultName(custom) },
            url = voiceUrl,
            key = InuConfig.AI_TRANSCRIBE_CUSTOM_KEY.value.trim(),
            voiceModel = InuConfig.AI_TRANSCRIBE_CUSTOM_MODEL.value.trim(),
        ) else null

        val chatId = when (InuConfig.AI_CHAT_ACTIVE_PROVIDER.value) {
            InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> gemini
            InuConfig.TRANSCRIBE_PROVIDER_OPENAI -> openai
            InuConfig.TRANSCRIBE_PROVIDER_GROQ -> groq
            InuConfig.AI_PROVIDER_OPENROUTER -> openrouter
            custom -> customChat
            else -> null
        }?.id
        val voiceId = when (InuConfig.AI_TRANSCRIBE_PROVIDER.value) {
            InuConfig.TRANSCRIBE_PROVIDER_GEMINI -> gemini
            InuConfig.TRANSCRIBE_PROVIDER_OPENAI -> openai
            InuConfig.TRANSCRIBE_PROVIDER_GROQ -> groq
            InuConfig.TRANSCRIBE_PROVIDER_CF -> cf
            custom -> customVoice ?: customChat
            else -> null
        }?.id
        InuConfig.AI_CHAT_PROVIDER_ID.value = chatId.orEmpty()
        InuConfig.AI_VOICE_PROVIDER_ID.value = voiceId.orEmpty()
        return listOfNotNull(gemini, openai, groq, openrouter, cf, customChat, customVoice)
    }
}
