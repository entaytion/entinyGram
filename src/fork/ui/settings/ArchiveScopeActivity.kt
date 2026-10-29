package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.archive.ArchiveScope
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.UsersSelectActivity

// entiny: exceptions of one archive; the picker is the one of chat folders, idea from AyuGram's exceptions screen
class ArchiveScopeActivity(private val kind: ArchiveScope.Kind) : SettingsPageActivity() {

    private val never: Boolean get() = ArchiveScope.mode(kind) == ArchiveScope.MODE_ALL

    override fun getTitle(): CharSequence = LocaleController.getString(titleRes(kind))

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val mode = ArchiveScope.mode(kind)
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuArchiveScopeMode)))
        items.add(UItem.asRadio(RADIO_ALL, LocaleController.getString(R.string.InuArchiveScopeModeAll)).setChecked(mode == ArchiveScope.MODE_ALL))
        items.add(UItem.asRadio(RADIO_ONLY, LocaleController.getString(R.string.InuArchiveScopeModeOnly)).setChecked(mode == ArchiveScope.MODE_ONLY))
        items.add(UItem.asShadow(LocaleController.getString(if (never) R.string.InuArchiveScopeInfoAll else R.string.InuArchiveScopeInfoOnly)))

        val entry = ArchiveScope.entry(kind, never)
        items.add(UItem.asHeader(LocaleController.getString(if (never) R.string.InuArchiveScopeNever else R.string.InuArchiveScopeAlways)))
        items.add(UItem.asButton(BUTTON_ADD, R.drawable.msg2_chats_add, LocaleController.getString(R.string.InuArchiveScopeAdd)))
        for ((flag, type, label) in typeRows()) {
            if (entry.flags and flag != 0) items.add(UItem.asFilterChat(true, label, type, flag))
        }
        for (id in entry.ids) items.add(UItem.asFilterChat(true, id))
        items.add(UItem.asCheck(CHECK_SECRET, LocaleController.getString(R.string.InuArchiveScopeSecret)).setChecked(entry.flags and ArchiveScope.FLAG_SECRET != 0))
        items.add(UItem.asCheck(CHECK_BOTS_IN_GROUPS, LocaleController.getString(R.string.InuArchiveScopeBotsInGroups)).setChecked(entry.flags and ArchiveScope.FLAG_BOT_IN_GROUP != 0))
        items.add(UItem.asShadow(null))

        if (kind == ArchiveScope.Kind.MEDIA) addLimits(items)
    }

    private fun addLimits(items: ArrayList<UItem>) {
        val options = ArchiveScope.mediaLimitOptions()
        val labels = Array(options.size) { index ->
            if (options[index] == 0) LocaleController.getString(R.string.InuArchiveLimitDefault)
            else AndroidUtilities.formatFileSize(options[index] * 1024L * 1024L)
        }
        for ((header, config) in listOf(
            R.string.InuArchiveLimitWifi to InuConfig.ARCHIVE_MEDIA_LIMIT_WIFI,
            R.string.InuArchiveLimitMobile to InuConfig.ARCHIVE_MEDIA_LIMIT_MOBILE,
        )) {
            items.add(UItem.asHeader(LocaleController.getString(header)))
            val chosen = options.indexOf(config.value).coerceAtLeast(0)
            items.add(UItem.asSlideView(labels, chosen) { index -> config.value = options[index] })
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuArchiveLimitInfo)))
    }

    private fun typeRows(): List<Triple<Int, String, String>> = listOf(
        Triple(MessagesController.DIALOG_FILTER_FLAG_CONTACTS, "contacts", LocaleController.getString(R.string.FilterContacts)),
        Triple(MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS, "non_contacts", LocaleController.getString(R.string.FilterNonContacts)),
        Triple(MessagesController.DIALOG_FILTER_FLAG_GROUPS, "groups", LocaleController.getString(R.string.FilterGroups)),
        Triple(MessagesController.DIALOG_FILTER_FLAG_CHANNELS, "channels", LocaleController.getString(R.string.FilterChannels)),
        Triple(MessagesController.DIALOG_FILTER_FLAG_BOTS, "bots", LocaleController.getString(R.string.FilterBots)),
    )

    private fun update(change: (ArchiveScope.Entry) -> ArchiveScope.Entry) {
        ArchiveScope.save(kind, never, change(ArchiveScope.entry(kind, never)))
        listView?.adapter?.update(true)
    }

    private fun addChats() {
        val entry = ArchiveScope.entry(kind, never)
        val picker = UsersSelectActivity(true, ArrayList(entry.ids), entry.flags and STOCK_FLAGS)
        picker.setDelegate { ids, flags ->
            update { ArchiveScope.Entry((it.flags and STOCK_FLAGS.inv()) or (flags and STOCK_FLAGS), ids) }
        }
        presentFragment(picker)
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.viewType == UniversalAdapter.VIEW_TYPE_FILTER_CHAT) {
            if (item.dialogId != 0L) {
                update { ArchiveScope.Entry(it.flags, it.ids - item.dialogId) }
            } else {
                update { ArchiveScope.Entry(it.flags and item.flags.inv(), it.ids) }
            }
            return
        }
        when (item.id) {
            RADIO_ALL -> setMode(ArchiveScope.MODE_ALL)
            RADIO_ONLY -> setMode(ArchiveScope.MODE_ONLY)
            BUTTON_ADD -> addChats()
            CHECK_SECRET -> toggleExtra(ArchiveScope.FLAG_SECRET)
            CHECK_BOTS_IN_GROUPS -> toggleExtra(ArchiveScope.FLAG_BOT_IN_GROUP)
        }
    }

    private fun setMode(mode: Int) {
        if (ArchiveScope.mode(kind) == mode) return
        ArchiveScope.setMode(kind, mode)
        listView?.adapter?.update(true)
    }

    private fun toggleExtra(flag: Int) {
        update { ArchiveScope.Entry(it.flags xor flag, it.ids) }
    }

    companion object {
        private val STOCK_FLAGS = MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS
        private val RADIO_ALL = InuUtils.generateId()
        private val RADIO_ONLY = InuUtils.generateId()
        private val BUTTON_ADD = InuUtils.generateId()
        private val CHECK_SECRET = InuUtils.generateId()
        private val CHECK_BOTS_IN_GROUPS = InuUtils.generateId()

        private fun titleRes(kind: ArchiveScope.Kind): Int = when (kind) {
            ArchiveScope.Kind.DELETED -> R.string.InuSaveDeletedMessages
            ArchiveScope.Kind.EDITED -> R.string.InuEditHistory
            ArchiveScope.Kind.MEDIA -> R.string.InuArchiveAttachments
        }

        @JvmStatic
        fun summary(kind: ArchiveScope.Kind): String {
            val (mode, count) = ArchiveScope.summary(kind)
            return when {
                mode == ArchiveScope.MODE_ONLY -> LocaleController.formatString(R.string.InuArchiveScopeOnly, count)
                count == 0 -> LocaleController.getString(R.string.InuArchiveScopeAll)
                else -> LocaleController.formatString(R.string.InuArchiveScopeExcept, count)
            }
        }
    }
}
