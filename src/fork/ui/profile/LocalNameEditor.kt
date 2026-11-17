package desu.inugram.ui.profile

import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import desu.inugram.helpers.profile.LocalNameHelper
import desu.inugram.helpers.profile.LocalNameHelper.F_ABOUT
import desu.inugram.helpers.profile.LocalNameHelper.F_EMOJI
import desu.inugram.helpers.profile.LocalNameHelper.F_FIRST
import desu.inugram.helpers.profile.LocalNameHelper.F_LAST
import desu.inugram.helpers.profile.LocalNameHelper.F_PREMIUM
import desu.inugram.helpers.profile.LocalNameHelper.F_TITLE
import desu.inugram.helpers.profile.LocalNameHelper.F_USERNAME
import desu.inugram.helpers.profile.LocalNameHelper.F_VERIFIED
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_stars
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.CheckBoxCell
import org.telegram.ui.Components.AnimatedEmojiDrawable
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.ImageUpdater
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.SelectAnimatedEmojiDialog

object LocalNameEditor {
    private var pendingUpdater: ImageUpdater? = null

    private fun pickEmoji(fragment: BaseFragment, account: Int, dialogId: Long, onPicked: () -> Unit) {
        val context = fragment.parentActivity ?: return
        val anchor = fragment.fragmentView ?: return
        var popup: SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow? = null
        val layout = object : SelectAnimatedEmojiDialog(fragment, context, true, anchor.width / 2, SelectAnimatedEmojiDialog.TYPE_EMOJI_STATUS, null) {
            override fun onEmojiSelected(emojiView: View?, documentId: Long?, document: TLRPC.Document?, gift: TL_stars.TL_starGiftUnique?, until: Int?) {
                LocalNameHelper.saveMerged(account, dialogId, mapOf(F_EMOJI to (documentId ?: 0L).toString()))
                popup?.dismiss()
                onPicked()
            }
        }
        layout.setSaveState(2)
        popup = SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow(layout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT)
        popup.showAsDropDown(anchor, 0, -(anchor.height - AndroidUtilities.dp(96f)), Gravity.TOP or Gravity.LEFT)
        popup.dimBehind()
    }

    private fun pickAvatar(fragment: BaseFragment, account: Int, dialogId: Long, onPicked: () -> Unit) {
        val updater = ImageUpdater(false, ImageUpdater.FOR_TYPE_USER, false)
        pendingUpdater = updater
        updater.parentFragment = fragment
        updater.setUploadAfterSelect(false)
        updater.setDelegate { _, _, _, _, bigSize, _, _, _ ->
            val file = bigSize?.let { FileLoader.getInstance(account).getPathToAttach(it, true) }
            if (file != null && file.exists()) LocalNameHelper.setAvatar(account, dialogId, file)
            AndroidUtilities.runOnUIThread { onPicked() }
        }
        updater.openGallery()
    }

