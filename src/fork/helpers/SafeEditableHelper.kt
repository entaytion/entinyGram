package desu.inugram.helpers

import android.text.Editable
import android.text.SpannableStringBuilder
import android.widget.TextView

object SafeEditableHelper {
    private val factory = object : Editable.Factory() {
        override fun newEditable(source: CharSequence): Editable = SafeSpannableStringBuilder(source)
    }

    @JvmStatic
    fun install(view: TextView) {
        view.setEditableFactory(factory)
        view.setText(view.text, TextView.BufferType.EDITABLE)
    }
}

// entiny: framework getSpans can throw ArrayIndexOutOfBounds on a stale span tree (IME cursor anchor updates)
private class SafeSpannableStringBuilder(source: CharSequence) : SpannableStringBuilder(source) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getSpans(queryStart: Int, queryEnd: Int, kind: Class<T>?): Array<T> =
        try {
            super.getSpans(queryStart, queryEnd, kind)
        } catch (_: ArrayIndexOutOfBoundsException) {
            java.lang.reflect.Array.newInstance(kind ?: Any::class.java, 0) as Array<T>
        }
}
