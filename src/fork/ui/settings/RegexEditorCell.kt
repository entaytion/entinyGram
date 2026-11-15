package desu.inugram.ui.settings

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.helpers.chat.RegexFilterHelper
import desu.inugram.helpers.chat.TextCleanerHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.LayoutHelper

class RegexEditorCell(
    context: Context,
    name: String,
    pattern: String,
    action: Int,
    private val onChanged: () -> Unit,
) : LinearLayout(context) {

    private val nameInput = field(false, false)
    private val patternInput = field(true, true)
    private val validation = note(14f)
    private val blockChip = chip(LocaleController.getString(R.string.InuRegexActionBlock))
    private val cleanChip = chip(LocaleController.getString(R.string.InuRegexActionClean))
    private val actionInfo = note(13f)
    private val testInput = field(true, false)
    private val resultLabel = label(LocaleController.getString(R.string.InuRegexTestResult))
    private val result = TextView(context)

    var action: Int = action
        private set

    var caseInsensitive: Boolean = true
        set(value) {
            field = value
            refresh()
        }

    var reversed: Boolean = false
        set(value) {
            field = value
            refresh()
        }

    val nameText: String get() = nameInput.text.toString().trim()
    val patternText: String get() = patternInput.text.toString().trim('\n', '\r')

    init {
        orientation = VERTICAL
        setPadding(dp(21f), dp(12f), dp(21f), dp(16f))

        addView(label(LocaleController.getString(R.string.InuRegexName)))
        nameInput.hint = LocaleController.getString(R.string.InuRegexNameHint)
        nameInput.setText(name)
        addView(nameInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 6f, 0f, 0f))

        addView(label(LocaleController.getString(R.string.InuRegexPattern)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 18f, 0f, 0f))
        patternInput.hint = LocaleController.getString(R.string.InuRegexPatternHint)
        patternInput.typeface = Typeface.MONOSPACE
        patternInput.setText(pattern)
        addView(patternInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 6f, 0f, 0f))
        addView(validation, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2f, 6f, 2f, 0f))

        addView(label(LocaleController.getString(R.string.InuRegexAction)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 18f, 0f, 0f))
        val chips = LinearLayout(context)
        chips.addView(blockChip, LinearLayout.LayoutParams(0, dp(44f), 1f).apply { rightMargin = dp(4f) })
        chips.addView(cleanChip, LinearLayout.LayoutParams(0, dp(44f), 1f).apply { leftMargin = dp(4f) })
        addView(chips, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 8f, 0f, 0f))
        addView(actionInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2f, 8f, 2f, 0f))
        blockChip.setOnClickListener { setAction(RegexFilterHelper.ACTION_BLOCK) }
        cleanChip.setOnClickListener { setAction(RegexFilterHelper.ACTION_CLEAN) }

        addView(label(LocaleController.getString(R.string.InuRegexTest)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 18f, 0f, 0f))
        testInput.hint = LocaleController.getString(R.string.InuRegexTestHint)
        addView(testInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 6f, 0f, 0f))
        addView(resultLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 12f, 0f, 0f))
        result.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        result.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        result.setTextIsSelectable(true)
        result.setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        result.background = Theme.createRoundRectDrawable(dp(10f), Theme.getColor(Theme.key_windowBackgroundGray))
        addView(result, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 6f, 0f, 0f))

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refresh()
                onChanged()
            }
        }
        nameInput.addTextChangedListener(watcher)
        patternInput.addTextChangedListener(watcher)
        testInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refresh()
                testInput.post { reveal(testInput, 160f) }
            }
        })
        revealOnFocus(nameInput, 0f)
        revealOnFocus(patternInput, 48f)
        revealOnFocus(testInput, 160f)
        refresh()
    }

    // entiny: the list does not follow a focused field by itself, so scroll it above the keyboard
    private fun reveal(view: View, extraDp: Float) {
        view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height + dp(extraDp)))
    }

    private fun revealOnFocus(input: EditTextBoldCursor, extraDp: Float) {
        input.setOnFocusChangeListener { v, focused ->
            if (!focused) return@setOnFocusChangeListener
            v.postDelayed({ reveal(v, extraDp) }, 250)
            v.postDelayed({ reveal(v, extraDp) }, 600)
        }
    }

    fun error(): String? {
        val text = patternText
        if (text.isBlank()) return null
        val check = RegexFilterHelper.check(text, caseInsensitive)
        return when {
            check.error != null -> LocaleController.formatString(R.string.InuRegexPatternError, check.error)
            check.matchesEmpty -> LocaleController.getString(R.string.InuRegexPatternMatchesEmpty)
            else -> null
        }
    }

    private fun setAction(value: Int) {
        if (action == value) return
        action = value
        refresh()
        onChanged()
        onActionChanged?.invoke()
    }

    var onActionChanged: (() -> Unit)? = null

    private fun refresh() {
        val text = patternText
        val check = if (text.isBlank()) null else RegexFilterHelper.check(text, caseInsensitive)
        val problem = error()
        validation.visibility = if (check == null) GONE else VISIBLE
        validation.text = problem ?: LocaleController.getString(R.string.InuRegexPatternValid)
        validation.setTextColor(
            Theme.getColor(if (problem != null) Theme.key_text_RedBold else Theme.key_windowBackgroundWhiteGrayText)
        )

        paintChip(blockChip, action == RegexFilterHelper.ACTION_BLOCK)
        paintChip(cleanChip, action == RegexFilterHelper.ACTION_CLEAN)
        actionInfo.text = LocaleController.getString(
            if (action == RegexFilterHelper.ACTION_CLEAN) R.string.InuRegexActionCleanInfo else R.string.InuRegexActionBlockInfo
        )

        val sample = testInput.text.toString()
        val compiled = if (problem == null) check?.pattern else null
        if (sample.isEmpty() || compiled == null) {
            resultLabel.visibility = GONE
            result.visibility = GONE
            return
        }
        resultLabel.visibility = VISIBLE
        result.visibility = VISIBLE
        result.text = if (action == RegexFilterHelper.ACTION_CLEAN) {
            TextCleanerHelper.preview(sample, listOf(compiled)) ?: LocaleController.getString(R.string.InuRegexTestNoMatch)
        } else {
            val matched = try {
                compiled.matcher(sample).find()
            } catch (_: Exception) {
                false
            } != reversed
            LocaleController.getString(if (matched) R.string.InuRegexTestBlocked else R.string.InuRegexTestPassed)
        }
    }

    private fun dp(v: Float) = AndroidUtilities.dp(v)

    private fun label(text: String) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        typeface = AndroidUtilities.bold()
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader))
    }

    private fun note(size: Float) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
    }

    private fun chip(text: String) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        typeface = AndroidUtilities.bold()
        setPadding(dp(8f), 0, dp(8f), 0)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun paintChip(view: TextView, selected: Boolean) {
        view.background = Theme.createRoundRectDrawable(
            dp(12f),
            Theme.getColor(if (selected) Theme.key_featuredStickers_addButton else Theme.key_windowBackgroundGray),
        )
        view.setTextColor(
            Theme.getColor(if (selected) Theme.key_featuredStickers_buttonText else Theme.key_windowBackgroundWhiteBlackText)
        )
    }

    private fun field(multiline: Boolean, noSuggestions: Boolean) = EditTextBoldCursor(context).apply {
        background = Theme.createRoundRectDrawable(dp(10f), Theme.getColor(Theme.key_windowBackgroundGray))
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))
        setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        setCursorSize(dp(20f))
        setCursorWidth(1.5f)
        setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        gravity = Gravity.LEFT or Gravity.TOP
        var type = InputType.TYPE_CLASS_TEXT
        if (noSuggestions) type = type or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        if (multiline) {
            setSingleLine(false)
            minLines = if (noSuggestions) 3 else 2
            maxLines = 10
            type = type or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        } else {
            setSingleLine(true)
            maxLines = 1
        }
        inputType = type
    }
}
