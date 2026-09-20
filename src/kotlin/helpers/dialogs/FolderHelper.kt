package desu.inugram.helpers.dialogs

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.Pair
import androidx.core.content.edit
import androidx.core.graphics.withSave
import desu.inugram.InuConfig
import desu.inugram.helpers.security.ParanoiaHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.FilterTabsView
import org.telegram.ui.Stories.recorder.HintView2
import kotlin.math.ceil

object FolderHelper {
    private val folderIcons = mapOf(
        "\uD83D\uDC31" to R.drawable.filter_cat,
        "\uD83D\uDCD5" to R.drawable.filter_book,
        "\uD83D\uDCB0" to R.drawable.filter_money,
        "\uD83C\uDFAE" to R.drawable.filter_game,
        "\uD83D\uDCA1" to R.drawable.filter_light,
        "\uD83D\uDC4C" to R.drawable.filter_like,
        "\uD83C\uDFB5" to R.drawable.filter_note,
        "\uD83C\uDFA8" to R.drawable.filter_palette,
        "\u2708" to R.drawable.filter_travel,
        "\u26BD" to R.drawable.filter_sport,
        "\u2B50" to R.drawable.filter_favorite,
        "\uD83C\uDF93" to R.drawable.filter_study,
        "\uD83D\uDEEB" to R.drawable.filter_airplane,
        "\uD83D\uDC64" to R.drawable.filter_private,
        "\uD83D\uDC65" to R.drawable.filter_group,
        "\uD83D\uDCAC" to R.drawable.filter_all,
        "\u2705" to R.drawable.filter_unread,
        "\uD83E\uDD16" to R.drawable.filter_bots,
        "\uD83D\uDC51" to R.drawable.filter_crown,
        "\uD83C\uDF39" to R.drawable.filter_flower,
        "\uD83C\uDFE0" to R.drawable.filter_home,
        "\u2764" to R.drawable.filter_love,
        "\uD83C\uDFAD" to R.drawable.filter_mask,
        "\uD83C\uDF78" to R.drawable.filter_party,
        "\uD83D\uDCC8" to R.drawable.filter_trade,
        "\uD83D\uDCBC" to R.drawable.filter_work,
        "\uD83D\uDD14" to R.drawable.filter_unmuted,
        "\uD83D\uDCE2" to R.drawable.filter_channels,
        "\uD83D\uDCC1" to R.drawable.filter_custom,
        "\uD83D\uDCCB" to R.drawable.filter_setup,
    )

    const val ICON_GAP = 4

    @JvmStatic
    fun getIconChoices(): List<Pair<String, Int>> = folderIcons.entries.map { Pair(it.key, it.value) }

    private fun getIconSize(): Int {
        return if (isIconsOnly()) 28 else 24
    }

    @JvmStatic
    fun saveMeta(storage: MessagesStorage, filters: List<MessagesController.DialogFilter>) {
        saveMetaMap(storage, HashMap<Int, String?>().also { map -> for (filter in filters) map[filter.id] = filter.inu_emoticon })
    }

    @JvmStatic
    fun saveMetaMap(storage: MessagesStorage, emoticons: Map<Int, String?>) {
        val db = storage.database ?: return
        db.executeFast("DELETE FROM inu_folder_meta").stepThis().dispose()
        val state = db.executeFast("REPLACE INTO inu_folder_meta VALUES(?, ?)")
        try {
            for ((id, emoticon) in emoticons) {
                state.requery()
                state.bindInteger(1, id)
                state.bindString(2, emoticon ?: "")
                state.step()
            }
        } finally {
            state.dispose()
        }
    }

    @JvmStatic
    fun loadMeta(storage: MessagesStorage, account: Int, filters: List<MessagesController.DialogFilter>) {
        val db = storage.database ?: return
        val map = HashMap<Int, String>()
        val cursor = db.queryFinalized("SELECT filter_id, emoticon FROM inu_folder_meta")
        try {
            while (cursor.next()) {
                map[cursor.intValue(0)] = cursor.stringValue(1)
            }
        } finally {
            cursor.dispose()
        }
        var hasMissing = false
        for (filter in filters) {
            val cached = map[filter.id]
            if (cached == null) hasMissing = true
            filter.inu_emoticon = if (cached.isNullOrEmpty()) null else cached
        }
        if (hasMissing) {
            val userConfig = UserConfig.getInstance(account)
            userConfig.filtersLoaded = false
            userConfig.preferences.edit { putBoolean("filtersLoaded", false) }
        }
    }

