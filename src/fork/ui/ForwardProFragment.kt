package desu.inugram.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.URLSpan
import android.util.Patterns
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.tgnet.SerializedData
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.ChatMessageCell
import org.telegram.ui.Components.EditTextCaption
import org.telegram.ui.Components.LayoutHelper
import kotlin.math.ceil
import kotlin.math.max

// entiny: full-screen forward editor with a stock bubble preview, inspired by Telegraph's special forward
class ForwardProFragment(
    private val items: List<Item>,
    private val onResult: (Map<Int, CharSequence>?) -> Unit,
) : BaseFragment() {

    class Item(
        val carrier: MessageObject,
        val editable: Boolean,
        val original: CharSequence,
        val initial: CharSequence,
        val isMedia: Boolean,
    )

    private val multi = items.size > 1
    private val texts = items.map { it.initial }.toMutableList()
    private var selected = items.indexOfFirst { it.editable }.coerceAtLeast(0)
    private var finished = false
    private var expanded = !multi
    private var editor: EditTextCaption? = null
    private var previewHolder: FrameLayout? = null
    private var panel: View? = null
    private var strip: View? = null
    private var trailing: ImageView? = null
    private var label: TextView? = null

    override fun createView(context: Context): View {
        actionBar.setAddToContainer(false)
        hasOwnBackground = true

        val root = object : FrameLayout(context) {
            override fun dispatchDraw(canvas: Canvas) {
                drawWallpaper(canvas, measuredWidth, measuredHeight)
                super.dispatchDraw(canvas)
            }
        }
        root.setWillNotDraw(false)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, AndroidUtilities.statusBarHeight, 0, AndroidUtilities.navigationBarHeight)
        }
        column.addView(createTopBar(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56))

        val holder = FrameLayout(context)
        previewHolder = holder
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(holder, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        column.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f))
        showPreview()

        val panelView = createPanel(context)
        panel = panelView
        panelView.visibility = if (expanded) View.VISIBLE else View.GONE
        column.addView(panelView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8f, 4f, 8f, 8f))

        val stripView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f)
            setTextColor(Theme.getColor(Theme.key_chat_messagePanelHint, resourceProvider))
            gravity = Gravity.CENTER
            background = Theme.createRoundRectDrawable(dp(28f), Theme.getColor(Theme.key_chat_messagePanelBackground, resourceProvider))
            text = LocaleController.getString(R.string.InuForwardProTapToEdit)
            visibility = if (expanded) View.GONE else View.VISIBLE
            setOnClickListener { select(selected) }
        }
        strip = stripView
        column.addView(stripView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56, 8f, 4f, 8f, 8f))

        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
        fragmentView = root
        return root
    }

    override fun onResume() {
        super.onResume()
        AndroidUtilities.requestAdjustResize(parentActivity, classGuid)
    }

    override fun onPause() {
        super.onPause()
        AndroidUtilities.removeAdjustResize(parentActivity, classGuid)
    }

    override fun onBackPressed(invoked: Boolean): Boolean {
        if (!isDirty()) return true
        if (invoked) confirmDiscard()
        return false
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        if (!finished) {
            finished = true
            onResult(null)
        }
    }

    private fun isDirty() = items.indices.any { texts[it].toString() != items[it].initial.toString() }

    private fun confirmDiscard() {
        val ctx = parentActivity ?: return
        AlertDialog.Builder(ctx, resourceProvider)
            .setTitle(LocaleController.getString(R.string.DiscardChanges))
            .setMessage(LocaleController.getString(R.string.AreYouSure))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.Discard)) { _, _ -> finishFragment() }
            .show()
    }

    private fun confirmReset() {
        val ctx = parentActivity ?: return
        AlertDialog.Builder(ctx, resourceProvider)
            .setTitle(LocaleController.getString(R.string.Reset))
            .setMessage(LocaleController.getString(R.string.AreYouSure))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.Reset)) { _, _ ->
                editor?.setText(SpannableStringBuilder(items[selected].original))
                editor?.setSelection(editor?.text?.length ?: 0)
                showPreview()
            }
            .show()
    }

    private fun hasEmptyText(): Boolean {
        val bad = items.indices.any { items[it].editable && !items[it].isMedia && texts[it].trim().isEmpty() }
        if (bad) Toast.makeText(context, LocaleController.getString(R.string.InuForwardProEmptyNotice), Toast.LENGTH_SHORT).show()
        return bad
    }

    private fun done() {
        if (hasEmptyText()) return
        finished = true
        val result = LinkedHashMap<Int, CharSequence>()
        for (i in items.indices) {
            if (items[i].editable && texts[i].toString() != items[i].original.toString()) {
                result[items[i].carrier.id] = SpannableStringBuilder(texts[i])
            }
        }
        finishFragment()
        onResult(result)
    }

    private fun apply() {
        if (hasEmptyText()) return
        showPreview()
    }

    private fun select(index: Int) {
        val item = items[index]
        if (!item.editable) return
        selected = index
        label?.text = LocaleController.getString(if (item.isMedia) R.string.Caption else R.string.EditMessage)
        editor?.hint = LocaleController.getString(if (item.isMedia) R.string.InuForwardProPlaceholder else R.string.Message)
        editor?.setText(SpannableStringBuilder(texts[index]))
        editor?.setSelection(editor?.text?.length ?: 0)
        setExpanded(true)
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        panel?.visibility = if (value) View.VISIBLE else View.GONE
        strip?.visibility = if (value) View.GONE else View.VISIBLE
        trailing?.setImageResource(if (value) R.drawable.inu_tabler_eye else R.drawable.msg_send)
        if (value) {
            editor?.requestFocus()
            editor?.let { AndroidUtilities.showKeyboard(it) }
        } else {
            editor?.let { AndroidUtilities.hideKeyboard(it) }
        }
    }

    private fun circle(context: Context, icon: Int, size: Int = 56): ImageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER
        setImageResource(icon)
        colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_chat_messagePanelIcons, resourceProvider), PorterDuff.Mode.SRC_IN)
        background = Theme.createRoundRectDrawable(dp(size / 2f), Theme.getColor(Theme.key_chat_messagePanelBackground, resourceProvider))
    }

    private fun createTopBar(context: Context): View {
        val bar = FrameLayout(context)
        val back = circle(context, R.drawable.ic_ab_back).apply {
            setOnClickListener { if (onBackPressed(true)) finishFragment() }
        }
        bar.addView(back, LayoutHelper.createFrame(56, 56f, Gravity.LEFT or Gravity.CENTER_VERTICAL, 8f, 0f, 0f, 0f))

        val title = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), 0, dp(16f), 0)
            background = Theme.createRoundRectDrawable(dp(28f), Theme.getColor(Theme.key_chat_messagePanelBackground, resourceProvider))
        }
        title.addView(TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
            typeface = AndroidUtilities.bold()
            setTextColor(Theme.getColor(Theme.key_chat_messagePanelText, resourceProvider))
            maxLines = 1
            text = LocaleController.getString(R.string.InuForwardPro)
        })
        if (multi) {
            title.addView(TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                setTextColor(Theme.getColor(Theme.key_chat_messagePanelHint, resourceProvider))
                maxLines = 1
                text = LocaleController.formatPluralString("messages", items.size)
            })
        }
        bar.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 56f, Gravity.LEFT or Gravity.CENTER_VERTICAL, 68f, 0f, 0f, 0f))

        val action = circle(context, if (expanded) R.drawable.inu_tabler_eye else R.drawable.msg_send).apply {
            setOnClickListener { if (expanded) apply() else done() }
        }
        trailing = action
        bar.addView(action, LayoutHelper.createFrame(56, 56f, Gravity.RIGHT or Gravity.CENTER_VERTICAL, 0f, 0f, 8f, 0f))
        return bar
    }

    private fun createPanel(context: Context): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.createRoundRectDrawable(dp(24f), Theme.getColor(Theme.key_chat_messagePanelBackground, resourceProvider))
        }
        val accent = Theme.getColor(Theme.key_chat_messagePanelSend, resourceProvider)
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.msg_edit)
            colorFilter = PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN)
        }, LayoutHelper.createLinear(44, 44, 4f, 0f, 0f, 0f))
        header.addView(TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            typeface = AndroidUtilities.bold()
            setTextColor(accent)
            text = LocaleController.getString(if (items[selected].isMedia) R.string.Caption else R.string.EditMessage)
            label = this
        }, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 4, 0, 0, 0))

        fun tool(icon: Int, click: () -> Unit) = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(icon)
            colorFilter = PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN)
            setOnClickListener { click() }
        }
        header.addView(tool(R.drawable.msg_retry) { confirmReset() }, LayoutHelper.createLinear(44, 44))
        header.addView(tool(R.drawable.msg_delete) {
            editor?.setText(withoutLinks(texts[selected]))
            editor?.setSelection(editor?.text?.length ?: 0)
        }, LayoutHelper.createLinear(44, 44))
        header.addView(tool(R.drawable.msg_close) { setExpanded(false) }, LayoutHelper.createLinear(44, 44, 0f, 0f, 4f, 0f))
        box.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46))

        val edit = EditTextCaption(context, resourceProvider).apply {
            background = null
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            setTextColor(Theme.getColor(Theme.key_chat_messagePanelText, resourceProvider))
            setHintTextColor(Theme.getColor(Theme.key_chat_messagePanelHint, resourceProvider))
            hint = LocaleController.getString(if (items[selected].isMedia) R.string.InuForwardProPlaceholder else R.string.Message)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 1
            maxLines = 5
            gravity = Gravity.LEFT or Gravity.TOP
            setCursorColor(Theme.getColor(Theme.key_chat_messagePanelCursor, resourceProvider))
            setCursorSize(dp(20f))
            setCursorWidth(1.5f)
            setPadding(dp(16f), dp(8f), dp(8f), dp(12f))
            setText(texts[selected])
            setSelection(text?.length ?: 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    texts[selected] = SpannableStringBuilder(s ?: "")
                }
            })
        }
        editor = edit

        val send = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.msg_send)
            colorFilter = PorterDuffColorFilter(0xffffffff.toInt(), PorterDuff.Mode.SRC_IN)
            background = Theme.createCircleDrawable(dp(38f), accent)
            setOnClickListener { done() }
        }
        val inputRow = LinearLayout(context).apply { gravity = Gravity.BOTTOM }
        inputRow.addView(edit, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
        inputRow.addView(send, LayoutHelper.createLinear(38, 38, Gravity.BOTTOM, 0f, 0f, 8f, 8f))
        box.addView(inputRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        return box
    }

    // entiny: a tap on the bubble or the empty space beside it opens that message for editing
    private inner class TapRow(context: Context, private val index: Int) : FrameLayout(context) {
        private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (multi || !expanded) select(index)
                return true
            }
        })

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            detector.onTouchEvent(ev)
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            detector.onTouchEvent(event)
            return true
        }
    }

    private fun showPreview() {
        val ctx = context ?: return
        val holder = previewHolder ?: return
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (i in items.indices) {
            val item = items[i]
            val message = if (item.editable) copyWithText(item.carrier, texts[i]) else item.carrier
            val cell = ChatMessageCell(ctx, currentAccount, false, null, resourceProvider).apply {
                isChat = false
                setFullyDraw(true)
                delegate = object : ChatMessageCell.ChatMessageCellDelegate {
                    override fun canPerformActions() = true
                }
                setMessageObject(message, null, false, false, false)
            }
            val row = TapRow(ctx, i)
            row.addView(cell, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            column.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        holder.setOnClickListener { if (!expanded) select(selected) }
        holder.removeAllViews()
        holder.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    // entiny: the original message stays untouched, the preview renders a serialized copy with the edited text
    private fun copyWithText(source: MessageObject, text: CharSequence): MessageObject {
        val owner = source.messageOwner
        val data = SerializedData()
        owner.serializeToStream(data)
        val read = SerializedData(data.toByteArray())
        val copy = TLRPC.Message.TLdeserialize(read, read.readInt32(false), false)
        val holder = arrayOf<CharSequence>(SpannableStringBuilder(text))
        val entities = MediaDataController.getInstance(currentAccount).getEntities(holder, true)
        copy.message = holder[0].toString()
        copy.entities = entities ?: ArrayList()
        return MessageObject(currentAccount, copy, true, false)
    }

    private fun withoutLinks(text: CharSequence): CharSequence {
        val result = SpannableStringBuilder(text)
        for (span in result.getSpans(0, result.length, URLSpan::class.java)) result.removeSpan(span)
        val matcher = Patterns.WEB_URL.matcher(result)
        val ranges = ArrayList<IntRange>()
        while (matcher.find()) ranges.add(matcher.start() until matcher.end())
        for (range in ranges.asReversed()) result.delete(range.first, range.last + 1)
        return result
    }

    private fun drawWallpaper(canvas: Canvas, width: Int, height: Int) {
        val drawable = Theme.getCachedWallpaperNonBlocking() ?: return
        drawable.alpha = 255
        if (drawable is BitmapDrawable) {
            canvas.save()
            if (drawable.tileModeX == Shader.TileMode.REPEAT) {
                val scale = 2.0f / AndroidUtilities.density
                canvas.scale(scale, scale)
                drawable.setBounds(0, 0, ceil(width / scale).toInt(), ceil(height / scale).toInt())
            } else {
                val scale = max(width.toFloat() / drawable.intrinsicWidth, height.toFloat() / drawable.intrinsicHeight)
                val w = ceil(drawable.intrinsicWidth * scale).toInt()
                val h = ceil(drawable.intrinsicHeight * scale).toInt()
                val x = (width - w) / 2
                val y = (height - h) / 2
                canvas.clipRect(0, 0, width, height)
                drawable.setBounds(x, y, x + w, y + h)
            }
            drawable.draw(canvas)
            canvas.restore()
        } else {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
        }
    }
}
