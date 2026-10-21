package desu.inugram.helpers.plugins.io

import desu.inugram.core.plugins.PluginInstalls
import java.io.File

/** rust `sqlite.rs` owns the connection and quota */
object PluginSqlite {
    private const val ROOT = "inu_sqlite"

    /** sqlite's own, beside the database: the rollback journal and the WAL with its shared-memory index */
    private val SIDE_FILES = listOf("-journal", "-wal", "-shm")

    private fun file(installId: String): File = PluginPaths.scopedFile(installId, ROOT)

    fun pathFor(installId: String): String = file(installId).apply { parentFile!!.mkdirs() }.absolutePath

    /** after the engine is closed */
    fun wipe(installId: String) {
        if (!PluginInstalls.isValidId(installId)) return
        val db = file(installId)
        db.delete()
        for (suffix in SIDE_FILES) File("${db.path}$suffix").delete()
    }

    fun sweepOrphans(live: Set<String>) = PluginPaths.sweepOrphans(ROOT, live) { name ->
        SIDE_FILES.fold(name) { stripped, suffix -> stripped.removeSuffix(suffix) }
    }
}
