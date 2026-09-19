package desu.inugram.helpers.pillstack

import desu.inugram.InuConfig
import desu.inugram.helpers.pillstack.pills.RatePill
import org.telegram.messenger.R

// entiny: user-added rate pills (base -> target pairs), ported from exteraGram/exteraless's RateInstances. Ids >= FIRST_ID so they never collide with PillType.
object RateInstances {

    const val FIRST_ID = 100
    const val MAX_COUNT = 8

    val BASE_CURRENCIES = arrayOf("BTC", "ETH", "XAU", "TON", "EUR", "USD")

    class Instance(val id: Int, var from: String, var to: String)

    private val sync = Any()
    private val instances = LinkedHashMap<Int, Instance>()
    private var loaded = false

    fun ensureLoaded() {
        synchronized(sync) {
            if (loaded) return
            loaded = true
            parse(InuConfig.PILL_STACK_RATE_INSTANCES.value)
            if (instances.isEmpty()) {
                // entiny: first run gets one default pill so Pill Stack isn't empty out of the box.
                val instance = Instance(FIRST_ID, "BTC", PillCurrencies.AUTO)
                instances[instance.id] = instance
                persist()
            }
        }
        for (instance in getAll()) PillRegistry.register(describe(instance))
    }

    private fun parse(raw: String) {
        instances.clear()
        if (raw.isBlank()) return
        for (part in raw.split(";")) {
            val entry = part.trim()
            val colon = entry.indexOf(':')
            val arrow = entry.indexOf('>')
            if (colon <= 0 || arrow <= colon) continue
            val id = entry.substring(0, colon).trim().toIntOrNull() ?: continue
            if (id < FIRST_ID || instances.containsKey(id)) continue
            val from = normalizeBase(entry.substring(colon + 1, arrow))
            val to = PillCurrencies.normalize(entry.substring(arrow + 1))
            if (from.isEmpty() || to.isEmpty()) continue
            instances[id] = Instance(id, from, to)
        }
    }

    private fun persist() {
        val builder = StringBuilder()
        for (instance in instances.values) {
            if (builder.isNotEmpty()) builder.append(';')
            builder.append(instance.id).append(':').append(instance.from).append('>').append(instance.to)
        }
        InuConfig.PILL_STACK_RATE_INSTANCES.value = builder.toString()
    }

    fun normalizeBase(code: String?): String {
        val normalized = PillCurrencies.normalize(code)
        return if (normalized == "GRAM") "TON" else normalized
    }

    fun getAll(): List<Instance> {
        ensureLoaded()
        synchronized(sync) { return ArrayList(instances.values) }
    }

    fun get(id: Int): Instance? {
        ensureLoaded()
        synchronized(sync) { return instances[id] }
    }

    fun canAddMore(): Boolean = getAll().size < MAX_COUNT

    fun create(from: String, to: String): Instance? {
        ensureLoaded()
        val created: Instance
        synchronized(sync) {
            if (instances.size >= MAX_COUNT) return null
            var id = FIRST_ID
            while (instances.containsKey(id)) id++
            created = Instance(id, normalizeBase(from), PillCurrencies.normalize(to))
            instances[id] = created
            persist()
        }
        PillRegistry.register(describe(created))
        return created
    }

    fun remove(id: Int) {
        ensureLoaded()
        synchronized(sync) {
            if (instances.remove(id) == null) return
            persist()
        }
        PillRegistry.unregister(id)
    }

    fun setPair(id: Int, from: String, to: String) {
        val instance = get(id) ?: return
        synchronized(sync) {
            instance.from = normalizeBase(from)
            instance.to = PillCurrencies.normalize(to)
            persist()
        }
        PillRegistry.register(describe(instance))
    }

    fun describe(instance: Instance) = PillRegistry.PillInfo(instance.id, getBaseIcon(instance.from)) { context, resourcesProvider ->
        RatePill(context, resourcesProvider, instance.id)
    }

    fun getLabel(instance: Instance): CharSequence =
        "${getBaseLabel(instance.from)} → ${PillCurrencies.getTargetCurrencyLabel(instance.to)}"

    fun getBaseLabel(code: String): String = if (code == "XAU") "Gold" else code

    fun getBaseIcon(code: String): Int = when (code) {
        "XAU" -> R.drawable.pillstack_gold
        "BTC" -> R.drawable.pillstack_btc
        "ETH" -> R.drawable.pillstack_eth
        "TON" -> R.drawable.mini_gram_16
        "EUR" -> R.drawable.pillstack_eur
        else -> R.drawable.pillstack_usd
    }

    fun getBaseColorTop(code: String): Int = when (code) {
        "XAU" -> 0xFFE0A72E.toInt()
        "BTC" -> 0xFFF7931A.toInt()
        "ETH" -> 0xFF8A92B2.toInt()
        "TON" -> 0xFF54A9EB.toInt()
        "EUR" -> 0xFF2F80ED.toInt()
        else -> 0xFF2FB86E.toInt()
    }

    fun getBaseColorBottom(code: String): Int = when (code) {
        "XAU" -> 0xFFB6821F.toInt()
        "BTC" -> 0xFFC97316.toInt()
        "ETH" -> 0xFF62688A.toInt()
        "TON" -> 0xFF1488E1.toInt()
        "EUR" -> 0xFF1B5FC1.toInt()
        else -> 0xFF1E8E52.toInt()
    }

    fun getScale(code: String): Int = if (code == "TON") 3 else 2

    fun defaultBase(): String = "BTC"
}
