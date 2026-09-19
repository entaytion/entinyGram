package desu.inugram.helpers.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.ChatActivityEnterView
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScaleStateListAnimator
import org.telegram.ui.Components.TranslateAlert2
import org.telegram.ui.Components.TranslateAlert3
import desu.inugram.InuConfig
import desu.inugram.helpers.theme.NonIslandHelper
import desu.inugram.helpers.translate.TranslateHelper

object ChatInputMenuHelper {
    private const val MENU_BUTTON_TAG = "inu_chat_input_menu_button"

    @JvmStatic
    fun attach(enterView: ChatActivityEnterView) {
        val container = enterView.messageEditTextContainer ?: return
        val context = enterView.context ?: return
        val theme = enterView.parentFragment?.resourceProvider

        val button = ImageView(context).apply {
            tag = MENU_BUTTON_TAG
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.ic_ab_other)
            contentDescription = LocaleController.getString(R.string.AccDescrMoreOptions)
            background = NonIslandHelper.createInputButtonSelector(Theme.getColor(Theme.key_listSelector, theme))
            setColorFilter(PorterDuffColorFilter(Theme.getColor(Theme.key_glass_defaultIcon, theme), PorterDuff.Mode.SRC_IN))
            visibility = View.GONE
            ScaleStateListAnimator.apply(this)
            setOnClickListener { showMenu(enterView, this) }
        }

