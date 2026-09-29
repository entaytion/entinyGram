package desu.inugram.helpers.pillstack

import android.content.SharedPreferences
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import desu.inugram.InuConfig
import desu.inugram.helpers.theme.NonIslandHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.FragmentSearchField
import org.telegram.ui.Components.LayoutHelper

// entiny: glues a row of PillStackView "slots" onto the search field, exteraGram/exteraless-style -- 6dp margins, own child, not routed through the shared additionalIconsLayout (that's sized for one small icon and clips a wide pill's tail). With PILL_STACK_IN_HEADER the same row lives in the action bar menu, left of the icons.
class PillStackController(
    private val searchField: ViewGroup,
    private val headerHost: ViewGroup?,
    private val editText: EditText?,
) {

    private var host: ViewGroup = resolveHost()
    private var rowLayout: LinearLayout? = null
    private val slots = ArrayList<PillStackView>()
    private var attached = false
    // entiny: pills must not pop in while the dialogs screen is covered (e.g. search auto-clear when a chat opens)
    private var screenOn = true
    // entiny: the search screen keeps the search field visible, so focus/text alone is not enough to keep the pills out of it
    private var searchOpen = false

    private val screenProbe = object : View(searchField.context) {
        override fun onVisibilityAggregated(isVisible: Boolean) {
            super.onVisibilityAggregated(isVisible)
            if (screenOn == isVisible) return
            screenOn = isVisible
            updateVisibility()
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key in REFRESH_KEYS) rebuild()
    }

    init {
        host.addView(screenProbe, FrameLayout.LayoutParams(0, 0))
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                attached = true
                InuConfig.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
                rebuild()
            }

            override fun onViewDetachedFromWindow(v: View) {
                attached = false
                InuConfig.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
            }
        })

        editText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                updateVisibility()
            }
        })
        // entiny: use global focus listener instead of setOnFocusChangeListener to avoid clobbering stock handler
        editText?.viewTreeObserver?.addOnGlobalFocusChangeListener { oldFocus, newFocus ->
            if (newFocus === editText || oldFocus === editText) updateVisibility()
        }

        if (host.isAttachedToWindow) {
            attached = true
            InuConfig.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
            rebuild()
        }
    }

    fun isAttached(): Boolean = attached

    fun onSearchStateChanged(open: Boolean) {
        if (searchOpen == open) return
        searchOpen = open
        updateVisibility()
    }

    fun rebuild() {
        moveToHost()
        if (!InuConfig.PILL_STACK_ENABLED.value || (host is FragmentSearchField && NonIslandHelper.globalSearch())) {
            removeRow()
            return
        }
        val ids = PillRegistry.activePillIds()
        if (ids.isEmpty()) {
            removeRow()
            return
        }

        val slotCount = InuConfig.PILL_STACK_VISIBLE_COUNT.value.coerceIn(1, ids.size)
        val buckets = List(slotCount) { ArrayList<Int>() }
        for ((index, id) in ids.withIndex()) buckets[index % slotCount].add(id)

        var row = rowLayout
        if (row == null) {
            row = LinearLayout(host.context)
            row.orientation = LinearLayout.HORIZONTAL
            // entiny: row itself is MATCH_PARENT height (like exteraless's single stackView); center the WRAP_CONTENT slots within it.
            row.gravity = Gravity.CENTER_VERTICAL
            host.addView(row, if (host is LinearLayout) 0 else -1, rowParams())
            rowLayout = row
        }

        while (slots.size > slotCount) {
            val extra = slots.removeAt(slots.size - 1)
            extra.clearPills()
            row.removeView(extra)
        }
        while (slots.size < slotCount) {
            val slot = PillStackView(host.context)
            slots.add(slot)
            row.addView(
                slot,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, if (slots.size > 1) 2f else 0f, 0f)
            )
        }

        val lastActive = readLastActive()
        val resourcesProvider = host as? Theme.ResourcesProvider
        var anyPills = false
        for (i in 0 until slotCount) {
            val slot = slots[i]
            slot.onCurrentPillChanged = null
            slot.clearPills()
            for (id in buckets[i]) {
                val pill = PillRegistry.createPill(id, host.context, resourcesProvider) ?: continue
                slot.addPill(pill)
                anyPills = true
            }
            slot.visibility = if (slot.getPillsCount() == 0) View.GONE else View.VISIBLE
            lastActive.getOrNull(i)?.let { slot.selectPillId(it) }
            slot.onCurrentPillChanged = { persistLastActive() }
        }
        if (!anyPills) {
            removeRow()
            return
        }
        updateVisibility()
    }

    private fun resolveHost(): ViewGroup =
        if (headerHost != null && InuConfig.PILL_STACK_IN_HEADER.value) headerHost else searchField

    // entiny: the host is a setting, so rebuilding after a toggle has to carry the row and its probe over to the other container
    private fun moveToHost() {
        val target = resolveHost()
        if (target === host) return
        removeRow()
        host.removeView(screenProbe)
        host = target
        host.addView(screenProbe, FrameLayout.LayoutParams(0, 0))
    }

    private fun rowParams(): ViewGroup.MarginLayoutParams {
        if (host is LinearLayout) {
            return LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                leftMargin = AndroidUtilities.dp(6f)
                rightMargin = AndroidUtilities.dp(6f)
            }
        }
        return LayoutHelper.createFrame(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT.toFloat(),
            (if (LocaleController.isRTL) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL,
            6f, 0f, 6f, 0f
        )
    }

    private fun readLastActive(): List<Int> =
        InuConfig.PILL_STACK_LAST_ACTIVE.value.split(",").mapNotNull { it.toIntOrNull() }

    private fun persistLastActive() {
        InuConfig.PILL_STACK_LAST_ACTIVE.value = slots.joinToString(",") { (it.getCurrentPillId() ?: -1).toString() }
    }

    private fun removeRow() {
        val row = rowLayout ?: return
        for (slot in slots) slot.clearPills()
        slots.clear()
        host.removeView(row)
        rowLayout = null
    }

    private fun updateVisibility() {
        val searchActive = searchOpen || editText?.hasFocus() == true || !editText?.text.isNullOrEmpty()
        for (slot in slots) {
            if (slot.getPillsCount() == 0) continue
            slot.setVisibilityFactor(if (searchActive || !screenOn) 0f else 1f)
        }
    }

    fun updateColors() {
        for (slot in slots) slot.updateColors()
    }

    companion object {
        private val REFRESH_KEYS = setOf(
            InuConfig.PILL_STACK_ENABLED.key,
            InuConfig.PILL_STACK_VISIBLE_COUNT.key,
            InuConfig.PILL_STACK_LAYOUT.key,
            InuConfig.PILL_STACK_ACTIVE_PILLS.key,
            InuConfig.PILL_STACK_HIDDEN_PILLS.key,
            InuConfig.PILL_STACK_RATE_INSTANCES.key,
            InuConfig.PILL_STACK_IN_HEADER.key,
            InuConfig.WEATHER_USE_CURRENT_LOCATION.key,
            InuConfig.WEATHER_LOCATION.key,
            InuConfig.NON_ISLAND_GLOBAL_SEARCH.key,
        )
    }
}
