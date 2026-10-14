package desu.inugram.helpers

import desu.inugram.InuConfig
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.security.SecureRandom

object LogRedactor {
    private class Rule(val kind: String, val regex: Regex, val group: Int = 0)

    private val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }

    private val rules = listOf(
        Rule("bot", Regex("""\b\d{6,10}:[A-Za-z0-9_-]{30,}\b""")),
        Rule("fcm", Regex("""[\w-]{8,}:APA91[\w-]+""")),
        Rule(
            "secret",
            Regex("""(?i)\b(?:access_hash|auth_key|api_hash|phone_code_hash|token|secret|password|session|hash)\b["']?\s*[:=]\s*["']?([\w+/=:.-]{8,})"""),
            1,
        ),
        Rule("email", Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")),
        Rule("phone", Regex("""(?i)\bphone(?:_number)?\b["']?\s*[:=]\s*["']?(\+?\d{7,15})"""), 1),
        Rule("phone", Regex("""(?<![\w.])\+\d{9,15}\b""")),
        Rule(
            "id",
            Regex("""(?i)\b(?:user_?id|chat_?id|channel_?id|dialog_?id|from_?id|peer_?id|self_?id|did|uid)\b["']?\s*[:=]\s*["']?(-?\d{5,})"""),
            1,
        ),
        Rule("ip", Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")),
        Rule("hex", Regex("""\b[0-9a-fA-F]{32,}\b""")),
        Rule("user", Regex("""(?<![\w@.])@[A-Za-z][A-Za-z0-9_]{4,31}\b""")),
        Rule("link", Regex("""(?i)\bt\.me/(?:\+|joinchat/)?([\w-]{5,})"""), 1),
    )

    private fun enabled(): Boolean = runCatching { InuConfig.LOGS_REDACT.value }.getOrDefault(true)

    private fun tag(kind: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").apply { update(salt) }.digest(value.toByteArray())
        val short = digest.take(2).joinToString("") { "%02x".format(it) }
        return "<$kind#$short>"
    }

    @JvmStatic
    fun redact(text: String): String {
        if (text.length < 5 || !enabled()) return text
        var out = text
        for (rule in rules) {
            out = rule.regex.replace(out) { m ->
                val g = m.groups[rule.group] ?: return@replace m.value
                if (rule.group == 0) {
                    tag(rule.kind, g.value)
                } else {
                    val start = g.range.first - m.range.first
                    m.value.replaceRange(start, start + g.value.length, tag(rule.kind, g.value)).toString()
                }
            }
        }
        return out
    }
}

class RedactingWriter(stream: OutputStream) : OutputStreamWriter(stream) {
    override fun write(str: String, off: Int, len: Int) {
        val redacted = LogRedactor.redact(str.substring(off, off + len))
        super.write(redacted, 0, redacted.length)
    }
}
