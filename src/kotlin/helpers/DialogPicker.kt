package desu.inugram.helpers

import android.os.Bundle
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.DialogsActivity

object DialogPicker {
    @JvmStatic
    fun pick(fragment: BaseFragment, onPicked: (Long) -> Unit) {
        val args = Bundle().apply {
            putBoolean("onlySelect", true)
            putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD)
            putBoolean("canSelectTopics", true)
        }
        val picker = DialogsActivity(args)
        picker.setDelegate { _, dids, _, _, _, _, _, _ ->
            dids.firstOrNull()?.let { onPicked(it.dialogId) }
            true
        }
        fragment.presentFragment(picker)
    }
}
