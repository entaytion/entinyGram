package desu.inugram.helpers.chat

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import desu.inugram.InuConfig
import desu.inugram.helpers.dialogs.FolderHelper
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.ActionBar.ActionBarPopupWindow
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.FilterTabsView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ShareAlert
import java.util.WeakHashMap

object ForwardProHelper {

    private const val FOLDER_TABS_TOP_MARGIN_DP = 51f
    private const val FOLDER_TABS_BOTTOM_GAP_DP = 6

    private class AlertState {
        var hideCaption: Boolean = false
        var editButton: ImageView? = null
        var silentSend: Boolean = false
        var silentSendIcon: ImageView? = null
        var authorIcon: ImageView? = null
        var captionIcon: ImageView? = null
        var filterTabsView: FilterTabsView? = null
        var selectedFilterId: Int = 0
        var active: Boolean = false
    }

    private val states = WeakHashMap<ShareAlert, AlertState>()

    // entiny: one-shot override for the next ShareAlert — true/false forces Forward Pro on/off for that share.
    private var pendingOverride: Boolean? = null

    @JvmStatic
    fun requestStockShareOnce() {
        pendingOverride = false
    }

    @JvmStatic
    fun requestForwardProOnce() {
        pendingOverride = true
    }

    // entiny: Java gates should use this, not raw InuConfig, so an override isn't skipped.
    @JvmStatic
    fun isActive(alert: ShareAlert): Boolean = getState(alert).active

    private fun getState(alert: ShareAlert): AlertState {
        return states.getOrPut(alert) {
            AlertState().also {
                it.active = pendingOverride ?: InuConfig.FORWARD_PRO.value
                pendingOverride = null
            }
        }
    }

    @JvmStatic
    fun shouldHideCaption(alert: ShareAlert): Boolean {
        val state = getState(alert)
        if (!state.active) return false
        return state.hideCaption
    }

    @JvmStatic
    fun getExtraCommentPadding(alert: ShareAlert): Int {
        if (!getState(alert).active) return 0
        val msgs = alert.sendingMessageObjects
        return if (msgs != null && msgs.isNotEmpty()) dp(46f) else 0
    }

    @JvmStatic
    fun isSilentSend(alert: ShareAlert): Boolean {
        val state = getState(alert)
        if (!state.active) return false
        return state.silentSend
    }

    // entiny: quick-toggle icons beside the search bar, siblings of searchView not children of it.
    @JvmStatic
    fun attachQuickToggles(alert: ShareAlert, frameLayout: FrameLayout) {
        val state = getState(alert)
        if (!state.active) return
        val context = alert.context ?: return
        val theme = alert.resourcesProvider
        val tintColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, theme)
        val bgColor = Theme.getColor(Theme.key_actionBarWhiteSelector, theme)

