package desu.inugram.helpers

import android.view.View
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.UniversalRecyclerView
import org.telegram.ui.ProfileActivity

object SettingsSearchHelper {
    @JvmStatic
    fun showRecentOptions(
        fragment: BaseFragment,
        view: View,
        search: ProfileActivity.SearchAdapter,
        entry: Any,
        listView: UniversalRecyclerView,
    ) {
        val link = when (entry) {
            is ProfileActivity.SearchAdapter.SearchResult -> entry.link
            is MessagesController.FaqSearchResult -> entry.url
            else -> null
        }
        ItemOptions.makeOptions(fragment, view)
            .addIf(!link.isNullOrEmpty(), R.drawable.msg_link2, LocaleController.getString(R.string.CopyLink)) {
                AndroidUtilities.addToClipboard(link)
                BulletinFactory.of(fragment).createCopyLinkBulletin().show()
            }
            .add(R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true) {
                if (search.inu_removeRecent(entry)) listView.adapter.update(true)
            }
            .setScrimViewBackground(listView.getClipBackground(view))
            .show()
    }
}
