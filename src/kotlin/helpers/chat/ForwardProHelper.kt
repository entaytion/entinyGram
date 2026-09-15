package desu.inugram.helpers.chat

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.FragmentSearchField
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ShareAlert
import java.util.WeakHashMap

object ForwardProHelper {

    private class AlertState {
        var hideCaption: Boolean = false
        var silentSend: Boolean = false
        var senderIcon: ImageView? = null
        var captionIcon: ImageView? = null
        var soundIcon: ImageView? = null
        var editButton: ImageView? = null
    }

    private val states = WeakHashMap<ShareAlert, AlertState>()

    private fun getState(alert: ShareAlert): AlertState {
        return states.getOrPut(alert) { AlertState() }
    }

    @JvmStatic
    fun shouldHideCaption(alert: ShareAlert): Boolean {
        if (!InuConfig.FORWARD_PRO.value) return false
        return getState(alert).hideCaption
    }

    @JvmStatic
    fun isSilentSend(alert: ShareAlert): Boolean {
        if (!InuConfig.FORWARD_PRO.value) return false
        return getState(alert).silentSend
    }

    @JvmStatic
    fun getExtraCommentPadding(alert: ShareAlert): Int {
        if (!InuConfig.FORWARD_PRO.value) return 0
        val msgs = alert.sendingMessageObjects
        return if (msgs != null && msgs.isNotEmpty()) dp(46f) else 0
    }

