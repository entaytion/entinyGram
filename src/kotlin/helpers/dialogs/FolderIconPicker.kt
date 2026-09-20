package desu.inugram.helpers.dialogs

import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme

// entiny: grid picker of the custom filter_* tab icons; selection is stored as the mapped emoji in filter.inu_emoticon
object FolderIconPicker {

    private const val COLUMNS = 5

    @JvmStatic
    fun pick(fragment: BaseFragment, filter: MessagesController.DialogFilter, onPicked: Runnable?) {
        val context = fragment.parentActivity ?: return
        val choices = FolderHelper.getIconChoices()
        val selectedEmoticon = filter.inu_emoticon?.replace("\uFE0F", "")
        val cellSize = AndroidUtilities.dp(58f)
        val iconSize = AndroidUtilities.dp(52f)

        lateinit var dialog: AlertDialog
        val grid = LinearLayout(context)
        grid.orientation = LinearLayout.VERTICAL
        val padding = AndroidUtilities.dp(14f)
        grid.setPadding(padding, padding / 2, padding, 0)

        var row: LinearLayout? = null
        choices.forEachIndexed { index, choice ->
            val emoticon = choice.first
            val iconRes = choice.second
            if (index % COLUMNS == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row)
            }
            val cell = FrameLayout(context)
            val image = ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(Theme.getColor(Theme.key_dialogTextBlack), PorterDuff.Mode.SRC_IN)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            cell.addView(image, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
            val params = LinearLayout.LayoutParams(0, cellSize, 1f)
            val margin = AndroidUtilities.dp(5f)
            params.setMargins(margin, margin, margin, margin)
            cell.layoutParams = params
            if (emoticon == selectedEmoticon) {
                cell.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(AndroidUtilities.dp(2f), Theme.getColor(Theme.key_dialogRadioBackgroundChecked))
                }
            }
            cell.setOnClickListener {
                filter.inu_emoticon = emoticon
                dialog.dismiss()
                onPicked?.run()
            }
            row?.addView(cell)
        }

        dialog = AlertDialog.Builder(context)
            .setTitle(LocaleController.getString(R.string.InuFolderIcon))
            .setView(grid)
            .setNeutralButton(LocaleController.getString(R.string.InuFolderIconDefault)) { _, _ ->
                filter.inu_emoticon = null
                onPicked?.run()
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show()
    }
}
