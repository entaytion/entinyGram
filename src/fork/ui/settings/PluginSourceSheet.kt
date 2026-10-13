package desu.inugram.ui.settings

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.CodeHighlighting
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BottomSheetWithRecyclerListView
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RecyclerListView

class PluginSourceSheet(context: Context, private val name: String, private val source: String) :
    BottomSheetWithRecyclerListView(context, null, false, false, false, null) {

    init {
        fixNavigationBar()
    }

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuPluginSourceTitle)

    override fun createAdapter(listView: RecyclerListView): RecyclerListView.SelectionAdapter = Adapter()

    private fun buildHeader(context: Context): View {
        val title = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            typeface = AndroidUtilities.bold()
            text = LocaleController.getString(R.string.InuPluginSourceTitle)
        }
        val meta = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            text = listOf(
                name,
                AndroidUtilities.formatFileSize(source.toByteArray().size.toLong()),
            ).joinToString(" · ")
        }
        val titleBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))
        }
        val copyButton = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.msg_copy)
            setColorFilter(PorterDuffColorFilter(Theme.getColor(Theme.key_dialogTextGray3), PorterDuff.Mode.SRC_IN))
            background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP)
            contentDescription = LocaleController.getString(R.string.Copy)
            setOnClickListener {
                AndroidUtilities.addToClipboard(source)
                BulletinFactory.of(container, resourcesProvider)
                    .createCopyBulletin(LocaleController.getString(R.string.TextCopied))
                    .show()
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(AndroidUtilities.dp(22f), AndroidUtilities.dp(18f), AndroidUtilities.dp(16f), AndroidUtilities.dp(10f))
            addView(titleBlock, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))
            addView(copyButton, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 12, 0, 0, 0))
        }
    }

    private val chunks: List<IntRange> = run {
        val result = ArrayList<IntRange>()
        var chunkStart = 0
        var lines = 0
        var i = source.indexOf('\n')
        while (i != -1) {
            if (++lines == CHUNK_LINES) {
                result.add(chunkStart until i)
                chunkStart = i + 1
                lines = 0
            }
            i = source.indexOf('\n', i + 1)
        }
        if (chunkStart < source.length || result.isEmpty()) result.add(chunkStart until source.length)
        result
    }
    private var highlightedChunks: List<CharSequence>? = null
    private var codeWidth = 0
    private var codeScrollX = 0

    init {
        if (source.length <= HIGHLIGHT_MAX_LENGTH) {
            Utilities.searchQueue.postRunnable {
                val spans = CodeHighlighting.inu_computeSpans(source, "javascript")
                var first = 0
                val result = chunks.map { range ->
                    val end = range.last + 1
                    val text = SpannableString(source.substring(range.first, end))
                    while (first < spans.size && spans[first].end <= range.first) first++
                    var i = first
                    while (i < spans.size && spans[i].start < end) {
                        val span = spans[i++]
                        val spanStart = maxOf(span.start, range.first) - range.first
                        val spanEnd = minOf(span.end, end) - range.first
                        if (spanStart < spanEnd) {
                            text.setSpan(CodeHighlighting.ColorSpan(span.group), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    text
                }
                AndroidUtilities.runOnUIThread {
                    highlightedChunks = result
                    recyclerListView.adapter?.notifyItemRangeChanged(1, chunks.size)
                }
            }
        }
    }

    private fun buildCode(context: Context): View {
        val code = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextIsSelectable(true)
            setHorizontallyScrolling(true)
        }
        if (codeWidth == 0) {
            codeWidth = source.lineSequence().maxOf { Layout.getDesiredWidth(it, code.paint) }.toInt() +
                AndroidUtilities.dp(44f)
        }
        code.minWidth = codeWidth
        return object : HorizontalScrollView(context) {
            override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
                super.onScrollChanged(l, t, oldl, oldt)
                if (codeScrollX == l) return
                codeScrollX = l
                for (i in 0 until recyclerListView.childCount) {
                    val child = recyclerListView.getChildAt(i)
                    if (child is HorizontalScrollView && child !== this) child.scrollTo(l, 0)
                }
            }
        }.apply {
            isHorizontalScrollBarEnabled = false
            addView(code, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat()))
        }
    }

    private fun bindCode(view: HorizontalScrollView, index: Int) {
        val range = chunks[index]
        (view.getChildAt(0) as TextView).apply {
            setPadding(
                AndroidUtilities.dp(22f),
                if (index == 0) AndroidUtilities.dp(4f) else 0,
                AndroidUtilities.dp(22f),
                if (index == chunks.lastIndex) AndroidUtilities.dp(16f) else 0,
            )
            text = highlightedChunks?.get(index) ?: source.substring(range.first, range.last + 1)
        }
        view.scrollX = codeScrollX
    }

    private inner class Adapter : RecyclerListView.SelectionAdapter() {
        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = false

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = if (viewType == 0) buildHeader(parent.context) else buildCode(parent.context)
            view.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            return RecyclerListView.Holder(view)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (position > 0) bindCode(holder.itemView as HorizontalScrollView, position - 1)
        }

        override fun getItemViewType(position: Int): Int = if (position == 0) 0 else 1

        override fun getItemCount(): Int = 1 + chunks.size
    }

    companion object {
        private const val HIGHLIGHT_MAX_LENGTH = 200_000
        private const val CHUNK_LINES = 40
    }
}
