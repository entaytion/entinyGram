package desu.inugram.helpers.dialogs

import desu.inugram.InuConfig
import org.telegram.ui.Cells.DialogCell

object DialogCellIosHelper {

    @JvmStatic
    fun isEnabled(): Boolean = InuConfig.IOS_CHAT_LIST.value

    // entiny: returns true when the cell paddings changed and the layout must be rebuilt
    @JvmStatic
    fun apply(cell: DialogCell, baseStart: Int): Boolean {
        val ios = cell.inu_isIos()
        val start = if (ios) baseStart + 2 else baseStart
        val padding = start + if (ios) 65 else 61
        if (cell.avatarStart == start && cell.messagePaddingStart == padding) return false
        cell.avatarStart = start
        cell.messagePaddingStart = padding
        return true
    }
}
