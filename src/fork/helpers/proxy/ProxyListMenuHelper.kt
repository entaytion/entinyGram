package desu.inugram.helpers.proxy

import android.content.Intent
import desu.inugram.InuConfig
import desu.inugram.helpers.ProxyImportHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.SimpleActionBar
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory

object ProxyListMenuHelper {
    private const val MENU_MORE = 100
    private const val MENU_IMPORT = 101
    private const val MENU_COPY_ALL = 102
    private const val MENU_SHARE_ALL = 103
    private const val MENU_PING_ALL = 104
    private const val MENU_DELETE_FAILED = 105
    private const val MENU_DELETE_ALL = 106

    @JvmStatic
    fun create(actionBar: SimpleActionBar?): ActionBarMenuItem? {
        if (!InuConfig.PROXY_LIST_MENU.value || actionBar == null) return null
        val item = actionBar.createMenu().addItem(MENU_MORE, R.drawable.ic_ab_other)
        item.contentDescription = LocaleController.getString(R.string.AccDescrMoreOptions)
        item.addSubItem(MENU_IMPORT, R.drawable.msg_addbot, LocaleController.getString(R.string.InuProxyImportFromClipboard))
        item.addSubItem(MENU_COPY_ALL, R.drawable.msg_copy, LocaleController.getString(R.string.InuCopyAllProxies))
        item.addSubItem(MENU_SHARE_ALL, R.drawable.msg_share, LocaleController.getString(R.string.InuShareAllProxies))
        item.addSubItem(MENU_PING_ALL, R.drawable.msg_retry, LocaleController.getString(R.string.InuPingAllProxies))
        item.addSubItem(MENU_DELETE_FAILED, R.drawable.msg_delete, LocaleController.getString(R.string.InuDeleteFailedProxies)).also(::styleDestructive)
        item.addSubItem(MENU_DELETE_ALL, R.drawable.msg_delete, LocaleController.getString(R.string.DeleteAllProxies)).also(::styleDestructive)
        return item
    }

    @JvmStatic
    fun update(item: ActionBarMenuItem?, list: List<SharedConfig.ProxyInfo>) {
        if (item == null) return
        setShown(item, MENU_COPY_ALL, list.isNotEmpty())
        setShown(item, MENU_SHARE_ALL, list.isNotEmpty())
        setShown(item, MENU_PING_ALL, list.size >= 2)
        setShown(item, MENU_DELETE_FAILED, list.any(ProxyListToolsHelper::isFailed))
        setShown(item, MENU_DELETE_ALL, list.isNotEmpty())
    }

    @JvmStatic
    fun handle(
        fragment: BaseFragment,
        id: Int,
        list: List<SharedConfig.ProxyInfo>,
        onPing: Runnable,
        onDeleted: Utilities.Callback<Boolean>,
    ): Boolean {
        when (id) {
            MENU_IMPORT -> ProxyImportHelper.importFromClipboard(fragment)
            MENU_COPY_ALL -> {
                if (AndroidUtilities.addToClipboard(links(list)) && AndroidUtilities.shouldShowClipboardToast()) {
                    BulletinFactory.of(fragment).createCopyLinkBulletin().show()
                }
            }
            MENU_SHARE_ALL -> {
                val activity = fragment.parentActivity ?: return true
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, links(list))
                }
                val chooser = Intent.createChooser(intent, LocaleController.getString(R.string.ShareLinks))
                chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                activity.startActivity(chooser)
            }
            MENU_PING_ALL -> {
                ProxyListToolsHelper.resetCheckTimes(list)
                onPing.run()
            }
            MENU_DELETE_FAILED -> ProxyListToolsHelper.confirmDeleteFailed(fragment, list, onDeleted)
            MENU_DELETE_ALL -> ProxyListToolsHelper.confirmDeleteAll(fragment, list, onDeleted)
            else -> return false
        }
        return true
    }

    private fun links(list: List<SharedConfig.ProxyInfo>): String =
        list.joinToString("\n\n") { it.settings.link }

    private fun styleDestructive(sub: ActionBarMenuSubItem) {
        sub.setIconColor(Theme.getColor(Theme.key_text_RedRegular))
        sub.setTextColor(Theme.getColor(Theme.key_text_RedBold))
        sub.setSelectorColorKey(Theme.key_text_RedRegular, .12f)
    }

    private fun setShown(item: ActionBarMenuItem, id: Int, shown: Boolean) {
        if (shown) item.showSubItem(id) else item.hideSubItem(id)
    }
}
