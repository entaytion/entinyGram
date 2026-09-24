package desu.inugram.helpers.diag

import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.RequestDelegate
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import java.lang.reflect.Modifier

// entiny: script-driven probes for .entinylog profiles -- NotificationCenter events, server requests /
// responses, server-pushed updates and state dumps, all selected by name so no rebuild is needed
object DiagProbes {

    class Spec(
        val events: List<String> = emptyList(),
        val requests: List<String> = emptyList(),
        val updates: List<String> = emptyList(),
        val dumpWhat: List<String> = emptyList(),
        val dumpOn: Set<String> = emptySet(),
        val dumpEverySec: Int = 0,
    ) {
        val isEmpty get() = events.isEmpty() && requests.isEmpty() && updates.isEmpty() && dumpWhat.isEmpty()

        fun toJson() = JSONObject().apply {
            put("events", JSONArray(events))
            put("requests", JSONArray(requests))
            put("updates", JSONArray(updates))
            put("dump", JSONObject().apply {
                put("what", JSONArray(dumpWhat))
                put("on", JSONArray(dumpOn.toList()))
                put("every", dumpEverySec)
            })
        }

        companion object {
            fun from(json: JSONObject?): Spec {
                if (json == null) return Spec()
                val dump = json.optJSONObject("dump")
                return Spec(
                    events = json.optJSONArray("events").strings(),
                    requests = json.optJSONArray("requests").strings(),
                    updates = json.optJSONArray("updates").strings(),
                    dumpWhat = dump?.optJSONArray("what").strings(),
                    dumpOn = dump?.optJSONArray("on").strings().toSet(),
                    dumpEverySec = (dump?.optInt("every", 0) ?: 0).coerceIn(0, 24 * 3600),
                )
            }

            private fun JSONArray?.strings(): List<String> =
                if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
        }
    }

    private const val CAT = "probe"
    private const val MIN_DUMP_EVERY_SEC = 30

    @Volatile
    private var spec: Spec = Spec()
    private val observers = ArrayList<Pair<NotificationCenter, Pair<NotificationCenter.NotificationCenterDelegate, Int>>>()
    private var dumpTimer: Runnable? = null

    fun apply(newSpec: Spec, reason: String) {
        AndroidUtilities.runOnUIThread {
            detachEvents()
            spec = newSpec
            attachEvents()
            scheduleDumps()
            if (reason in newSpec.dumpOn) dumpAll(reason)
        }
    }

    fun clear() = apply(Spec(), "off")

    fun dumpNow() = AndroidUtilities.runOnUIThread { dumpAll("manual") }

    // region events

    private val eventNames: Map<String, Int> by lazy {
        NotificationCenter::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .associate { it.name to it.getInt(null) }
    }
    private val eventIds: Map<Int, String> by lazy { eventNames.entries.associate { (k, v) -> v to k } }

    private fun attachEvents() {
        if (spec.events.isEmpty()) return
        val ids = eventNames.filterKeys { name -> spec.events.any { glob(it, name) } }.values
        if (ids.isEmpty()) {
            write("no NotificationCenter events match ${spec.events}")
            return
        }
        val centers = (0 until UserConfig.MAX_ACCOUNT_COUNT)
            .filter { UserConfig.getInstance(it).isClientActivated }
            .map { it to NotificationCenter.getInstance(it) } + (-1 to NotificationCenter.getGlobalInstance())
        for ((account, center) in centers) {
            val delegate = NotificationCenter.NotificationCenterDelegate { id, acc, args ->
                write("event ${eventIds[id] ?: id} acc=${if (account < 0) "global" else acc} ${args.joinToString(" | ") { DiagDump.value(it) }}")
            }
            for (id in ids) {
                center.addObserver(delegate, id)
                observers.add(center to (delegate to id))
            }
        }
        write("listening to ${ids.size} events: ${ids.mapNotNull { eventIds[it] }.sorted()}")
    }

    private fun detachEvents() {
        for ((center, pair) in observers) center.removeObserver(pair.first, pair.second)
        observers.clear()
    }

    // endregion

    // region requests & updates (called from the diag-probes patch)

    @JvmStatic
    fun wrapRequest(request: TLObject?, account: Int, onComplete: RequestDelegate?): RequestDelegate? {
        val patterns = spec.requests
        if (request == null || patterns.isEmpty()) return onComplete
        val name = request.javaClass.simpleName
        if (patterns.none { glob(it, name) }) return onComplete
        val started = System.currentTimeMillis()
        write("request acc=$account ${DiagDump.tl(request)}")
        return RequestDelegate { response, error ->
            val took = System.currentTimeMillis() - started
            if (error != null) {
                write("response acc=$account $name error ${error.code} ${error.text} (${took}ms)")
            } else {
                write("response acc=$account $name -> ${DiagDump.tl(response)} (${took}ms)")
            }
            onComplete?.run(response, error)
        }
    }

    @JvmStatic
    fun onUpdates(account: Int, updates: List<TLRPC.Update>?) {
        val patterns = spec.updates
        if (updates.isNullOrEmpty() || patterns.isEmpty()) return
        for (update in updates) {
            val name = update.javaClass.simpleName
            if (patterns.any { glob(it, name) }) write("update acc=$account ${DiagDump.tl(update)}")
        }
    }

    // endregion

    // region dumps

    private fun scheduleDumps() {
        dumpTimer?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        dumpTimer = null
        val every = spec.dumpEverySec
        if (every <= 0 || spec.dumpWhat.isEmpty()) return
        val delay = maxOf(every, MIN_DUMP_EVERY_SEC) * 1000L
        val timer = object : Runnable {
            override fun run() {
                if (DiagLog.current() == null) return
                dumpAll("timer")
                AndroidUtilities.runOnUIThread(this, delay)
            }
        }
        dumpTimer = timer
        AndroidUtilities.runOnUIThread(timer, delay)
    }

    private fun dumpAll(reason: String) {
        for (what in spec.dumpWhat) {
            val text = try {
                DiagDump.state(what)
            } catch (e: Throwable) {
                "failed: ${e.javaClass.simpleName} ${e.message}"
            }
            write("dump[$reason] $what: $text")
        }
    }

    // endregion

    // "*" wildcards anywhere, case-insensitive; "account.registerDevice" also matches TL class "registerDevice"
    private fun glob(pattern: String, name: String): Boolean {
        val p = pattern.substringAfterLast('.')
        val regex = Regex(p.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        return regex.matches(name)
    }

    private fun write(message: String) = DiagLog.forceWrite(CAT, message)
}
