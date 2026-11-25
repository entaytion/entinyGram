package desu.inugram.ui.settings

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import desu.inugram.helpers.update.ReleaseNotes
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class WhatsNewActivity : SettingsPageActivity() {
    private var loaded = false
    private var failed = false
    private var releases: List<Pair<String, View>> = emptyList()

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuWhatsNew)

    override fun createView(context: Context): View {
        loaded = false
        failed = false
        releases = emptyList()
        ReleaseNotes.load { list ->
            loaded = true
            failed = list.isNullOrEmpty()
            releases = list.orEmpty().map { it.date to buildBody(context, it.lines) }
            listView?.adapter?.update(true)
        }
        return super.createView(context)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        if (!loaded) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.Loading)))
            return
        }
        if (failed) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.ErrorOccurred)))
            return
        }
        for ((date, view) in releases) {
            items.add(UItem.asHeader(date))
            items.add(UItem.asCustom(view))
        }
        items.add(UItem.asShadow(null))
    }

    private fun buildBody(context: Context, lines: List<String>): View =
        TextView(context).apply {
            text = lines.joinToString("\n") { "• $it" }
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
            setLineSpacing(AndroidUtilities.dp(3f).toFloat(), 1f)
            setPadding(
                AndroidUtilities.dp(23f),
                AndroidUtilities.dp(4f),
                AndroidUtilities.dp(23f),
                AndroidUtilities.dp(12f),
            )
        }
}