        fun makeToggle(iconRes: Int, descRes: Int): ImageView {
            return ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER
                setImageResource(iconRes)
                colorFilter = PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
                background = Theme.createSelectorDrawable(bgColor, Theme.RIPPLE_MASK_CIRCLE_20DP, dp(16f))
                contentDescription = LocaleController.getString(descRes)
            }
        }

        val authorIcon = makeToggle(R.drawable.msg_openprofile, R.string.ShowSendersName)
        val silentIcon = makeToggle(R.drawable.input_notify_off, R.string.SendWithoutSound)
        val captionIcon = makeToggle(R.drawable.outline_caption_24, R.string.InuForwardProHideCaption)
        state.authorIcon = authorIcon
        state.silentSendIcon = silentIcon
        state.captionIcon = captionIcon

        authorIcon.setOnClickListener {
            alert.showSendersName = !alert.showSendersName
            updateQuickToggleIcons(alert)
        }
        silentIcon.setOnClickListener {
            state.silentSend = !state.silentSend
            updateQuickToggleIcons(alert)
        }
        captionIcon.setOnClickListener {
            state.hideCaption = !state.hideCaption
            updateQuickToggleIcons(alert)
        }

        frameLayout.addView(authorIcon, LayoutHelper.createFrame(32, 32f, Gravity.TOP or Gravity.RIGHT, 0f, 11f, 11f, 0f))
        frameLayout.addView(silentIcon, LayoutHelper.createFrame(32, 32f, Gravity.TOP or Gravity.RIGHT, 0f, 11f, 47f, 0f))
        frameLayout.addView(captionIcon, LayoutHelper.createFrame(32, 32f, Gravity.TOP or Gravity.RIGHT, 0f, 11f, 83f, 0f))

        updateQuickToggleIcons(alert)
    }

    @JvmStatic
    fun updateQuickToggleIcons(alert: ShareAlert) {
        val state = getState(alert)
        if (!state.active) return
        state.authorIcon?.alpha = if (alert.showSendersName) 0.5f else 1f
        state.silentSendIcon?.alpha = if (state.silentSend) 1f else 0.5f
        state.captionIcon?.alpha = if (state.hideCaption) 1f else 0.5f
    }

    // entiny: folder-tab strip via stock FilterTabsView; dialogFilters already has the default "All Chats" entry (id 0).
    @JvmStatic
    fun attachFolderTabs(alert: ShareAlert, frameLayout: FrameLayout) {
        val state = getState(alert)
        if (!state.active) return
        val context = alert.context ?: return
        val filters = MessagesController.getInstance(alert.currentAccount).dialogFilters
        if (filters.isNullOrEmpty()) return

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
        for (filter in filters) {
            val title = if (filter.isDefault) LocaleController.getString(R.string.FilterAllChats) else filter.name
            tabsView.addTab(filter.id, filter.id, title, true, filter.isDefault, false)
        }
        tabsView.finishAddingTabs(false)

        state.filterTabsView = tabsView
        state.selectedFilterId = filters.firstOrNull { it.isDefault }?.id ?: filters[0].id
        // entiny: deferred - adding this inline re-entered FilterTabsView's listView mid-layout and crashed RecyclerView.State.
        frameLayout.post {
            frameLayout.addView(
                tabsView,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, FolderHelper.TAB_BAR_HEIGHT_DP.toFloat(), Gravity.TOP or Gravity.LEFT, 0f, FOLDER_TABS_TOP_MARGIN_DP, 0f, 0f)
            )
        }
    }

    @JvmStatic
    fun isDialogAllowedByFolder(alert: ShareAlert, dialogId: Long): Boolean {
        val state = getState(alert)
        if (!state.active) return true
        val filter = MessagesController.getInstance(alert.currentAccount).dialogFilters
            ?.firstOrNull { it.id == state.selectedFilterId } ?: return true
        if (filter.isDefault) return true
        // entiny: filter.dialogs is only populated when this filter is one of DialogsActivity's own selectedDialogFilter slots — use includesDialog() instead, it's self-contained.
        return filter.includesDialog(AccountInstance.getInstance(alert.currentAccount), dialogId)
    }

    @JvmStatic
    fun getFolderTabsHeightDp(alert: ShareAlert): Int {
        if (!getState(alert).active) return 0
        val filters = MessagesController.getInstance(alert.currentAccount).dialogFilters
        if (filters.isNullOrEmpty()) return 0
        // entiny: total header needed for the tab strip minus the stock 58dp band search already sits in
        return (FOLDER_TABS_TOP_MARGIN_DP + FolderHelper.TAB_BAR_HEIGHT_DP + FOLDER_TABS_BOTTOM_GAP_DP - 58).toInt()
    }

    @JvmStatic
    fun attachHideCaptionRow(alert: ShareAlert, sendPopupLayout1: ActionBarPopupWindow.ActionBarPopupWindowLayout, darkTheme: Boolean) {
        val state = getState(alert)
        if (!state.active) return
        val context = alert.context ?: return
        val hideCaptionView = ActionBarMenuSubItem(context, true, false, true, alert.resourcesProvider)
        if (darkTheme) {
            hideCaptionView.setTextColor(Theme.getColor(Theme.key_voipgroup_nameText, alert.resourcesProvider))
        }
        sendPopupLayout1.addView(hideCaptionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))
        hideCaptionView.setTextAndIcon(LocaleController.getString(R.string.InuForwardProHideCaption), 0)
        hideCaptionView.setChecked(state.hideCaption)
        hideCaptionView.setOnClickListener {
            state.hideCaption = !state.hideCaption
            hideCaptionView.setChecked(state.hideCaption)
        }
    }

    @JvmStatic
    fun attachEditButton(alert: ShareAlert, writeButtonContainer: FrameLayout) {
        val state = getState(alert)
        if (!state.active) return
        val context = alert.context ?: return
        val msgs = alert.sendingMessageObjects
        if (msgs == null || msgs.isEmpty()) return

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

        // entiny: anchored from the container's right edge (where the send circle sits), not the left.
        writeButtonContainer.addView(
            editButton,
            LayoutHelper.createFrame(38, 38f, Gravity.RIGHT or Gravity.BOTTOM, 0f, 0f, 65f, 10f)
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

                alert.showSendersName = false

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

