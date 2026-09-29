package desu.inugram.helpers.pillstack

import desu.inugram.InuConfig

// entiny: active and hidden pills layout management (exteraGram pattern)
object PillStackLayout {

    private val sync = Any()
    private val activePills = ArrayList<Int>()
    private val hiddenPills = ArrayList<Int>()
    private var loaded = false

    private val DEFAULT_ACTIVE = listOf(
        PillType.CACHE.id,
        PillType.PROXY.id,
        PillType.CLOCK.id,
        RateInstances.FIRST_ID,
    )

    fun ensureLoaded() {
        synchronized(sync) {
            if (loaded) return
            loaded = true
            RateInstances.ensureLoaded()
            val rawActive = InuConfig.PILL_STACK_ACTIVE_PILLS.value
            val rawHidden = InuConfig.PILL_STACK_HIDDEN_PILLS.value
            if (rawActive.isEmpty() && rawHidden.isEmpty()) {
                val layout = InuConfig.PILL_STACK_LAYOUT.value
                val hasCustom = layout.any { !it.enabled }
                if (hasCustom) {
                    for (entry in layout) {
                        if (entry.enabled) activePills.add(entry.item.id) else hiddenPills.add(entry.item.id)
                    }
                    for (rate in RateInstances.getAll()) {
                        if (!activePills.contains(rate.id) && !hiddenPills.contains(rate.id)) {
                            activePills.add(rate.id)
                        }
                    }
                } else {
                    activePills.addAll(DEFAULT_ACTIVE)
                }
                persist()
            } else {
                activePills.clear()
                activePills.addAll(parseList(rawActive))
                hiddenPills.clear()
                hiddenPills.addAll(parseList(rawHidden).filter { !activePills.contains(it) })
            }
            sanitize()
        }
    }

    private fun parseList(data: String): List<Int> {
        if (data.isBlank()) return emptyList()
        return data.split(",").mapNotNull { it.trim().toIntOrNull() }.distinct()
    }

    private fun serializeList(list: List<Int>): String = list.joinToString(",")

    private fun persist() {
        InuConfig.PILL_STACK_ACTIVE_PILLS.value = serializeList(activePills)
        InuConfig.PILL_STACK_HIDDEN_PILLS.value = serializeList(hiddenPills)
    }

    fun sanitize() {
        val allKnown = (PillType.entries.map { it.id } + RateInstances.getAll().map { it.id }).toSet()
        activePills.retainAll { allKnown.contains(it) }
        hiddenPills.retainAll { allKnown.contains(it) && !activePills.contains(it) }
        for (id in allKnown) {
            if (!activePills.contains(id) && !hiddenPills.contains(id)) {
                hiddenPills.add(id)
            }
        }
    }

    fun getActivePills(): ArrayList<Int> {
        ensureLoaded()
        synchronized(sync) { return ArrayList(activePills) }
    }

    fun getHiddenPills(): ArrayList<Int> {
        ensureLoaded()
        synchronized(sync) { return ArrayList(hiddenPills) }
    }

    fun saveLayout(active: List<Int>, hidden: List<Int>) {
        ensureLoaded()
        synchronized(sync) {
            activePills.clear()
            activePills.addAll(active)
            hiddenPills.clear()
            hiddenPills.addAll(hidden)
            sanitize()
            persist()
        }
    }

    fun setPillActive(id: Int, active: Boolean) {
        ensureLoaded()
        synchronized(sync) {
            activePills.remove(id)
            hiddenPills.remove(id)
            if (active) activePills.add(id) else hiddenPills.add(id)
            persist()
        }
    }

    fun removePill(id: Int) {
        ensureLoaded()
        synchronized(sync) {
            activePills.remove(id)
            hiddenPills.remove(id)
            persist()
        }
    }

    fun resetLayout() {
        ensureLoaded()
        synchronized(sync) {
            activePills.clear()
            activePills.addAll(DEFAULT_ACTIVE)
            hiddenPills.clear()
            sanitize()
            persist()
        }
    }
}
