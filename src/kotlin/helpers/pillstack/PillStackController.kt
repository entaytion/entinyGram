package desu.inugram.helpers.pillstack

import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import desu.inugram.InuConfig
import org.telegram.messenger.LocaleController
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

// entiny: glues a row of PillStackView "slots" directly onto the search field, exteraGram/exteraless-style -- 6dp margins, own FrameLayout child, not routed through the shared additionalIconsLayout (that's sized for one small icon and clips a wide pill's tail).
class PillStackController(private val container: FrameLayout, private val editText: EditText?) {

    private var rowLayout: LinearLayout? = null
    private val slots = ArrayList<PillStackView>()
    private var attached = false

    init {
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                attached = true
                rebuild()
            }

            override fun onViewDetachedFromWindow(v: View) {
                attached = false
            }
        })

        editText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                updateVisibility()
            }
        })
        // Tapping the search field focuses it immediately, before any text is typed -- watching
        // only text (below) left the pills sitting on top of the "Search Chats" hint the whole
        // time the keyboard was up and empty. A global focus listener (not editText's own
        // setOnFocusChangeListener) so this doesn't clobber whatever focus handling stock/other
        // fork code already attaches to the same EditText.
        editText?.viewTreeObserver?.addOnGlobalFocusChangeListener { oldFocus, newFocus ->
            if (newFocus === editText || oldFocus === editText) updateVisibility()
        }

        if (container.isAttachedToWindow) {
            attached = true
            rebuild()
        }
    }

    fun isAttached(): Boolean = attached

    fun rebuild() {
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
            row = LinearLayout(container.context)
            row.orientation = LinearLayout.HORIZONTAL
            // entiny: row itself is MATCH_PARENT height (like exteraless's single stackView); center the WRAP_CONTENT slots within it.
            row.gravity = Gravity.CENTER_VERTICAL
            container.addView(
                row, LayoutHelper.createFrame(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT.toFloat(),
                    (if (LocaleController.isRTL) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL,
                    6f, 0f, 6f, 0f
                )
            )
            rowLayout = row
        }

        while (slots.size > slotCount) {
            val extra = slots.removeAt(slots.size - 1)
            extra.clearPills()
            row.removeView(extra)
        }
        while (slots.size < slotCount) {
            val slot = PillStackView(container.context)
            slots.add(slot)
            row.addView(
                slot,
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, if (slots.size > 1) 2f else 0f, 0f)
            )
        }

        val lastActive = readLastActive()
        val resourcesProvider = container as? Theme.ResourcesProvider
        var anyPills = false
        for (i in 0 until slotCount) {
            val slot = slots[i]
            slot.onCurrentPillChanged = null
            slot.clearPills()
            for (id in buckets[i]) {
                val pill = PillRegistry.createPill(id, container.context, resourcesProvider) ?: continue
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

    private fun readLastActive(): List<Int> =
        InuConfig.PILL_STACK_LAST_ACTIVE.value.split(",").mapNotNull { it.toIntOrNull() }

    private fun persistLastActive() {
        InuConfig.PILL_STACK_LAST_ACTIVE.value = slots.joinToString(",") { (it.getCurrentPillId() ?: -1).toString() }
    }

    private fun removeRow() {
        val row = rowLayout ?: return
        for (slot in slots) slot.clearPills()
        slots.clear()
        container.removeView(row)
        rowLayout = null
    }

    // entiny: pills hide as soon as search is focused (even before typing) so they don't sit on
    // top of the "Search Chats" hint, and stay hidden while there's text.
    private fun updateVisibility() {
        val searchActive = editText?.hasFocus() == true || !editText?.text.isNullOrEmpty()
        for (slot in slots) {
            if (slot.getPillsCount() == 0) continue
            slot.setVisibilityFactor(if (searchActive) 0f else 1f)
        }
    }

    fun updateColors() {
        for (slot in slots) slot.updateColors()
    }
}