    fun show(fragment: BaseFragment, account: Int, dialogId: Long, onChanged: Runnable? = null) {
        val context = fragment.parentActivity ?: return
        if (dialogId == 0L) return
        val theme = fragment.resourceProvider
        val entry = LocalNameHelper.entryOf(account, dialogId)
        val fields = LocalNameHelper.fieldsFor(dialogId)
        val obj = LocalNameHelper.cached(account, dialogId)
        val inputs = LinkedHashMap<String, EditTextBoldCursor>()

        fun current(f: String): String {
            entry?.over?.get(f)?.let { return it }
            entry?.orig?.get(f)?.let { return it }
            return when {
                f == F_ABOUT -> when (val full = LocalNameHelper.cachedFull(account, dialogId)) {
                    is TLRPC.UserFull -> full.about
                    is TLRPC.ChatFull -> full.about
                    else -> null
                }
                obj is TLRPC.User -> LocalNameHelper.getField(obj, f)
                obj is TLRPC.Chat -> LocalNameHelper.getField(obj, f)
                else -> null
            }.orEmpty()
        }

        fun hintFor(f: String) = LocaleController.getString(
            when (f) {
                F_FIRST -> R.string.InuLocalNameFirst
                F_LAST -> R.string.InuLocalNameLast
                F_TITLE -> R.string.InuLocalNameTitle
                F_ABOUT -> R.string.InuLocalNameAbout
                else -> R.string.InuLocalNameUsername
            }
        )

        var verifiedBox: CheckBoxCell? = null
        var premiumBox: CheckBoxCell? = null
        var emojiValue = current(F_EMOJI).ifEmpty { "0" }
        fun collect(): Map<String, String> {
            val result = HashMap<String, String>(inputs.mapValues { it.value.text?.toString().orEmpty() })
            result[F_VERIFIED] = if (verifiedBox?.isChecked == true) "1" else "0"
            if (dialogId > 0) result[F_PREMIUM] = if (premiumBox?.isChecked == true) "1" else "0"
            result[F_EMOJI] = emojiValue
            return result
        }

        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val avatarView = BackupImageView(context).apply { setRoundRadius(AndroidUtilities.dp(28f)) }
        val avatarDrawable = AvatarDrawable()
        when (obj) {
            is TLRPC.User -> avatarDrawable.setInfo(account, obj)
            is TLRPC.Chat -> avatarDrawable.setInfo(account, obj)
        }
        if (obj != null) avatarView.setForUserOrChat(obj, avatarDrawable)
        header.addView(avatarView, LayoutHelper.createLinear(56, 56, Gravity.CENTER_VERTICAL, 24, 4, 12, 4))

        val actions = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun actionText(textRes: Int, onClick: () -> Unit) = TextView(context).apply {
            text = LocaleController.getString(textRes)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlue, theme))
            setPadding(0, AndroidUtilities.dp(6f), 0, AndroidUtilities.dp(6f))
            setOnClickListener { onClick() }
        }
        header.addView(actions, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        container.addView(header)

        val original = LocalNameHelper.originalName(account, dialogId)
        if (original.isNotEmpty()) {
            container.addView(
                TextView(context).apply {
                    text = LocaleController.formatString(R.string.InuLocalNameWas, original)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                    setTextColor(Theme.getColor(Theme.key_dialogTextGray2, theme))
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT, 24, 4, 24, 4),
            )
        }
        for (f in fields) {
            if (f == F_VERIFIED || f == F_PREMIUM || f == F_EMOJI) continue
            val multiline = f == F_ABOUT
            val edit = EditTextBoldCursor(context).apply {
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
                    minLines = 2
                    maxLines = 5
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                } else {
                    setSingleLine(true)
                    inputType = InputType.TYPE_CLASS_TEXT or if (f == F_USERNAME) InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    imeOptions = EditorInfo.IME_ACTION_NEXT
                }
                gravity = Gravity.LEFT or Gravity.TOP
                setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, theme))
                setCursorSize(AndroidUtilities.dp(20f))
                setCursorWidth(1.5f)
                setPadding(0, AndroidUtilities.dp(4f), 0, AndroidUtilities.dp(4f))
                hint = hintFor(f)
                setText(current(f))
            }
            container.addView(
                edit,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, if (multiline) LayoutHelper.WRAP_CONTENT else 36, Gravity.TOP or Gravity.LEFT, 24, 6, 24, 0),
            )
            inputs[f] = edit
        }

        verifiedBox = CheckBoxCell(context, 1, theme).apply {
            setText(LocaleController.getString(R.string.InuLocalNameVerified), "", current(F_VERIFIED) == "1", false)
            setOnClickListener { setChecked(!isChecked, true) }
        }
        container.addView(verifiedBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, Gravity.TOP or Gravity.LEFT, 8, 4, 8, 0))

        if (dialogId > 0) {
            premiumBox = CheckBoxCell(context, 1, theme).apply {
                setText(LocaleController.getString(R.string.InuLocalNamePremium), "", current(F_PREMIUM) == "1", false)
                setOnClickListener { setChecked(!isChecked, true) }
            }
            container.addView(premiumBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, Gravity.TOP or Gravity.LEFT, 8, 0, 8, 0))
        }

        val emojiRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val emojiView = BackupImageView(context)
        emojiValue.toLongOrNull()?.takeIf { it > 0L }?.let { emojiView.setAnimatedEmojiDrawable(AnimatedEmojiDrawable(AnimatedEmojiDrawable.CACHE_TYPE_KEYBOARD, account, it)) }
        emojiRow.addView(emojiView, LayoutHelper.createLinear(32, 32, Gravity.CENTER_VERTICAL, 24, 8, 12, 8))
        val emojiActions = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        emojiRow.addView(emojiActions, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        container.addView(
            TextView(context).apply {
                text = LocaleController.getString(R.string.InuLocalNameEmoji)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                setTextColor(Theme.getColor(Theme.key_dialogTextGray2, theme))
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT, 24, 8, 24, 0),
        )
        container.addView(emojiRow)

        val scroll = ScrollView(context).apply { addView(container) }
        val builder = AlertDialog.Builder(context, theme)
            .setTitle(LocaleController.getString(R.string.InuLocalName))
            .setView(scroll)
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.Save)) { _, _ ->
                LocalNameHelper.save(account, dialogId, collect())
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.InuLocalNameSaved)).show()
                onChanged?.run()
            }
        if (entry != null) {
            builder.setNeutralButton(LocaleController.getString(R.string.Reset)) { _, _ ->
                LocalNameHelper.remove(account, dialogId)
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.InuLocalNameRemoved)).show()
                onChanged?.run()
            }
        }
        val dialog = builder.create()

        fun reopen() {
            onChanged?.run()
            show(fragment, account, dialogId, onChanged)
        }
        actions.addView(actionText(R.string.InuLocalNameChangePhoto) {
            LocalNameHelper.save(account, dialogId, collect())
            dialog.dismiss()
            pickAvatar(fragment, account, dialogId) { reopen() }
        })
        emojiActions.addView(actionText(R.string.InuLocalNameEmojiChoose) {
            LocalNameHelper.save(account, dialogId, collect())
            dialog.dismiss()
            pickEmoji(fragment, account, dialogId) { reopen() }
        })
        if (emojiValue != "0") {
            emojiActions.addView(actionText(R.string.InuLocalNameEmojiHide) {
                LocalNameHelper.saveMerged(account, dialogId, collect() + (F_EMOJI to "0"))
                dialog.dismiss()
                reopen()
            })
        }
        if (entry?.avatar != null) {
            actions.addView(actionText(R.string.InuLocalNameRemovePhoto) {
                LocalNameHelper.save(account, dialogId, collect())
                LocalNameHelper.removeAvatar(account, dialogId)
                dialog.dismiss()
                reopen()
            })
        }
        dialog.setOnShowListener {
            AndroidUtilities.runOnUIThread {
                val first = inputs.values.first()
                first.requestFocus()
                first.setSelection(first.text?.length ?: 0)
                AndroidUtilities.showKeyboard(first)
            }
        }
        fragment.showDialog(dialog)
    }
}