    @JvmStatic
    fun getDefaultsFromFlags(filterFlags: Int): Pair<String, String> {
        val allChats = MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS
        var flags = filterFlags and allChats

        if (flags and allChats == allChats) {
            if (filterFlags and MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ != 0) {
                return Pair.create(getString(R.string.FilterNameUnread), "\u2705")
            }
            if (filterFlags and MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED != 0) {
                return Pair.create(getString(R.string.FilterNameNonMuted), "\uD83D\uDD14")
            }
        } else if (flags and MessagesController.DIALOG_FILTER_FLAG_CONTACTS != 0) {
            flags = flags and MessagesController.DIALOG_FILTER_FLAG_CONTACTS.inv()
            if (flags == 0 || flags == MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS) {
                return Pair.create(getString(R.string.FilterContacts), "\uD83D\uDC64")
            }
        } else if (flags and MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS != 0) {
            flags = flags and MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS.inv()
            if (flags == 0) return Pair.create(getString(R.string.FilterNonContacts), "\uD83D\uDC64")
        } else if (flags and MessagesController.DIALOG_FILTER_FLAG_GROUPS != 0) {
            flags = flags and MessagesController.DIALOG_FILTER_FLAG_GROUPS.inv()
            if (flags == 0) return Pair.create(getString(R.string.FilterGroups), "\uD83D\uDC65")
        } else if (flags and MessagesController.DIALOG_FILTER_FLAG_BOTS != 0) {
            flags = flags and MessagesController.DIALOG_FILTER_FLAG_BOTS.inv()
            if (flags == 0) return Pair.create(getString(R.string.FilterBots), "\uD83E\uDD16")
        } else if (flags and MessagesController.DIALOG_FILTER_FLAG_CHANNELS != 0) {
            flags = flags and MessagesController.DIALOG_FILTER_FLAG_CHANNELS.inv()
            if (flags == 0) return Pair.create(getString(R.string.FilterChannels), "\uD83D\uDCE2")
        }

        return Pair.create("", "")
    }

    @JvmStatic
    fun getTabInfo(filter: MessagesController.DialogFilter): Pair<String, String> {
        val defaults = getDefaultsFromFlags(filter.flags)
        val name = filter.name?.takeIf { it.isNotEmpty() } ?: defaults.first
        val emoticon = filter.inu_emoticon?.takeIf { it.isNotEmpty() } ?: defaults.second
        return Pair.create(name, emoticon)
    }

    @JvmStatic
    fun getTabIcon(emoticon: String?): Int {
        if (emoticon != null) {
            val stripped = emoticon.replace("\uFE0F", "")
            return folderIcons[stripped] ?: R.drawable.filter_custom
        }
        return R.drawable.filter_custom
    }

    @JvmStatic
    fun getContentWidth(title: CharSequence?, textPaint: TextPaint): Int {
        val mode = InuConfig.FOLDERS_DISPLAY_MODE.value;
        if (mode == InuConfig.FoldersDisplayModeItem.ICONS_ONLY) return AndroidUtilities.dp(getIconSize().toFloat())

        var w = ceil(HintView2.measureCorrectly(title, textPaint)).toInt();
        if (mode == InuConfig.FoldersDisplayModeItem.TITLES_AND_ICONS) w += AndroidUtilities.dp((getIconSize() + ICON_GAP).toFloat());

        return w
    }

    @JvmStatic
    fun needIcons(): Boolean {
        return InuConfig.FOLDERS_DISPLAY_MODE.value != InuConfig.FoldersDisplayModeItem.TITLES
    }

    @JvmStatic
    fun isIconsOnly(): Boolean {
        return InuConfig.FOLDERS_DISPLAY_MODE.value == InuConfig.FoldersDisplayModeItem.ICONS_ONLY
    }

    @JvmStatic
    fun isTitleOnly(): Boolean {
        return InuConfig.FOLDERS_DISPLAY_MODE.value == InuConfig.FoldersDisplayModeItem.TITLES
    }

    @JvmStatic
    fun getTextXOffset(): Float {
        if (InuConfig.FOLDERS_DISPLAY_MODE.value != InuConfig.FoldersDisplayModeItem.TITLES_AND_ICONS) return 0f
        return AndroidUtilities.dp((getIconSize() + ICON_GAP).toFloat()).toFloat()
    }

