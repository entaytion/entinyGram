package desu.inugram.helpers.chat

import android.util.SparseArray
import org.telegram.messenger.MessageObject

object ForwardSlowmodeHelper {
    // entiny: an album goes out as one send, so slow mode should count it once
    @JvmStatic
    fun sendCount(selected: Array<SparseArray<MessageObject>>): Int {
        val groups = HashSet<Long>()
        var singles = 0
        for (side in selected) {
            for (i in 0 until side.size()) {
                val groupId = side.valueAt(i).groupId
                if (groupId == 0L) singles++ else groups.add(groupId)
            }
        }
        return singles + groups.size
    }
}
