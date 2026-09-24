package desu.inugram.helpers.chat

import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import desu.inugram.InuConfig
import desu.inugram.helpers.dialogs.FolderHelper
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.VideoEditedInfo
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.ActionBar.ActionBarPopupWindow
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.AlertsCreator
import org.telegram.ui.Components.ChatActivityEnterView
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.FilterTabsView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ShareAlert
import java.io.File
import java.util.WeakHashMap

object ForwardProHelper {

    private const val FOLDER_TABS_TOP_MARGIN_DP = 51f
    private const val FOLDER_TABS_BOTTOM_GAP_DP = 6

    private class AlertState {
        var active: Boolean = false
        var hideCaption: Boolean = false
        var silentSend: Boolean = false
        var scheduleDate: Int = 0
        var isEditMode: Boolean = false
        var savedComment: CharSequence? = null
        var editedText: CharSequence? = null
        var editButton: ImageView? = null
        var copyNotice: TextView? = null
        var authorIcon: ImageView? = null
        var silentSendIcon: ImageView? = null
        var scheduleIcon: ImageView? = null
        var captionIcon: ImageView? = null
        var toggleActiveColor: Int = 0
        var toggleInactiveColor: Int = 0
        var filterTabsView: FilterTabsView? = null
        var selectedFilterId: Int = 0
    }

    private val states = WeakHashMap<ShareAlert, AlertState>()
    private var pendingOverride: Boolean? = null
    private var pendingInitialEditedText: CharSequence? = null

    @JvmStatic
    fun requestStockShareOnce() {
        pendingOverride = false
        pendingInitialEditedText = null
    }

    @JvmStatic
    fun requestForwardProOnce() {
        pendingOverride = true
        pendingInitialEditedText = null
    }

    @JvmStatic
    fun requestForwardProWithEditedText(text: CharSequence) {
        pendingOverride = true
        pendingInitialEditedText = text
    }

    @JvmStatic
    fun isActive(alert: ShareAlert): Boolean = getState(alert).active

    private fun getState(alert: ShareAlert): AlertState {
        return states.getOrPut(alert) {
            AlertState().also {
                it.active = pendingOverride ?: InuConfig.FORWARD_PRO.value
                it.editedText = pendingInitialEditedText
                pendingOverride = null
                pendingInitialEditedText = null
            }
        }
    }

    @JvmStatic
    fun shouldHideCaption(alert: ShareAlert): Boolean {
        val state = getState(alert)
        return state.active && state.hideCaption
    }

    @JvmStatic
    fun isSilentSend(alert: ShareAlert): Boolean {
        val state = getState(alert)
        return state.active && state.silentSend
    }

    @JvmStatic
    fun getExtraCommentPadding(alert: ShareAlert): Int {
        if (!getState(alert).active) return 0
        val msgs = alert.sendingMessageObjects
        return if (getEditableMessage(msgs) != null) dp(44f) else 0
    }

