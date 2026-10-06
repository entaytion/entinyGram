package desu.inugram.ui

import android.content.Context
import android.content.DialogInterface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.AlertDialogDecor
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.LayoutHelper
fun showInputDialog(
    fragment: BaseFragment,
    title: CharSequence,
    hint: CharSequence? = null,
    initialText: CharSequence? = null,
    selectAll: Boolean = false,
    inputType: Int = InputType.TYPE_CLASS_TEXT,
    multiline: Boolean = false,
    onSubmit: (String) -> Boolean,
): AlertDialog? {
    val ctx = fragment.parentActivity ?: return null
    return showInputDialog(
        ctx, fragment.resourceProvider, title, hint, initialText, selectAll, inputType,
        multiline = multiline,
        showDialog = { fragment.showDialog(it) },
        onSubmit = onSubmit,
    )
}

fun showInputDialog(
    ctx: Context,
    theme: Theme.ResourcesProvider? = null,
    title: CharSequence,
    hint: CharSequence? = null,
    initialText: CharSequence? = null,
    selectAll: Boolean = false,
    inputType: Int = InputType.TYPE_CLASS_TEXT,
    adaptive: Boolean = false,
    multiline: Boolean = false,
    showDialog: (AlertDialog) -> Unit = { it.show() },
    onSubmit: (String) -> Boolean,
): AlertDialog {
    val typedInputType = inputType
    val editText = EditTextBoldCursor(ctx).apply {
        background = null
        setLineColors(
            Theme.getColor(Theme.key_dialogInputField, theme),
            Theme.getColor(Theme.key_dialogInputFieldActivated, theme),
            Theme.getColor(Theme.key_text_RedBold, theme),
        )
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        setTextColor(Theme.getColor(Theme.key_dialogTextBlack, theme))
        setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, theme))
        if (multiline) {
            setSingleLine(false)
            minLines = 4
            maxLines = 8
            this.inputType = typedInputType or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        } else {
            maxLines = 1
            setLines(1)
            this.inputType = typedInputType
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        gravity = Gravity.LEFT or Gravity.TOP
        setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, theme))
        setCursorSize(AndroidUtilities.dp(20f))
        setCursorWidth(1.5f)
        setPadding(0, AndroidUtilities.dp(4f), 0, if (multiline) AndroidUtilities.dp(10f) else 0)
        if (hint != null) this.hint = hint
        if (initialText != null) {
            setText(initialText)
            if (selectAll) setSelection(0, initialText.length)
        }
    }
    val container = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            editText,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, if (multiline) LayoutHelper.WRAP_CONTENT else 36, Gravity.TOP or Gravity.LEFT, 24, 6, 24, 0),
        )
    }
    val submit = submit@{
        val text = editText.text?.toString()?.trim().orEmpty()
        val ok = onSubmit(text)
        if (!ok) AndroidUtilities.shakeView(editText)
        ok
    }
    val builder = if (adaptive) AlertDialogDecor.Builder(ctx, theme) else AlertDialog.Builder(ctx, theme)
    val dialog = builder
        .setTitle(title)
        .setView(container)
        .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
        .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ -> }
        .create()
    editText.setOnEditorActionListener { _, _, _ ->
        if (submit()) dialog.dismiss()
        true
    }
    dialog.setOnShowListener {
        AndroidUtilities.runOnUIThread {
            editText.requestFocus()
            AndroidUtilities.showKeyboard(editText)
        }
    }
    if (adaptive) {
        (dialog as AlertDialogDecor).showDelayed(250)
    } else {
        showDialog(dialog)
    }
    dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
        if (submit()) dialog.dismiss()
    }
    return dialog
}
