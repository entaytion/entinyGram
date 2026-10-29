package desu.inugram.ui.settings

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.URLSpan
import android.util.Patterns
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

// entiny: full-screen editor for the forwarded text, inspired by Telegraph's special forward
class ForwardEditorActivity(
    private val original: CharSequence,
    initial: CharSequence,
    private val isMedia: Boolean,
    private val onResult: (CharSequence?) -> Unit,
) : SettingsPageActivity() {

    private var current: CharSequence = initial
    private var finished = false
    private var bubble: TextView? = null
    private var editor: EditTextBoldCursor? = null
    private var previewRow: View? = null
    private var editorRow: View? = null

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuForwardPro)

    override fun createView(context: Context): View {
        return super.createView(context).also {
            actionBar.createMenu().addItem(MENU_DONE, R.drawable.ic_ab_done)
            actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
                override fun onItemClick(id: Int) {
                    when (id) {
                        -1 -> finishFragment()
                        MENU_DONE -> done()
                    }
                }
            })
        }
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val context = context ?: return
        items.add(UItem.asCustom(previewRow ?: createPreview(context).also { previewRow = it }))
        items.add(UItem.asShadow(null))
        items.add(UItem.asCustom(editorRow ?: createEditor(context).also { editorRow = it }))
        items.add(UItem.asShadow(null))
        items.add(UItem.asButton(BUTTON_RESET, R.drawable.msg_retry, LocaleController.getString(R.string.Reset)))
        items.add(UItem.asButton(BUTTON_LINKS, R.drawable.msg_link, LocaleController.getString(R.string.InuForwardProRemoveLinks)))
        items.add(UItem.asButton(BUTTON_CLEAR, R.drawable.msg_delete, LocaleController.getString(R.string.Clear)))
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_RESET -> editor?.setText(original)
            BUTTON_LINKS -> editor?.setText(withoutLinks(current))
            BUTTON_CLEAR -> editor?.setText("")
        }
        editor?.setSelection(editor?.text?.length ?: 0)
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        if (!finished) {
            finished = true
            onResult(null)
        }
    }

    private fun done() {
        if (!isMedia && current.trim().isEmpty()) {
            Toast.makeText(context, LocaleController.getString(R.string.InuForwardProEmptyNotice), Toast.LENGTH_SHORT).show()
            return
        }
        finished = true
        val result = SpannableStringBuilder(current)
        finishFragment()
        onResult(result)
    }

    private fun createPreview(context: Context): View {
        val text = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            setTextColor(Theme.getColor(Theme.key_chat_messageTextIn, resourceProvider))
            background = Theme.createRoundRectDrawable(dp(14f), Theme.getColor(Theme.key_chat_inBubble, resourceProvider))
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            setText(current)
        }
        bubble = text
        return FrameLayout(context).apply {
            setPadding(dp(16f), dp(12f), dp(48f), dp(12f))
            addView(text, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.LEFT))
        }
    }

    private fun createEditor(context: Context): View {
        val edit = EditTextBoldCursor(context).apply {
            background = null
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider))
            setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, resourceProvider))
            hint = LocaleController.getString(if (isMedia) R.string.InuForwardProPlaceholder else R.string.Message)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            maxLines = 12
            gravity = Gravity.LEFT or Gravity.TOP
            setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider))
            setCursorSize(dp(20f))
            setCursorWidth(1.5f)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            setText(current)
            setSelection(text?.length ?: 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    current = SpannableStringBuilder(s ?: "")
                    bubble?.text = current
                }
            })
        }
        editor = edit
        return FrameLayout(context).apply {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider))
            addView(edit, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun withoutLinks(text: CharSequence): CharSequence {
        val result = SpannableStringBuilder(text)
        for (span in result.getSpans(0, result.length, URLSpan::class.java)) result.removeSpan(span)
        val matcher = Patterns.WEB_URL.matcher(result)
        val ranges = ArrayList<IntRange>()
        while (matcher.find()) ranges.add(matcher.start() until matcher.end())
        for (range in ranges.asReversed()) result.delete(range.first, range.last + 1)
        return result
    }

    companion object {
        private const val MENU_DONE = 1
        private val BUTTON_RESET = InuUtils.generateId()
        private val BUTTON_LINKS = InuUtils.generateId()
        private val BUTTON_CLEAR = InuUtils.generateId()
    }
}
