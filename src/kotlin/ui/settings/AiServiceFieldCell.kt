package desu.inugram.ui.settings

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

/**
 * Modern inline text input row matching the reference design for AI service details.
 * Supports clean borders, hint placeholders, password masking, and right-aligned character count.
 */
class AiServiceFieldCell(
    context: Context,
    hintText: String,
    initialValue: String,
    inputType: Int = InputType.TYPE_CLASS_TEXT,
    showCounter: Boolean = false,
    private val onChanged: (String) -> Unit,
) : LinearLayout(context) {

    private val editText = EditText(context).apply {
        setText(initialValue)
        hint = hintText
        this.inputType = InputType.TYPE_CLASS_TEXT or inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSingleLine = true
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
        background = null
        setPadding(0, AndroidUtilities.dp(12f), 0, AndroidUtilities.dp(12f))
    }

    private val counterView: TextView? = if (showCounter) {
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            text = initialValue.length.toString()
        }
    } else null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(
            AndroidUtilities.dp(20f),
            AndroidUtilities.dp(4f),
            AndroidUtilities.dp(20f),
            AndroidUtilities.dp(4f)
        )

        addView(editText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))
        if (counterView != null) {
            addView(counterView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 8, 0, 0, 0))
        }

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val str = s?.toString().orEmpty()
                counterView?.text = str.length.toString()
                onChanged(str.trim())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    fun setText(text: String) {
        if (editText.text.toString() != text) {
            editText.setText(text)
            editText.setSelection(text.length)
            counterView?.text = text.length.toString()
        }
    }

    fun updateColors() {
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
        counterView?.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2))
    }
}