        val size = ChatActivityEnterView.DEFAULT_HEIGHT
        container.addView(button, LayoutHelper.createFrame(size, size, Gravity.BOTTOM or Gravity.RIGHT))
    }

    @JvmStatic
    fun isMenuButtonVisible(enterView: ChatActivityEnterView): Boolean {
        if (!InuConfig.INPUT_TRANSLATE.value) return false
        val button = enterView.messageEditTextContainer?.findViewWithTag<View>(MENU_BUTTON_TAG) ?: return false
        return button.visibility == View.VISIBLE
    }

    @JvmStatic
    @JvmOverloads
    fun updateVisibility(enterView: ChatActivityEnterView, animated: Boolean = false) {
        val container = enterView.messageEditTextContainer ?: return
        val button = container.findViewWithTag<View>(MENU_BUTTON_TAG) ?: return

        if (!InuConfig.INPUT_TRANSLATE.value) {
            if (button.visibility != View.GONE) {
                button.visibility = View.GONE
            }
            return
        }

        val text = enterView.fieldText
        val shouldShow = !TextUtils.isEmpty(text)
        val isCurrentlyVisible = button.visibility == View.VISIBLE

        if (shouldShow == isCurrentlyVisible) return

        if (shouldShow) {
            button.visibility = View.VISIBLE
            if (animated) {
                button.alpha = 0f
                button.scaleX = 0.5f
                button.scaleY = 0.5f
                button.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150)
                    .setListener(null)
                    .start()
            } else {
                button.alpha = 1f
                button.scaleX = 1f
                button.scaleY = 1f
            }
        } else {
            if (animated) {
                button.animate()
                    .alpha(0f)
                    .scaleX(0.5f)
                    .scaleY(0.5f)
                    .setDuration(150)
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            button.visibility = View.GONE
                        }
                    })
                    .start()
            } else {
                button.visibility = View.GONE
            }
        }
    }

    private fun showMenu(enterView: ChatActivityEnterView, anchor: View) {
        val fragment = enterView.parentFragment ?: return
        val activity = fragment as? ChatActivity
        val options = ItemOptions.makeOptions(fragment, anchor)

        if (activity != null) {
            val isPreviewActive = activity.foundWebPage != null || enterView.isMessageWebPageSearchEnabled
            val linkTitle = if (isPreviewActive) {
                LocaleController.getString(R.string.InuDisableLinkPreview)
            } else {
                LocaleController.getString(R.string.InuEnableLinkPreview)
            }
            options.add(R.drawable.msg_link2, linkTitle) {
                activity.inu_toggleLinkPreview()
            }
        }

        val toLang = TranslateHelper.currentTargetLanguage().ifEmpty {
            TranslateAlert2.getToLanguage().orEmpty().ifEmpty { "en" }
        }
        val transTitle = "${LocaleController.getString(R.string.TranslateMessage)} (${toLang.uppercase()})"
        options.add(R.drawable.msg_translate, transTitle) {
            translateText(enterView, toLang)
        }

        options.add(R.drawable.msg_edit, LocaleController.getString(R.string.InuReplaceText)) {
            showReplaceDialog(enterView)
        }

        options.add(R.drawable.msg_input_attach2, LocaleController.getString(R.string.AttachMenu)) {
            enterView.inu_openAttach()
        }

        options.show()
    }

    private fun translateText(enterView: ChatActivityEnterView, toLang: String) {
        val editText = enterView.messageEditText ?: return
        val selStart = editText.selectionStart
        val selEnd = editText.selectionEnd
        val hasSelection = selStart in 0 until selEnd
        val textToTranslate = if (hasSelection) {
            editText.text.subSequence(selStart, selEnd)
        } else {
            enterView.fieldText
        }
        if (TextUtils.isEmpty(textToTranslate)) return

        val fragment = enterView.parentFragment
        TranslateAlert3(enterView.context, fragment?.resourceProvider)
            .setToLanguage(toLang)
            .setText(textToTranslate)
            .setOnUse { translated ->
                if (hasSelection) {
                    editText.text.replace(selStart, selEnd, translated)
                } else {
                    enterView.setFieldText(translated.toString())
                }
            }
            .show()
    }

    private fun showReplaceDialog(enterView: ChatActivityEnterView) {
        val context = enterView.context ?: return
        val theme = enterView.parentFragment?.resourceProvider

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(8f), dp(24f), dp(8f))
        }

        val findField = EditTextBoldCursor(context).apply {
            background = null
            setLineColors(
                Theme.getColor(Theme.key_dialogInputField, theme),
                Theme.getColor(Theme.key_dialogInputFieldActivated, theme),
                Theme.getColor(Theme.key_text_RedBold, theme),
            )
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack, theme))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, theme))
            hint = LocaleController.getString(R.string.InuReplaceTextFind)
            isSingleLine = true
            setCursorColor(Theme.getColor(Theme.key_dialogInputFieldActivated, theme))
            setCursorSize(dp(20f))
            setCursorWidth(1.5f)
        }

        val replaceField = EditTextBoldCursor(context).apply {
            background = null
            setLineColors(
                Theme.getColor(Theme.key_dialogInputField, theme),
                Theme.getColor(Theme.key_dialogInputFieldActivated, theme),
                Theme.getColor(Theme.key_text_RedBold, theme),
            )
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack, theme))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, theme))
            hint = LocaleController.getString(R.string.InuReplaceTextWith)
            isSingleLine = true
            setCursorColor(Theme.getColor(Theme.key_dialogInputFieldActivated, theme))
            setCursorSize(dp(20f))
            setCursorWidth(1.5f)
        }

        val editText = enterView.messageEditText
        if (editText != null && editText.selectionStart in 0 until editText.selectionEnd) {
            findField.setText(editText.text.subSequence(editText.selectionStart, editText.selectionEnd))
            findField.setSelection(findField.length())
        }

        layout.addView(findField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 12f))
        layout.addView(replaceField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        AlertDialog.Builder(context, theme)
            .setTitle(LocaleController.getString(R.string.InuReplaceText))
            .setView(layout)
            .setPositiveButton(LocaleController.getString(R.string.InuReplaceTextApply)) { _, _ ->
                val find = findField.text?.toString().orEmpty()
                val replacement = replaceField.text?.toString().orEmpty()
                if (find.isNotEmpty()) {
                    val current = enterView.fieldText?.toString().orEmpty()
                    if (current.contains(find)) {
                        enterView.setFieldText(current.replace(find, replacement))
                    }
                }
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show()
    }
}

