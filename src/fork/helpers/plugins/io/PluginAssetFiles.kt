package desu.inugram.helpers.plugins.io

import desu.inugram.core.plugins.PluginAsset
import desu.inugram.core.plugins.PluginAssetsParser
import java.io.File
import java.io.IOException
import java.util.zip.CRC32

/** Decoded asset footers, keyed by install id. Files are named by index: asset names never become paths */
object PluginAssetFiles {
    private const val ROOT = "inu_plugin_assets"
    private const val STAMP = "stamp"

    private fun dir(installId: String): File = PluginPaths.scopedFile(installId, ROOT)

    /** plugin queue only. Decodes again only when the footer changed; the stamp is written last, so a torn write redoes it */
    fun extract(installId: String, source: String, assets: List<PluginAsset>): List<Pair<PluginAsset, String>> {
        val dir = dir(installId)
        if (assets.isEmpty()) {
            dir.deleteRecursively()
            return emptyList()
        }
        val footer = source.substring(PluginAssetsParser.findFooter(source))
        val stamp = "${footer.length}:" + CRC32().apply { update(footer.toByteArray()) }.value
        val stampFile = File(dir, STAMP)
        val files = assets.indices.map { File(dir, it.toString()) }
        val current = runCatching { stampFile.readText() }.getOrNull() == stamp && files.all { it.isFile }
        if (!current) {
            dir.deleteRecursively()
            if (!dir.mkdirs()) throw IOException("could not create $dir")
            assets.forEachIndexed { index, asset -> files[index].writeBytes(PluginAssetsParser.decode(source, asset)) }
            stampFile.writeText(stamp)
        }
        return assets.mapIndexed { index, asset -> asset to files[index].absolutePath }
    }

    fun wipe(installId: String) = PluginPaths.wipe(installId, ::dir)

    fun sweepOrphans(live: Set<String>) = PluginPaths.sweepOrphans(ROOT, live) { it }
}
