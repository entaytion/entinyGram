package desu.inugram.helpers.dialogs

import desu.inugram.InuConfig
import org.telegram.ui.Cells.DialogCell

object DialogCellIosHelper {

    @JvmStatic
    fun isEnabled(): Boolean = InuConfig.IOS_CHAT_LIST.value

    // entiny: returns true when the cell paddings changed and the layout must be rebuilt
    @JvmStatic
    fun apply(cell: DialogCell, baseStart: Int): Boolean {
        val padding = baseStart + 61
        if (cell.avatarStart == baseStart && cell.messagePaddingStart == padding) return false
        cell.avatarStart = baseStart
        cell.messagePaddingStart = padding
        return true
    }
}