    @JvmStatic
    fun drawTabIcon(
        canvas: Canvas,
        icon: Drawable?,
        colorFilter: ColorFilter?,
        textX: Float,
        viewWidth: Int,
        viewHeight: Int
    ) {
        if (icon == null || !needIcons()) return

        val iconPx = AndroidUtilities.dp(getIconSize().toFloat())
        icon.colorFilter = colorFilter
        icon.setBounds(0, 0, iconPx, iconPx)
        canvas.withSave {
            translate(textX, (viewHeight - iconPx) / 2f)
            icon.draw(this)
        }
    }

    @JvmStatic
    fun getTabPadding(): Float {
        if (isIconsOnly()) return 16f
        return FilterTabsView.TAB_PADDING_WIDTH
    }

    @JvmStatic
    fun shouldSkipDefaultTab(totalFilters: Int): Boolean {
        return InuConfig.HIDE_ALL_CHATS_TAB.value && totalFilters > 1
    }

    @JvmStatic
    fun snapOffDefault(filters: List<MessagesController.DialogFilter>, selectedType: Int): Int {
        if (!shouldSkipDefaultTab(filters.size)) return selectedType
        if (selectedType !in filters.indices || !filters[selectedType].isDefault) return selectedType
        return filters.indexOfFirst { !it.isDefault }.takeIf { it >= 0 } ?: selectedType
    }

    @JvmStatic
    fun refreshSelectedTab(filterTabsView: FilterTabsView, selectedType: Int, filtersSize: Int) {
        if (!InuConfig.HIDE_ALL_CHATS_TAB.value) return
        if (selectedType < 0 || selectedType >= filtersSize) return
        filterTabsView.selectTabWithId(selectedType, 1f)
    }

    @JvmStatic
    fun isMuteFilteringActive(): Boolean {
        val mode = InuConfig.FOLDERS_UNREAD_COUNTER_MODE.value
        return mode == InuConfig.FoldersUnreadCounterModeItem.EXCLUDE_MUTED ||
            mode == InuConfig.FoldersUnreadCounterModeItem.EXCLUDE_MUTED_NON_DMS
    }

    @JvmStatic
    @JvmOverloads
    fun shouldExcludeFromCounter(currentAccount: Int, dialogId: Long, user: TLRPC.User? = null): Boolean {
        if (ParanoiaHelper.isHidden(currentAccount, dialogId)) return true
        val mode = InuConfig.FOLDERS_UNREAD_COUNTER_MODE.value
        if (mode == InuConfig.FoldersUnreadCounterModeItem.EXCLUDE_MUTED_NON_DMS && dialogId > 0) {
            if (user == null || !user.bot) return false
        }
        if (mode != InuConfig.FoldersUnreadCounterModeItem.EXCLUDE_MUTED &&
            mode != InuConfig.FoldersUnreadCounterModeItem.EXCLUDE_MUTED_NON_DMS
        ) return false
        return MessagesController.getInstance(currentAccount).isDialogMuted(dialogId, 0)
    }

    @JvmStatic
    fun getTabInternalPadding(): Float {
        if (isIconsOnly()) return FilterTabsView.TAB_INTERNAL_PADDING / 2f
        if (InuConfig.FOLDERS_DISPLAY_MODE.value == InuConfig.FoldersDisplayModeItem.TITLES_AND_ICONS) return 8f
        return FilterTabsView.TAB_INTERNAL_PADDING
    }

    const val TAB_BAR_HEIGHT_DP = 36 + 7 + 7

    @JvmStatic
    fun getTabBarHeightDp(): Int = TAB_BAR_HEIGHT_DP

    const val TAB_BAR_BOTTOM_MARGIN_DP = 14

    @JvmStatic
    fun atBottom(): Boolean = InuConfig.FOLDERS_AT_BOTTOM.value

    @JvmStatic
    fun bottomReservedHeightDp(): Int = getTabBarHeightDp() + TAB_BAR_BOTTOM_MARGIN_DP

    @JvmStatic
    @JvmOverloads
    fun bottomTabsTranslationY(
        navigationBarHeight: Int,
        additionFloatingButtonOffset: Int,
        additionalFloatingTranslation: Float = 0f,
        floatingButtonPanOffset: Float = 0f
    ): Float {
        val baseOffset = additionFloatingButtonOffset - AndroidUtilities.dp(bottomReservedHeightDp().toFloat())
        return -navigationBarHeight - baseOffset - floatingButtonPanOffset
    }
}