    @JvmStatic
    fun attachSearchRow(alert: ShareAlert, frameLayout: FrameLayout, searchView: View) {
        val state = getState(alert)
        if (!state.active) {
            frameLayout.addView(searchView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40f, Gravity.BOTTOM or Gravity.LEFT, 11f, 7f, 11f, 11f))
            return
        }
        val context = alert.context ?: return
        val theme = alert.resourcesProvider
        val tintColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, theme)
        val bgColor = Theme.getColor(Theme.key_actionBarWhiteSelector, theme)
        state.toggleActiveColor = Theme.getColor(
            if (Theme.isCurrentThemeDark()) Theme.key_voipgroup_listeningText else Theme.key_dialogTextBlue2, theme,
        )
        state.toggleInactiveColor = tintColor

        fun makeToggle(iconRes: Int, descRes: Int): ImageView {
            return ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER
                setImageResource(iconRes)
                colorFilter = PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
                background = Theme.createSelectorDrawable(bgColor, Theme.RIPPLE_MASK_CIRCLE_20DP, dp(16f))
                contentDescription = LocaleController.getString(descRes)
                setOnLongClickListener {
                    Toast.makeText(context, LocaleController.getString(descRes), Toast.LENGTH_SHORT).show()
                    true
                }
            }
        }

        val authorIcon = makeToggle(R.drawable.msg_openprofile, R.string.ShowSendersName)
        val silentIcon = makeToggle(R.drawable.input_notify_off, R.string.SendWithoutSound)
        val scheduleIcon = makeToggle(R.drawable.msg_calendar2_solar, R.string.ScheduleMessage)
        val captionIcon = makeToggle(R.drawable.outline_caption_24, R.string.InuForwardProHideCaption)
        state.authorIcon = authorIcon
        state.silentSendIcon = silentIcon
        state.scheduleIcon = scheduleIcon
        state.captionIcon = captionIcon

        authorIcon.setOnClickListener {
            alert.showSendersName = !alert.showSendersName
            updateQuickToggleIcons(alert)
        }
        silentIcon.setOnClickListener {
            state.silentSend = !state.silentSend
            updateQuickToggleIcons(alert)
        }
        scheduleIcon.setOnClickListener {
            if (state.scheduleDate != 0) {
                state.scheduleDate = 0
                updateQuickToggleIcons(alert)
                return@setOnClickListener
            }
            val dialogId = if (alert.selectedDialogs.size() > 0) alert.selectedDialogs.keyAt(0) else 0L
            AlertsCreator.createScheduleDatePickerDialog(context, dialogId, AlertsCreator.ScheduleDatePickerDelegate { notify, date, _ ->
                if (date != 0) {
                    state.scheduleDate = date
                    if (!notify) state.silentSend = true
                }
                updateQuickToggleIcons(alert)
            }, theme)
        }
        captionIcon.setOnClickListener {
            state.hideCaption = !state.hideCaption
            updateQuickToggleIcons(alert)
        }

        val toggleContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(captionIcon, LinearLayout.LayoutParams(dp(32f), dp(32f)))
            addView(silentIcon, LinearLayout.LayoutParams(dp(32f), dp(32f)))
            addView(scheduleIcon, LinearLayout.LayoutParams(dp(32f), dp(32f)))
            addView(authorIcon, LinearLayout.LayoutParams(dp(32f), dp(32f)))
        }

        val searchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(searchView, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))
            addView(toggleContainer, LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        }

        frameLayout.addView(searchRow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40f, Gravity.TOP or Gravity.LEFT, 11f, 7f, 11f, 0f))
        updateQuickToggleIcons(alert)
    }

    @JvmStatic
    fun updateQuickToggleIcons(alert: ShareAlert) {
        val state = getState(alert)
        if (!state.active) return
        fun ImageView.paint(on: Boolean) {
            alpha = 1f
            colorFilter = PorterDuffColorFilter(
                if (on) state.toggleActiveColor else state.toggleInactiveColor,
                PorterDuff.Mode.SRC_IN,
            )
        }
        state.authorIcon?.paint(!alert.showSendersName)
        state.silentSendIcon?.paint(state.silentSend)
        state.scheduleIcon?.paint(state.scheduleDate != 0)
        state.captionIcon?.paint(state.hideCaption)
    }

    @JvmStatic
    fun getScheduleDate(alert: ShareAlert): Int {
        val state = getState(alert)
        return if (state.active) state.scheduleDate else 0
    }

    @JvmStatic
    fun attachFolderTabs(alert: ShareAlert, frameLayout: FrameLayout) {
        val state = getState(alert)
        if (!state.active) return
        val context = alert.context ?: return
        val filters = MessagesController.getInstance(alert.currentAccount).dialogFilters
        if (filters.isNullOrEmpty() || filters.size <= 1) return

        val tabsView = FilterTabsView(context, alert.resourcesProvider)
        tabsView.setDelegate(object : FilterTabsView.FilterTabsViewDelegate {
            override fun onPageSelected(tab: FilterTabsView.Tab, forward: Boolean) {
                state.selectedFilterId = tab.id
                alert.inu_refreshDialogsList()
            }
            override fun onPageScrolled(progress: Float) {}
            override fun onSamePageSelected() {}
            override fun getTabCounter(tabId: Int): Int = 0
            override fun didSelectTab(tabView: FilterTabsView.TabView, selected: Boolean): Boolean = true
            override fun isTabMenuVisible(): Boolean = false
            override fun onDeletePressed(id: Int) {}
            override fun onPageReorder(fromId: Int, toId: Int) {}
            override fun canPerformActions(): Boolean = true
        })
        val skipDefaultTab = FolderHelper.shouldSkipDefaultTab(filters.size)
        for (filter in filters) {
            if (filter.isDefault) {
                if (skipDefaultTab) continue
                tabsView.inu_addTab(filter.id, filter.id, LocaleController.getString(R.string.FilterAllChats), null, true, true, false, "\uD83D\uDCAC")
            } else {
                val info = FolderHelper.getTabInfo(filter)
                tabsView.inu_addTab(filter.id, filter.id, info.first, filter.entities, true, false, false, info.second)
            }
        }
        tabsView.finishAddingTabs(false)

        state.filterTabsView = tabsView
        state.selectedFilterId = (if (skipDefaultTab) filters.firstOrNull { !it.isDefault } else filters.firstOrNull { it.isDefault })?.id ?: filters[0].id
        frameLayout.addView(
            tabsView,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, FolderHelper.TAB_BAR_HEIGHT_DP.toFloat(), Gravity.TOP or Gravity.LEFT, 0f, FOLDER_TABS_TOP_MARGIN_DP, 0f, 0f)
        )
    }

    @JvmStatic
    fun isDialogAllowedByFolder(alert: ShareAlert, dialogId: Long): Boolean {
        val state = getState(alert)
        if (!state.active) return true
        val filter = MessagesController.getInstance(alert.currentAccount).dialogFilters
            ?.firstOrNull { it.id == state.selectedFilterId } ?: return true
        if (filter.isDefault) return true
        return filter.includesDialog(AccountInstance.getInstance(alert.currentAccount), dialogId)
    }

    @JvmStatic
    fun getFolderTabsHeightDp(alert: ShareAlert): Int {
        if (!getState(alert).active) return 0
        val filters = MessagesController.getInstance(alert.currentAccount).dialogFilters
        if (filters.isNullOrEmpty() || filters.size <= 1) return 0
        return (FOLDER_TABS_TOP_MARGIN_DP + FolderHelper.TAB_BAR_HEIGHT_DP + FOLDER_TABS_BOTTOM_GAP_DP - 58).toInt()
    }

    @JvmStatic
    fun attachHideCaptionRow(alert: ShareAlert, sendPopupLayout: ActionBarPopupWindow.ActionBarPopupWindowLayout, darkTheme: Boolean) {
        val state = getState(alert)
        if (!state.active) return
        val context = alert.context ?: return
        val hideCaptionView = ActionBarMenuSubItem(context, true, false, true, alert.resourcesProvider)
        if (darkTheme) {
            hideCaptionView.setTextColor(Theme.getColor(Theme.key_voipgroup_nameText, alert.resourcesProvider))
        }
        sendPopupLayout.addView(hideCaptionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))
        hideCaptionView.setTextAndIcon(LocaleController.getString(R.string.InuForwardProHideCaption), 0)
        hideCaptionView.setChecked(state.hideCaption)
        hideCaptionView.setOnClickListener {
            state.hideCaption = !state.hideCaption
            hideCaptionView.setChecked(state.hideCaption)
            updateQuickToggleIcons(alert)
        }
    }

    @JvmStatic
    fun attachCommentRow(alert: ShareAlert) {
        val state = getState(alert)
        if (!state.active) return
        val context = alert.context ?: return
        val theme = alert.resourcesProvider
        val frame2 = alert.frameLayout2 ?: return
        val msgs = alert.sendingMessageObjects
        val editable = getEditableMessage(msgs) ?: return

        val editButton = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.msg_edit)
            setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, theme), PorterDuff.Mode.SRC_IN)
            background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, theme), Theme.RIPPLE_MASK_CIRCLE_TO_BOUND_EDGE)
            contentDescription = LocaleController.getString(R.string.Edit)
            ViewCompat.setTooltipText(this, LocaleController.getString(R.string.Edit))
            setOnClickListener {
                toggleEditMode(alert)
            }
        }
        state.editButton = editButton

        val copyNotice = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            setTextColor(Theme.getColor(Theme.key_undo_infoColor, theme))
            background = Theme.createRoundRectDrawable(dp(10f), Theme.getColor(Theme.key_undo_background, theme))
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            text = LocaleController.getString(R.string.InuForwardProEditedNotice)
            visibility = View.GONE
        }
        state.copyNotice = copyNotice

        frame2.addView(copyNotice, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, -2f, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0f, -34f, 0f, 0f))
        frame2.addView(editButton, LayoutHelper.createFrame(40, 40f, Gravity.RIGHT or Gravity.BOTTOM, 0f, 0f, 68f, 5f))

        if (state.editedText != null) {
            enterEditMode(alert, state.editedText, false)
        }
    }

    @JvmStatic
    fun onShowCommentTextView(alert: ShareAlert, show: Boolean) {
        val state = getState(alert)
        if (!state.active) return
        if (!show && state.isEditMode) {
            exitEditMode(alert)
        }
    }

    private fun toggleEditMode(alert: ShareAlert) {
        val state = getState(alert)
        if (state.isEditMode) {
            exitEditMode(alert)
        } else {
            enterEditMode(alert, null)
        }
    }

    private fun enterEditMode(alert: ShareAlert, prefillText: CharSequence?, openKeyboard: Boolean = true) {
        val state = getState(alert)
        val msgs = alert.sendingMessageObjects ?: return
        val editable = getEditableMessage(msgs) ?: return
        val commentView = alert.commentTextView ?: return

        state.isEditMode = true
        state.savedComment = commentView.text
        commentView.setHint(LocaleController.getString(R.string.InuForwardProPlaceholder))
        val textToLoad = prefillText ?: editableText(editable, commentView.getEditText().paint.fontMetricsInt)
        commentView.setText(textToLoad)
        commentView.getEditText().setSelection(commentView.text?.length ?: 0)
        state.editButton?.setImageResource(R.drawable.msg_close)
        state.editButton?.contentDescription = LocaleController.getString(R.string.Cancel)
        state.copyNotice?.visibility = View.VISIBLE
        // entiny: the sheet opens right after the editor dialog, where the keyboard was already up
        if (openKeyboard) commentView.openKeyboard() else commentView.closeKeyboard()
    }

    private fun exitEditMode(alert: ShareAlert) {
        val state = getState(alert)
        if (!state.isEditMode) return
        val commentView = alert.commentTextView ?: return

        state.isEditMode = false
        state.editedText = null
        commentView.setHint(LocaleController.getString(R.string.ShareComment))
        commentView.setText(state.savedComment ?: "")
        state.editButton?.setImageResource(R.drawable.msg_edit)
        state.editButton?.contentDescription = LocaleController.getString(R.string.Edit)
        state.copyNotice?.visibility = View.GONE
    }

    @JvmStatic
    fun handleSend(alert: ShareAlert, withSound: Boolean): Boolean {
        val state = getState(alert)
        if (!state.active) return false
        val msgs = alert.sendingMessageObjects ?: return false
        val editable = getEditableMessage(msgs) ?: return false

        val commentView = alert.commentTextView
        val currentFieldText: CharSequence = commentView?.text?.let { SpannableStringBuilder(it) } ?: ""

        val isTextEdited = state.isEditMode && hasTextChanged(currentFieldText, editable)
        val isPreEdited = state.editedText != null && hasTextChanged(state.editedText ?: "", editable)

        if (!isTextEdited && !isPreEdited) {
            if (state.isEditMode) exitEditMode(alert)
            return false
        }

        val textToSend: CharSequence = if (isTextEdited) currentFieldText else state.editedText ?: ""
        if (isTextOnly(editable) && textToSend.trim().isEmpty()) {
            Toast.makeText(alert.context, LocaleController.getString(R.string.InuForwardProEmptyNotice), Toast.LENGTH_SHORT).show()
            return true
        }

        return sendEditedAsCopy(alert, editable, textToSend, withSound)
    }

    private fun sendEditedAsCopy(alert: ShareAlert, editable: MessageObject, editedText: CharSequence, withSound: Boolean): Boolean {
        val messages = alert.sendingMessageObjects ?: return false
        val state = getState(alert)
        val account = alert.currentAccount

        // entiny: keep the spans so text links and formatting survive; getEntities also strips markdown from the text
        val textHolder = arrayOf<CharSequence>(editedText)
        val entities = MediaDataController.getInstance(account).getEntities(textHolder, true) ?: ArrayList()
        val plainText = textHolder[0].toString()
        val comment = state.savedComment
        val hasComment = !comment.isNullOrEmpty()
        val commentEntities = if (hasComment) MediaDataController.getInstance(account).getEntities(arrayOf(comment), true) ?: ArrayList() else ArrayList()

        var hasSentAny = false
        val selectedDialogs = alert.selectedDialogs
        val selectedTopics = alert.selectedDialogTopics

        for (a in 0 until selectedDialogs.size()) {
            val did = selectedDialogs.keyAt(a)
            val isMonoForum = MessagesController.getInstance(account).isMonoForum(did)
            val topic = selectedTopics[selectedDialogs.get(did)]
            val monoForumPeerId = if (topic != null && isMonoForum) DialogObject.getPeerDialogId(topic.from_id) else 0L
            val replyTopMsg = if (topic != null && !isMonoForum) MessageObject(account, topic.topicStartMessage, false, false).apply { isTopicMainMessage = true } else null

            if (hasComment) {
                val params = SendMessagesHelper.SendMessageParams.of(
                    comment.toString(), did, null, replyTopMsg, null, true,
                    commentEntities, null, null, withSound, 0, 0, null, false
                )
                params.monoForumPeer = monoForumPeerId
                params.scheduleDate = state.scheduleDate
                SendMessagesHelper.getInstance(account).sendMessage(params)
            }

            val sent = withEditedText(editable, plainText, entities) {
                sendSingleOrBatchAsCopy(messages, did, replyTopMsg, withSound, account, monoForumPeerId, state.scheduleDate)
            }
            if (sent) {
                hasSentAny = true
            }
        }

        alert.dismiss()
        return hasSentAny
    }

    private inline fun <T> withEditedText(
        editable: MessageObject,
        newText: String,
        entities: ArrayList<TLRPC.MessageEntity>,
        action: () -> T
    ): T {
        val owner = editable.messageOwner ?: return action()
        val origMessage = owner.message
        val origEntities = owner.entities
        val origCaption = editable.caption
        val origText = editable.messageText

        try {
            owner.message = newText
            owner.entities = entities
            editable.caption = if (newText.isNotEmpty()) newText else null
            editable.messageText = newText
            return action()
        } finally {
            owner.message = origMessage
            owner.entities = origEntities
            editable.caption = origCaption
            editable.messageText = origText
        }
    }

    private fun sendSingleOrBatchAsCopy(
        messages: ArrayList<MessageObject>,
        targetDialogId: Long,
        replyTopMsg: MessageObject?,
        withSound: Boolean,
        account: Int,
        monoForumPeerId: Long,
        scheduleDate: Int
    ): Boolean {
        if (messages.isEmpty()) return false
        val accountInstance = AccountInstance.getInstance(account)

        if (isAlbumGroup(messages)) {
            val mediaList = ArrayList<SendMessagesHelper.SendingMediaInfo>()
            val pending = ArrayList<Int>()
            for (i in messages.indices) {
                val msg = messages[i]
                val info = SendMessagesHelper.SendingMediaInfo().apply {
                    this.path = getPathToMessage(msg, account)
                    this.caption = msg.caption?.toString()
                    this.entities = msg.messageOwner?.entities ?: ArrayList()
                    this.isVideo = msg.isVideo
                }
                mediaList.add(info)
                val path = info.path
                if (path.isNullOrEmpty() || !File(path).exists()) {
                    info.path = null
                    pending.add(i)
                }
            }
            if (pending.isEmpty()) {
                prepareAlbumCopy(mediaList, messages, accountInstance, targetDialogId, replyTopMsg, withSound, monoForumPeerId, scheduleDate)
                return true
            }
            waitForDownloads(
                account, messages, mediaList, pending,
                onReady = {
                    prepareAlbumCopy(mediaList, messages, accountInstance, targetDialogId, replyTopMsg, withSound, monoForumPeerId, scheduleDate)
                },
                onFailure = {
                    for (msg in messages) {
                        sendSingleMessageAsCopy(msg, targetDialogId, replyTopMsg, withSound, account, monoForumPeerId, scheduleDate)
                    }
                },
            )
            return true
        }

        var sentAny = false
        for (msg in messages) {
            if (sendSingleMessageAsCopy(msg, targetDialogId, replyTopMsg, withSound, account, monoForumPeerId, scheduleDate)) {
                sentAny = true
            }
        }
        return sentAny
    }

    // entiny: photos, videos and files can share one album; round/voice/sticker items must go solo
    private fun isAlbumGroup(messages: ArrayList<MessageObject>): Boolean {
        if (messages.size < 2) return false
        return messages.all {
            it.isPhoto || (it.isVideo && !it.isRoundVideo) ||
                (it.document != null && !it.isSticker && !it.isAnimatedSticker && !it.isVoice())
        }
    }

    private fun prepareAlbumCopy(
        mediaList: ArrayList<SendMessagesHelper.SendingMediaInfo>,
        messages: ArrayList<MessageObject>,
        accountInstance: AccountInstance,
        targetDialogId: Long,
        replyTopMsg: MessageObject?,
        withSound: Boolean,
        monoForumPeerId: Long,
        scheduleDate: Int
    ) {
        SendMessagesHelper.prepareSendingMedia(
            accountInstance, mediaList, targetDialogId, null,
            replyTopMsg, null, null, false, true, null, withSound, scheduleDate, 0, 0,
            false, null, null, 0, messages[0].messageOwner?.invert_media ?: false, 0, monoForumPeerId, null
        )
    }

    private fun waitForDownloads(
        account: Int,
        messages: ArrayList<MessageObject>,
        mediaList: ArrayList<SendMessagesHelper.SendingMediaInfo>,
        pending: ArrayList<Int>,
        onReady: () -> Unit,
        onFailure: () -> Unit,
    ) {
        val loader = FileLoader.getInstance(account)
        val center = NotificationCenter.getInstance(account)
        var settled = false
        var observer: NotificationCenter.NotificationCenterDelegate? = null
        var timeoutRunnable: Runnable? = null

        fun finish(allResolved: Boolean) {
            if (settled) return
            settled = true
            observer?.let {
                center.removeObserver(it, NotificationCenter.fileLoaded)
                center.removeObserver(it, NotificationCenter.fileLoadFailed)
            }
            timeoutRunnable?.let { AndroidUtilities.cancelRunOnUIThread(it) }
            if (allResolved) onReady() else onFailure()
        }
        fun resolveAll(): Boolean {
            var all = true
            for (i in pending) {
                if (mediaList[i].path.isNullOrEmpty()) {
                    mediaList[i].path = getPathToMessage(messages[i], account)
                }
                if (mediaList[i].path.isNullOrEmpty()) all = false
            }
            return all
        }
        val delegate = object : NotificationCenter.NotificationCenterDelegate {
            override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
                if (id == NotificationCenter.fileLoaded) finish(resolveAll())
            }
        }
        observer = delegate
        center.addObserver(delegate, NotificationCenter.fileLoaded)
        center.addObserver(delegate, NotificationCenter.fileLoadFailed)
        if (resolveAll()) {
            finish(true)
            return
        }
        for (i in pending) triggerDownload(messages[i], loader)
        val timeout = Runnable { finish(resolveAll()) }
        timeoutRunnable = timeout
        AndroidUtilities.runOnUIThread(timeout, 60_000)
    }

    private fun triggerDownload(msg: MessageObject, loader: FileLoader) {
        val owner = msg.messageOwner ?: return
        val document = owner.media?.document ?: msg.document
        if (document != null) {
            loader.loadFile(document, msg, FileLoader.PRIORITY_HIGH, 1)
            return
        }
        val photo = owner.media?.photo
        if (photo != null && !photo.sizes.isEmpty()) {
            val size = photo.sizes[photo.sizes.size - 1]
            loader.loadFile(ImageLocation.getForPhoto(size, photo), photo, "jpg", FileLoader.PRIORITY_HIGH, 1)
        }
    }

    private fun sendSingleMessageAsCopy(
        msg: MessageObject,
        targetDialogId: Long,
        replyTopMsg: MessageObject?,
        withSound: Boolean,
        account: Int,
        monoForumPeerId: Long,
        scheduleDate: Int
    ): Boolean {
        val owner = msg.messageOwner ?: return false
        val path = getPathToMessage(msg, account)
        val caption = msg.caption?.toString()
        val entities = owner.entities ?: ArrayList()

        if (msg.type == MessageObject.TYPE_TEXT || msg.isAnimatedEmoji) {
            val text = owner.message.orEmpty()
            if (text.isEmpty()) return false
            val params = SendMessagesHelper.SendMessageParams.of(
                text, targetDialogId, null, replyTopMsg, null, false,
                entities, null, null, withSound, 0, 0, null, false
            )
            params.monoForumPeer = monoForumPeerId
            params.scheduleDate = scheduleDate
            SendMessagesHelper.getInstance(account).sendMessage(params)
            return true
        }

        val photo = owner.media?.photo as? TLRPC.TL_photo
        if (photo != null) {
            val params = SendMessagesHelper.SendMessageParams.of(
                photo, path, targetDialogId, null, replyTopMsg, caption, entities,
                null, null, withSound, 0, 0, owner.ttl, null, false, msg.hasMediaSpoilers()
            )
            params.monoForumPeer = monoForumPeerId
            params.scheduleDate = scheduleDate
            params.invert_media = owner.invert_media
            SendMessagesHelper.getInstance(account).sendMessage(params)
            return true
        }

        val document = (owner.media?.document ?: msg.document) as? TLRPC.TL_document
        if (document != null) {
            val videoEditedInfo = if (msg.isRoundVideo) {
                msg.videoEditedInfo ?: VideoEditedInfo().apply { roundVideo = true }
            } else {
                msg.videoEditedInfo
            }
            val params = SendMessagesHelper.SendMessageParams.of(
                document, videoEditedInfo, path, targetDialogId, null, replyTopMsg, caption, entities,
                null, null, withSound, 0, 0, owner.ttl, null, null, false, msg.hasMediaSpoilers()
            )
            params.monoForumPeer = monoForumPeerId
            params.scheduleDate = scheduleDate
            params.invert_media = owner.invert_media
            SendMessagesHelper.getInstance(account).sendMessage(params)
            return true
        }

        return false
    }

    @JvmStatic
    fun openEditorDialog(activity: ChatActivity, message: MessageObject, group: MessageObject.GroupedMessages?) {
        val messages = group?.messages ?: listOf(message)
        val editable = getEditableMessage(messages)
        val context = activity.parentActivity ?: return
        val theme = activity.resourceProvider

        val originalFontMetrics = Paint().apply { textSize = dp(16f).toFloat() }.fontMetricsInt
        val originalText: CharSequence = if (editable != null) editableText(editable, originalFontMetrics) else ""
        val isMedia = editable != null && !isTextOnly(editable)

        val editText = EditTextBoldCursor(context).apply {
            background = null
            setLineColors(
                Theme.getColor(Theme.key_dialogInputField, theme),
                Theme.getColor(Theme.key_dialogInputFieldActivated, theme),
                Theme.getColor(Theme.key_text_RedBold, theme),
            )
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            val textColor = Theme.getColor(Theme.key_dialogTextBlack, theme)
            setTextColor(textColor)
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, theme))
            try {
                backgroundTintList = ColorStateList.valueOf(textColor)
            } catch (_: Throwable) {}
            hint = LocaleController.getString(if (isMedia) R.string.InuForwardProPlaceholder else R.string.Message)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 10
            minLines = 3
            isSingleLine = false
            gravity = Gravity.LEFT or Gravity.TOP
            setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, theme))
            setCursorSize(dp(20f))
            setCursorWidth(1.5f)
            setPadding(0, dp(8f), 0, dp(8f))
            setText(originalText)
            setSelection(text?.length ?: 0)
        }

        val scrollView = NestedScrollView(context).apply {
            val pad = dp(24f)
            setPadding(pad, dp(8f), pad, 0)
            addView(editText, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }

        val dialog = AlertDialog.Builder(context, theme)
            .setTitle(LocaleController.getString(R.string.InuForwardPro))
            .setView(scrollView)
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.Forward)) { _, _ ->
                val newText: CharSequence = editText.text?.let { SpannableStringBuilder(it) } ?: ""
                if (!isMedia && newText.trim().isEmpty()) {
                    Toast.makeText(context, LocaleController.getString(R.string.InuForwardProEmptyNotice), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                requestForwardProWithEditedText(newText)
                val alert = ShareAlert(
                    context, activity, ArrayList(messages), null, null,
                    ChatObject.isChannel(activity.currentChat), null, null,
                    false, false, false, null, activity.themeDelegate
                )
                activity.showDialog(alert)
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

    @JvmStatic
    fun onDismiss(alert: ShareAlert) {
        states.remove(alert)
    }

    fun getEditableMessage(messages: List<MessageObject>?): MessageObject? {
        if (messages.isNullOrEmpty()) return null
        val groupId = messages[0].groupIdForUse
        val isAlbum = messages.size > 1
        var captionCarrier: MessageObject? = null
        for (msg in messages) {
            if (msg.messageOwner == null) return null
            if (isAlbum && msg.groupIdForUse != groupId) return null
            if (!isTextCarrier(msg)) return null
            if (captionCarrier == null && msg.caption != null) {
                captionCarrier = msg
            }
        }
        if (!isAlbum) return messages[0]
        if (groupId == 0L) return null
        return captionCarrier ?: messages[0]
    }

    private fun isTextCarrier(msg: MessageObject): Boolean {
        if (msg.isPoll || msg.isTodo || msg.isLocation || msg.isLiveLocation ||
            msg.isGame || msg.isInvoice || msg.isStoryMedia || msg.isVoiceOnce ||
            msg.isRoundOnce || msg.isSticker || msg.isAnimatedSticker
        ) {
            return false
        }
        return msg.type == MessageObject.TYPE_TEXT || msg.isAnimatedEmoji ||
                msg.isPhoto || msg.isVideo || msg.isRoundVideo ||
                msg.document != null || msg.caption != null
    }

    fun isTextOnly(msg: MessageObject): Boolean {
        return msg.type == MessageObject.TYPE_TEXT || msg.isAnimatedEmoji
    }

    fun getForwardText(msg: MessageObject): CharSequence {
        var text = ChatActivity.getMessageCaption(msg, null, null)
        if (text == null && isTextOnly(msg)) {
            text = ChatActivity.getMessageContent(msg, 0, false)
        }
        return text ?: ""
    }

    private fun hasTextChanged(newText: CharSequence, editable: MessageObject): Boolean {
        return getForwardText(editable).toString() != newText.toString()
    }

    // entiny: raw text plus entities as editor spans, the same way the stock message editor loads it
    private fun editableText(msg: MessageObject, fontMetrics: Paint.FontMetricsInt?): CharSequence {
        val owner = msg.messageOwner ?: return getForwardText(msg)
        val raw = owner.message
        if (raw.isNullOrEmpty()) return getForwardText(msg)
        return ChatActivityEnterView.applyMessageEntities(ArrayList(owner.entities ?: emptyList()), raw, fontMetrics) ?: raw
    }

    private fun getPathToMessage(msg: MessageObject, account: Int): String? {
        val owner = msg.messageOwner ?: return null
        if (!owner.attachPath.isNullOrEmpty() && File(owner.attachPath).exists()) {
            return owner.attachPath
        }
        val loader = FileLoader.getInstance(account)
        val doc = msg.document
        if (doc != null) {
            val f1 = loader.getPathToAttach(doc, false)
            if (f1 != null && f1.exists() && f1.length() > 0) return f1.absolutePath
            val f2 = loader.getPathToAttach(doc, true)
            if (f2 != null && f2.exists() && f2.length() > 0) return f2.absolutePath
        }
        val photo = owner.media?.photo
        if (photo != null && !photo.sizes.isNullOrEmpty()) {
            for (i in photo.sizes.indices.reversed()) {
                val f = loader.getPathToAttach(photo.sizes[i], false)
                if (f != null && f.exists() && f.length() > 0) return f.absolutePath
                val fCache = loader.getPathToAttach(photo.sizes[i], true)
                if (fCache != null && fCache.exists() && fCache.length() > 0) return fCache.absolutePath
            }
        }
        val fPath = loader.getPathToMessage(owner)
        if (fPath != null && fPath.exists() && fPath.length() > 0 && !fPath.absolutePath.endsWith("/cache")) {
            return fPath.absolutePath
        }
        return null
    }
}
