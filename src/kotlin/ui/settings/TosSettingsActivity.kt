package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuDatabaseHelper
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.chat.SavedMessagesHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class TosSettingsActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuTOS)

    private var cachedSizeText: String? = null

    private val selfDestructGroup = ExpandableBoolGroup(
        LocaleController.getString(R.string.InuSelfDestructMedia),
        listOf(
            ExpandableBoolGroup.Option(R.string.InuSaveSelfDestruct, InuConfig.SAVE_SELF_DESTRUCT, TOGGLE_SAVE_SELF_DESTRUCT),
            ExpandableBoolGroup.Option(R.string.InuSaveSelfDestructMedia, InuConfig.SAVE_SELF_DESTRUCT_MEDIA, TOGGLE_SAVE_SELF_DESTRUCT_MEDIA),
            ExpandableBoolGroup.Option(R.string.InuSaveSelfDestructText, InuConfig.SAVE_SELF_DESTRUCT_TEXT, TOGGLE_SAVE_SELF_DESTRUCT_TEXT),
            ExpandableBoolGroup.Option(R.string.InuSaveSecretChatContent, InuConfig.SAVE_SECRET_CHAT_CONTENT, TOGGLE_SAVE_SECRET_CHAT_CONTENT),
            ExpandableBoolGroup.Option(R.string.InuSaveViewOnceMedia, InuConfig.SAVE_VIEW_ONCE_MEDIA, TOGGLE_SAVE_VIEW_ONCE_MEDIA),
            ExpandableBoolGroup.Option(R.string.InuSaveTimedMessages, InuConfig.SAVE_TIMED_MESSAGES, TOGGLE_SAVE_TIMED_MESSAGES),
        ),
        sectionId = SECTION_SELF_DESTRUCT_SAVE,
    ).apply { expanded = true }

    private val deletedEditedGroup = ExpandableBoolGroup(
        LocaleController.getString(R.string.InuDeletedEditedSave),
        listOf(
            ExpandableBoolGroup.Option(R.string.InuSaveDeletedMessages, InuConfig.SAVE_DELETED_MESSAGES, TOGGLE_SAVE_DELETED_MESSAGES),
            ExpandableBoolGroup.Option(R.string.InuSaveEditedMessages, InuConfig.SAVE_EDITED_MESSAGES, TOGGLE_SAVE_EDITED_MESSAGES),
        ),
        sectionId = SECTION_DELETED_EDITED_SAVE,
    ).apply { expanded = true }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asButton(
                BUTTON_BETA_INFO,
                R.drawable.ic_beta_badge,
                LocaleController.getString(R.string.InuBetaFeatureTitle)
            )
        )
        items.add(UItem.asShadow(null))

        // Ghost Mode Section
        items.add(
            mkSubPageButton(
                BUTTON_GHOST,
                R.drawable.inu_ghost,
                LocaleController.getString(R.string.InuGhostMode)
            )
        )
        items.add(UItem.asShadow(null))

        // Unreader Section
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuUnreader)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_UNREADER,
                R.string.InuUnreader,
                R.string.InuUnreaderInfo,
                InuConfig.UNREADER.value,
            )
        )
        items.add(UItem.asShadow(null))

        // Anti-deletion Section
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAntiDeletion)))
        selfDestructGroup.addTo(items) { listView?.adapter?.update(true) }
        deletedEditedGroup.addTo(items) { listView?.adapter?.update(true) }
        if (InuConfig.SAVE_EDITED_MESSAGES.value) {
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_SHOW_EDIT_HISTORY_DIFF,
                    R.string.InuEditHistoryDiff,
                    R.string.InuEditHistoryDiffInfo,
                    InuConfig.SHOW_EDIT_HISTORY_DIFF.value,
                )
            )
        }
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SAVE_ANY_STORY,
                R.string.InuSaveAnyStory,
                R.string.InuSaveAnyStoryInfo,
                InuConfig.SAVE_ANY_STORY.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_ALLOW_FORWARD_RESTRICTED,
                R.string.InuAllowForwardRestricted,
                R.string.InuAllowForwardRestrictedInfo,
                InuConfig.ALLOW_FORWARD_RESTRICTED.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SAVE_USER_INFO,
                R.string.InuSaveUserInfo,
                R.string.InuSaveUserInfoInfo,
                InuConfig.SAVE_USER_INFO.value,
            )
        )
        items.add(UItem.asShadow(null))

        // Cache & Storage Section
        if (InuConfig.SAVE_DELETED_MESSAGES.value || InuConfig.SAVE_EDITED_MESSAGES.value) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.InuCacheTtl)))
            items.add(UItem.asButton(BUTTON_CACHE_TTL, LocaleController.getString(R.string.InuCacheTtl)).also {
                it.subtext = ttlLabel(InuConfig.DELETED_MESSAGES_TTL.value)
            })
            items.add(UItem.asButton(BUTTON_CLEAR_DELETED_CACHE, LocaleController.getString(R.string.InuClearDeletedCache)).also {
                if (cachedSizeText != null) {
                    it.subtext = cachedSizeText
                }
            })
        }
    }

    private fun ttlLabel(days: Int): String = when (days) {
        InuConfig.DeletedMessagesTtlItem.ONE_DAY -> LocaleController.getString(R.string.InuCacheTtlDay)
        InuConfig.DeletedMessagesTtlItem.ONE_WEEK -> LocaleController.getString(R.string.InuCacheTtlWeek)
        InuConfig.DeletedMessagesTtlItem.ONE_MONTH -> LocaleController.getString(R.string.InuCacheTtlMonth)
        else -> LocaleController.getString(R.string.InuCacheTtlNever)
    }

    private fun showBetaBottomSheet() {
        org.telegram.ui.Components.BulletinFactory.of(this)
            .createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.InuBetaFeatureInfo))
            .show()
    }

    private fun showTtlDialog() {
        val context = context ?: return
        val values = intArrayOf(
            InuConfig.DeletedMessagesTtlItem.NEVER,
            InuConfig.DeletedMessagesTtlItem.ONE_DAY,
            InuConfig.DeletedMessagesTtlItem.ONE_WEEK,
            InuConfig.DeletedMessagesTtlItem.ONE_MONTH,
        )
        val radioItems = listOf(
            RadioDialogBuilder.Item(LocaleController.getString(R.string.InuCacheTtlNever)),
            RadioDialogBuilder.Item(LocaleController.getString(R.string.InuCacheTtlDay)),
            RadioDialogBuilder.Item(LocaleController.getString(R.string.InuCacheTtlWeek)),
            RadioDialogBuilder.Item(LocaleController.getString(R.string.InuCacheTtlMonth)),
        )
        showDialog(
            RadioDialogBuilder(context, getResourceProvider())
                .setTitle(LocaleController.getString(R.string.InuCacheTtl))
                .setSubtitle(LocaleController.getString(R.string.InuCacheTtlInfo))
                .setItems(radioItems, values.indexOf(InuConfig.DELETED_MESSAGES_TTL.value).coerceAtLeast(0)) { _, which ->
                    val newVal = values[which]
                    if (InuConfig.DELETED_MESSAGES_TTL.value == newVal) return@setItems
                    InuConfig.DELETED_MESSAGES_TTL.value = newVal
                    // If TTL changed to non-never, immediately prune
                    if (newVal != InuConfig.DeletedMessagesTtlItem.NEVER) {
                        SavedMessagesHelper.pruneIfNeeded(UserConfig.selectedAccount)
                    }
                    listView?.adapter?.update(true)
                }
                .create()
        )
    }

    private fun showClearCacheDialog() {
        val context = context ?: return
        val account = UserConfig.selectedAccount
        val storage = org.telegram.messenger.MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val db = storage.database ?: return@postRunnable
            val stats = InuDatabaseHelper.getDeletedMessagesStats(db)
            org.telegram.messenger.AndroidUtilities.runOnUIThread {
                if (stats.isEmpty()) {
                    cachedSizeText = AndroidUtilities.formatFileSize(0)
                    listView?.adapter?.update(true)
                    org.telegram.ui.Components.BulletinFactory.of(this@TosSettingsActivity)
                        .createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.InuClearDeletedCacheEmpty))
                        .show()
                    return@runOnUIThread
                }

                val totalSize = stats.sumOf { it.estimatedSize }
                cachedSizeText = AndroidUtilities.formatFileSize(totalSize)
                listView?.adapter?.update(true)

                val selected = BooleanArray(stats.size) { true }

                val builder = org.telegram.ui.ActionBar.BottomSheet.Builder(context)
                builder.setTitle(LocaleController.getString(R.string.InuClearDeletedCache))

                val container = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(8f), AndroidUtilities.dp(16f), AndroidUtilities.dp(16f))
                }

                val linearLayout = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                }

                fun calcSelectedSize(): Long {
                    var sum = 0L
                    stats.forEachIndexed { i, stat ->
                        if (selected[i]) sum += stat.estimatedSize
                    }
                    return sum
                }

                val buttonTextView = android.widget.TextView(context).apply {
                    setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(12f), AndroidUtilities.dp(16f), AndroidUtilities.dp(12f))
                    setGravity(android.view.Gravity.CENTER)
                    setTextColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_featuredStickers_buttonText))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15f)
                    setTypeface(AndroidUtilities.bold())
                    background = org.telegram.ui.ActionBar.Theme.createSimpleSelectorRoundRectDrawable(
                        AndroidUtilities.dp(8f),
                        org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_featuredStickers_addButton),
                        org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_featuredStickers_addButtonPressed)
                    )
                }

                fun updateButtonText() {
                    val selSize = calcSelectedSize()
                    buttonTextView.text = LocaleController.getString(R.string.Delete) + " (" + AndroidUtilities.formatFileSize(selSize) + ")"
                }

                stats.forEachIndexed { i, stat ->
                    val name = if (stat.dialogId == 0L) {
                        LocaleController.getString(R.string.SavedMessages)
                    } else {
                        val user = MessagesController.getInstance(account).getUser(stat.dialogId)
                        val chat = MessagesController.getInstance(account).getChat(-stat.dialogId)
                        val userName = if (user != null) org.telegram.messenger.UserObject.getUserName(user) else null
                        userName ?: chat?.title ?: "ID ${stat.dialogId}"
                    }
                    val sizeFormatted = AndroidUtilities.formatFileSize(stat.estimatedSize)
                    val text = "$name ($sizeFormatted)"
                    val value = LocaleController.formatPluralString("messages", stat.count)

                    val cell = org.telegram.ui.Cells.CheckBoxCell(context, 1, getResourceProvider()).apply {
                        setText(text, value, true, true)
                        setChecked(selected[i], false)
                        setTag(i)
                        setOnClickListener {
                            val idx = tag as Int
                            selected[idx] = !selected[idx]
                            setChecked(selected[idx], true)
                            updateButtonText()
                        }
                    }
                    linearLayout.addView(cell)
                }

                val scrollView = android.widget.ScrollView(context).apply {
                    addView(linearLayout)
                }

                val scrollParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1.0f
                ).apply {
                    bottomMargin = AndroidUtilities.dp(12f)
                }
                container.addView(scrollView, scrollParams)
                container.addView(buttonTextView, org.telegram.ui.Components.LayoutHelper.createLinear(org.telegram.ui.Components.LayoutHelper.MATCH_PARENT, org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT))

                updateButtonText()

                builder.setCustomView(container)
                val sheet = builder.create()

                buttonTextView.setOnClickListener {
                    val toDelete = stats.filterIndexed { index, _ -> selected[index] }.map { it.dialogId }
                    if (toDelete.isNotEmpty()) {
                        sheet.dismiss()
                        SavedMessagesHelper.clearCache(account, if (toDelete.size == stats.size) null else toDelete) {
                            cachedSizeText = null
                            listView?.adapter?.update(true)
                            org.telegram.ui.Components.BulletinFactory.of(this@TosSettingsActivity)
                                .createSimpleBulletin(R.raw.ic_delete, LocaleController.getString(R.string.InuClearDeletedCacheDone))
                                .show()
                        }
                    }
                }

                showDialog(sheet)
            }
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (selfDestructGroup.handleClick(item, view) { listView?.adapter?.update(true) }) return
        if (deletedEditedGroup.handleClick(item, view) { listView?.adapter?.update(true) }) return
        when (item.id) {
            BUTTON_BETA_INFO -> showBetaBottomSheet()
            BUTTON_GHOST -> presentFragment(GhostModeActivity())
            TOGGLE_UNREADER -> {
                val new = InuConfig.UNREADER.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_SHOW_EDIT_HISTORY_DIFF -> {
                val new = InuConfig.SHOW_EDIT_HISTORY_DIFF.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_ALLOW_FORWARD_RESTRICTED -> {
                val new = InuConfig.ALLOW_FORWARD_RESTRICTED.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            TOGGLE_SAVE_USER_INFO -> {
                val new = InuConfig.SAVE_USER_INFO.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }
            BUTTON_CACHE_TTL -> showTtlDialog()
            BUTTON_CLEAR_DELETED_CACHE -> showClearCacheDialog()
        }
    }

    companion object {
        private val BUTTON_BETA_INFO = InuUtils.generateId()
        private val BUTTON_GHOST = InuUtils.generateId()
        private val TOGGLE_UNREADER = InuUtils.generateId()
        private val TOGGLE_SAVE_SELF_DESTRUCT = InuUtils.generateId()
        private val TOGGLE_SAVE_SELF_DESTRUCT_MEDIA = InuUtils.generateId()
        private val TOGGLE_SAVE_SELF_DESTRUCT_TEXT = InuUtils.generateId()
        private val TOGGLE_SAVE_SECRET_CHAT_CONTENT = InuUtils.generateId()
        private val TOGGLE_SAVE_VIEW_ONCE_MEDIA = InuUtils.generateId()
        private val TOGGLE_SAVE_TIMED_MESSAGES = InuUtils.generateId()
        private val TOGGLE_SAVE_ANY_STORY = InuUtils.generateId()
        private val TOGGLE_SAVE_DELETED_MESSAGES = InuUtils.generateId()
        private val TOGGLE_SAVE_EDITED_MESSAGES = InuUtils.generateId()
        private val TOGGLE_SHOW_EDIT_HISTORY_DIFF = InuUtils.generateId()
        private val TOGGLE_ALLOW_FORWARD_RESTRICTED = InuUtils.generateId()
        private val TOGGLE_SAVE_USER_INFO = InuUtils.generateId()
        private val SECTION_SELF_DESTRUCT_SAVE = InuUtils.generateId()
        private val SECTION_DELETED_EDITED_SAVE = InuUtils.generateId()
        private val BUTTON_CACHE_TTL = InuUtils.generateId()
        private val BUTTON_CLEAR_DELETED_CACHE = InuUtils.generateId()

        @JvmField val PAGE = SearchRegistry.Page(
            slug = "tos",
            titleRes = R.string.InuTOS,
            iconRes = R.drawable.msg_autodelete,
            factory = ::TosSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("unreader", R.string.InuUnreader, TOGGLE_UNREADER),
                SearchRegistry.Entry("self-destruct-save", R.string.InuSelfDestructMedia, SECTION_SELF_DESTRUCT_SAVE),
                SearchRegistry.Entry("save-self-destruct", R.string.InuSaveSelfDestruct, TOGGLE_SAVE_SELF_DESTRUCT),
                SearchRegistry.Entry("save-self-destruct-media", R.string.InuSaveSelfDestructMedia, TOGGLE_SAVE_SELF_DESTRUCT_MEDIA),
                SearchRegistry.Entry("save-self-destruct-text", R.string.InuSaveSelfDestructText, TOGGLE_SAVE_SELF_DESTRUCT_TEXT),
                SearchRegistry.Entry("save-secret-chat-content", R.string.InuSaveSecretChatContent, TOGGLE_SAVE_SECRET_CHAT_CONTENT),
                SearchRegistry.Entry("save-view-once-media", R.string.InuSaveViewOnceMedia, TOGGLE_SAVE_VIEW_ONCE_MEDIA),
                SearchRegistry.Entry("save-timed-messages", R.string.InuSaveTimedMessages, TOGGLE_SAVE_TIMED_MESSAGES),
                SearchRegistry.Entry("save-any-story", R.string.InuSaveAnyStory, TOGGLE_SAVE_ANY_STORY),
                SearchRegistry.Entry("deleted-edited-save", R.string.InuDeletedEditedSave, SECTION_DELETED_EDITED_SAVE),
                SearchRegistry.Entry("save-deleted-messages", R.string.InuSaveDeletedMessages, TOGGLE_SAVE_DELETED_MESSAGES),
                SearchRegistry.Entry("save-edited-messages", R.string.InuSaveEditedMessages, TOGGLE_SAVE_EDITED_MESSAGES),
                SearchRegistry.Entry("edit-history-diff", R.string.InuEditHistoryDiff, TOGGLE_SHOW_EDIT_HISTORY_DIFF),
                SearchRegistry.Entry("allow-forward-restricted", R.string.InuAllowForwardRestricted, TOGGLE_ALLOW_FORWARD_RESTRICTED),
                SearchRegistry.Entry("save-user-info", R.string.InuSaveUserInfo, TOGGLE_SAVE_USER_INFO),
                SearchRegistry.Entry("cache-ttl", R.string.InuCacheTtl, BUTTON_CACHE_TTL),
            ),
        )
    }
}
