package desu.inugram.helpers

import desu.inugram.InuConfig
import org.telegram.tgnet.TLRPC

object FaveStickersHelper {
    @JvmStatic
    fun mergeWithLocal(server: ArrayList<TLRPC.Document>?, local: List<TLRPC.Document>): ArrayList<TLRPC.Document>? {
        if (server == null || !InuConfig.UNLIMITED_FAVORITE_STICKERS.value) return server
        val merged = ArrayList(server)
        val ids = HashSet<Long>()
        for (doc in server) ids.add(doc.id)
        for (doc in local) if (ids.add(doc.id)) merged.add(doc)
        return merged
    }
}