    private fun getIconColor(alert: ShareAlert): Int {
        val theme = alert.resourcesProvider
        return Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, theme)
    }

    @JvmStatic
    fun updateTopAuthorIcon(alert: ShareAlert) {
        val state = states[alert] ?: return
        val icon = state.senderIcon ?: return
        val color = getIconColor(alert)
        icon.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        if (alert.showSendersName) {
            icon.alpha = 1.0f
            ViewCompat.setTooltipText(icon, LocaleController.getString(R.string.HideSendersName))
        } else {
            icon.alpha = 0.35f
            ViewCompat.setTooltipText(icon, LocaleController.getString(R.string.ShowSendersName))
        }
    }

    private fun updateCaptionIcon(alert: ShareAlert) {
        val state = states[alert] ?: return
        val icon = state.captionIcon ?: return
        val color = getIconColor(alert)
        icon.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        if (state.hideCaption) {
            icon.alpha = 0.35f
            ViewCompat.setTooltipText(icon, LocaleController.getString(R.string.InuForwardProShowCaption))
        } else {
            icon.alpha = 1.0f
            ViewCompat.setTooltipText(icon, LocaleController.getString(R.string.InuForwardProHideCaption))
        }
    }

    private fun updateSoundIcon(alert: ShareAlert) {
        val state = states[alert] ?: return
        val icon = state.soundIcon ?: return
        val color = getIconColor(alert)
        icon.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        if (state.silentSend) {
            icon.alpha = 1.0f
            ViewCompat.setTooltipText(icon, LocaleController.getString(R.string.InuForwardProSendWithSound))
        } else {
            icon.alpha = 0.35f
            ViewCompat.setTooltipText(icon, LocaleController.getString(R.string.SendWithoutSound))
        }
    }

    @JvmStatic
    fun attachTopIcons(alert: ShareAlert, searchField: FragmentSearchField) {
        if (!InuConfig.FORWARD_PRO.value) return
        val context = alert.context ?: return
        val state = getState(alert)
        val hasMessages = alert.sendingMessageObjects != null && alert.sendingMessageObjects.isNotEmpty()

        if (hasMessages) {
            // 1. Author toggle
            val senderIcon = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER
                setImageResource(R.drawable.msg_contact)
                background = Theme.createSelectorDrawable(
                    Theme.getColor(Theme.key_listSelector, alert.resourcesProvider),
                    1,
                    dp(16f)
                )
                contentDescription = LocaleController.getString(R.string.ShowSendersName)
                setOnClickListener {
                    alert.showSendersName = !alert.showSendersName
                    updateTopAuthorIcon(alert)
                    val text = LocaleController.getString(
                        if (alert.showSendersName) R.string.ShowSendersName else R.string.HideSendersName
                    )
                    showBulletin(alert, R.drawable.msg_contact, text)
                }
            }
            state.senderIcon = senderIcon
            updateTopAuthorIcon(alert)
            val lp1 = LinearLayout.LayoutParams(dp(32f), dp(32f)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(2f)
            }
            searchField.addAdditionalIcon(senderIcon)
            senderIcon.layoutParams = lp1

            // 2. Caption toggle
            val captionIcon = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER
                setImageResource(R.drawable.iv_text)
                background = Theme.createSelectorDrawable(
                    Theme.getColor(Theme.key_listSelector, alert.resourcesProvider),
                    1,
                    dp(16f)
                )
                contentDescription = LocaleController.getString(R.string.InuForwardProHideCaption)
                setOnClickListener {
                    state.hideCaption = !state.hideCaption
                    updateCaptionIcon(alert)
                    val text = LocaleController.getString(
                        if (state.hideCaption) R.string.InuForwardProHideCaption else R.string.InuForwardProShowCaption
                    )
                    showBulletin(alert, R.drawable.iv_text, text)
                }
            }
            state.captionIcon = captionIcon
            updateCaptionIcon(alert)
            val lp2 = LinearLayout.LayoutParams(dp(32f), dp(32f)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(2f)
            }
            searchField.addAdditionalIcon(captionIcon)
            captionIcon.layoutParams = lp2
        }

        // 3. Silent send toggle
        val soundIcon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.input_notify_off)
            background = Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_listSelector, alert.resourcesProvider),
                1,
                dp(16f)
            )
            contentDescription = LocaleController.getString(R.string.SendWithoutSound)
            setOnClickListener {
                state.silentSend = !state.silentSend
                updateSoundIcon(alert)
                val text = LocaleController.getString(
                    if (state.silentSend) R.string.SendWithoutSound else R.string.InuForwardProSendWithSound
                )
                showBulletin(alert, R.drawable.input_notify_off, text)
            }
        }
        state.soundIcon = soundIcon
        updateSoundIcon(alert)
        val lp3 = LinearLayout.LayoutParams(dp(32f), dp(32f)).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginEnd = dp(2f)
        }
        searchField.addAdditionalIcon(soundIcon)
        soundIcon.layoutParams = lp3
    }

    @JvmStatic
    fun attachEditButton(alert: ShareAlert, writeButtonContainer: FrameLayout) {
        if (!InuConfig.FORWARD_PRO.value) return
        val context = alert.context ?: return
        val msgs = alert.sendingMessageObjects
        if (msgs == null || msgs.isEmpty()) return

        val state = getState(alert)
        val theme = alert.resourcesProvider

        val editButton = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.msg_edit)
            colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            background = Theme.createSimpleSelectorCircleDrawable(
                dp(38f),
                Theme.getColor(Theme.key_dialogFloatingButton, theme),
                Theme.getColor(Theme.key_dialogFloatingButtonPressed, theme),
            )
            contentDescription = LocaleController.getString(R.string.Edit)
            ViewCompat.setTooltipText(this, LocaleController.getString(R.string.Edit))
            setOnClickListener {
                onEditClick(alert)
            }
        }
        state.editButton = editButton

        // 38dp to match the send button's own circle height (SendButton.setCircleSize(52, 38) —
        // the 38 is what's actually drawn), so both read as a matched pair: with the 4dp left
        // margin this also centers it exactly within the 46dp strip getExtraCommentPadding()
        // reserves (4 + 38 + 4 = 46), instead of the old 40dp/4dp-left/0dp-right split that
        // crowded it 2dp off-center toward the send button.
        writeButtonContainer.addView(
            editButton,
            LayoutHelper.createFrame(38, 38f, Gravity.LEFT or Gravity.CENTER_VERTICAL, 4f, 0f, 4f, 0f)
        )
    }

    private fun onEditClick(alert: ShareAlert) {
        val messages = alert.sendingMessageObjects ?: return
        if (messages.isEmpty()) return
        val msg = messages[0]
        val currentText = msg.messageOwner?.message.orEmpty()
        val context = alert.context ?: return
        val theme = alert.resourcesProvider

        val editText = EditTextBoldCursor(context).apply {
            background = null
            setLineColors(
                Theme.getColor(Theme.key_dialogInputField, theme),
                Theme.getColor(Theme.key_dialogInputFieldActivated, theme),
                Theme.getColor(Theme.key_text_RedBold, theme),
            )
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack, theme))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, theme))
            hint = LocaleController.getString(R.string.Message)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 10
            minLines = 3
            isSingleLine = false
            gravity = Gravity.LEFT or Gravity.TOP
            setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, theme))
            setCursorSize(dp(20f))
            setCursorWidth(1.5f)
            setPadding(0, dp(8f), 0, dp(8f))
            setText(currentText)
            setSelection(text?.length ?: 0)
        }

        val scrollView = NestedScrollView(context).apply {
            val pad = dp(24f)
            setPadding(pad, dp(8f), pad, 0)
            addView(
                editText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = AlertDialog.Builder(context, theme)
            .setTitle(LocaleController.getString(R.string.Edit))
            .setView(scrollView)
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.Done)) { _, _ ->
                val newText = editText.text?.toString().orEmpty()
                msg.messageOwner.message = newText
                msg.messageText = newText
                msg.caption = newText
                msg.messageOwner.entities = null

                // Editing text must send without author header
                alert.showSendersName = false
                updateTopAuthorIcon(alert)

                showBulletin(alert, R.drawable.msg_edit, LocaleController.getString(R.string.InuForwardProEditedNotice))
            }
            .create()

        dialog.setOnShowListener {
            AndroidUtilities.runOnUIThread {
                editText.requestFocus()
                AndroidUtilities.showKeyboard(editText)
            }
        }
        dialog.show()
    }

    private fun showBulletin(alert: ShareAlert, iconRes: Int, text: CharSequence) {
        try {
            val container = alert.bulletinContainer2 ?: alert.container
            val context = alert.context
            if (container != null && context != null) {
                val drawable = ContextCompat.getDrawable(context, iconRes)
                if (drawable != null) {
                    BulletinFactory.of(container, alert.resourcesProvider)
                        .createSimpleBulletin(drawable, text)
                        .show()
                    return
                }
            }
        } catch (_: Throwable) {}
        AndroidUtilities.runOnUIThread {
            Toast.makeText(alert.context, text, Toast.LENGTH_SHORT).show()
        }
    }
}

