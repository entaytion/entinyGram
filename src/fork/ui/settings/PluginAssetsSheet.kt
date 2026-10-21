package desu.inugram.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.net.Uri
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import desu.inugram.core.plugins.PluginAsset
import desu.inugram.core.plugins.PluginAssetsParser
import java.io.File
import java.io.IOException
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BottomSheetWithRecyclerListView
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView

class PluginAssetsSheet(context: Context, private val source: String, private val assets: List<PluginAsset>) :
    BottomSheetWithRecyclerListView(context, null, false, false, false, null) {

    private val thumbnails = HashMap<PluginAsset, Bitmap?>()
    private lateinit var adapter: Adapter

    init {
        fixNavigationBar()
        recyclerListView.setOnItemClickListener { view, _ -> (view as? AssetRow)?.asset?.let(::openAsset) }
    }

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuPluginAssets)

    override fun createAdapter(listView: RecyclerListView): RecyclerListView.SelectionAdapter = Adapter().also { adapter = it }

    private fun buildHeader(context: Context): View {
        val title = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            typeface = AndroidUtilities.bold()
            text = getTitle()
        }
        val meta = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            text = describeAssets(assets)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), dp(18f), dp(22f), dp(10f))
            addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))
        }
    }

    private inner class AssetRow(context: Context) : LinearLayout(context) {
        var asset: PluginAsset? = null
        val preview = ImageView(context).apply {
            background = Theme.createRoundRectDrawable(dp(8f), Theme.multAlpha(Theme.getColor(Theme.key_dialogTextGray3), 0.12f))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(8f).toFloat())
            }
            clipToOutline = true
        }
        val name = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val details = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            setSingleLine()
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22f), dp(8f), dp(22f), dp(8f))
            addView(preview, LayoutHelper.createLinear(48, 48))
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(name)
                addView(details)
            }, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 14, 0, 0, 0))
        }

        fun bind(asset: PluginAsset) {
            this.asset = asset
            name.text = asset.name
            details.text = "${asset.mime} · ${AndroidUtilities.formatFileSize(asset.size)}"
            val thumbnail = thumbnails[asset]
            if (thumbnail != null) {
                preview.scaleType = ImageView.ScaleType.CENTER_CROP
                preview.colorFilter = null
                preview.setImageBitmap(thumbnail)
                return
            }
            preview.scaleType = ImageView.ScaleType.CENTER
            preview.colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_dialogTextGray3), PorterDuff.Mode.SRC_IN)
            preview.setImageResource(getAssetIcon(asset.mime))
            if (asset.mime.startsWith("image/") && asset !in thumbnails) loadThumbnail(asset)
        }
    }

    private fun loadThumbnail(asset: PluginAsset) {
        thumbnails[asset] = null
        Utilities.globalQueue.postRunnable {
            val bitmap = runCatching { decodeThumbnail(PluginAssetsParser.decode(source, asset), dp(48f)) }.getOrNull()
                ?: return@postRunnable
            AndroidUtilities.runOnUIThread {
                thumbnails[asset] = bitmap
                adapter.notifyItemChanged(1 + assets.indexOf(asset))
            }
        }
    }

    private fun openAsset(asset: PluginAsset) {
        val activity = AndroidUtilities.findActivity(context) ?: return
        Utilities.globalQueue.postRunnable {
            val file = runCatching {
                val dir = File(ApplicationLoader.applicationContext.filesDir, OPEN_DIR)
                dir.deleteRecursively()
                if (!dir.mkdirs()) throw IOException("could not create $dir")
                File(dir, getFileName(asset)).apply { writeBytes(PluginAssetsParser.decode(source, asset)) }
            }
            AndroidUtilities.runOnUIThread {
                val opened = file.mapCatching {
                    AndroidUtilities.openForView(it, it.name, asset.mime, activity, resourcesProvider, true)
                }
                if (opened.getOrDefault(false)) return@runOnUIThread
                BulletinFactory.of(container, resourcesProvider)
                    .createErrorBulletin(LocaleController.getString(R.string.InuFontOpenInFailed))
                    .show()
            }
        }
    }

    private inner class Adapter : RecyclerListView.SelectionAdapter() {
        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = holder.itemView is AssetRow

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = if (viewType == 0) buildHeader(parent.context) else AssetRow(parent.context)
            view.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            return RecyclerListView.Holder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (position > 0) (holder.itemView as AssetRow).bind(assets[position - 1])
        }

        override fun getItemViewType(position: Int): Int = if (position == 0) 0 else 1

        override fun getItemCount(): Int = 1 + assets.size
    }

    companion object {
        /** under `files/cache`, the one app-private directory stock's FileProvider shares */
        private const val OPEN_DIR = "cache/inu_plugin_asset"

        /** asset names come from the plugin: keep only a plain last path segment */
        private fun getFileName(asset: PluginAsset): String {
            val name = Uri.decode(asset.name).substringAfterLast('/')
            return name.takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "asset"
        }

        fun describeAssets(assets: List<PluginAsset>): String =
            "${LocaleController.formatPluralString("InuPluginAssetsCount", assets.size)} · " +
                AndroidUtilities.formatFileSize(assets.sumOf { it.size })

        private fun getAssetIcon(mime: String): Int = when {
            mime.startsWith("image/") -> R.drawable.msg_photos
            mime.startsWith("video/") -> R.drawable.msg_video
            mime.startsWith("audio/") -> R.drawable.msg_voice_headphones
            mime.startsWith("font/") -> R.drawable.msg_text_outlined
            mime == "application/x-tgsticker" -> R.drawable.msg_sticker
            else -> R.drawable.msg_filehq
        }

        private fun decodeThumbnail(bytes: ByteArray, size: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
