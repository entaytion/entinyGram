package desu.inugram.ui.settings.fonts

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.font.FontConfig
import desu.inugram.helpers.font.FontConfig.BUNDLED_STACK_ID
import desu.inugram.helpers.font.FontConfig.FontMode
import desu.inugram.helpers.font.FontConfig.SYSTEM_STACK_ID
import desu.inugram.helpers.font.FontHelper
import desu.inugram.helpers.font.FontId
import desu.inugram.helpers.font.FontLibrary
import desu.inugram.helpers.font.SfntParser.Script
import desu.inugram.helpers.font.StackCoverage
import desu.inugram.ui.settings.SettingsPageActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.TypefaceSpan
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Stories.recorder.ButtonWithCounterView
import java.util.Locale

class FontStackActivity : SettingsPageActivity() {
    private val draft = ArrayList<String>()
    private var draftMono: String = ""
    private val stackRows = HashMap<String, StackFontRow>()
    private var reorderSectionId = -1
    private var warnings: List<String> = emptyList()
    private var warningsGen = 0

    private var preview: FontStackPreviewCell? = null
    private var warningsView: StackWarningsView? = null

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAppFont)


    @SuppressLint("ClickableViewAccessibility")
    override fun createView(context: Context): View {
        loadDraft()
        preview = FontStackPreviewCell(context, this)
        val view = super.createView(context)
        attachStickyButton(view, run {
            val btn = ButtonWithCounterView(context, true, resourceProvider).setRound()
            btn.setText(LocaleController.getString(R.string.InuFontStackApply), false)
            btn.setOnClickListener { apply() }
            return@run btn
        })

        listView.setReorderLongPressEnabled(false)
        listView.listenReorder { id, items ->
            if (id != reorderSectionId) return@listenReorder
            val newOrder = items.mapNotNull { it.`object` as? String }
            if (newOrder.size == draft.size) {
                draft.clear()
                draft.addAll(newOrder)
                onDraftChanged()
            }
        }
        listView.allowReorder(true)
        onDraftChanged()
        return view
    }

    private fun loadDraft() {
        draftMono = FontConfig.MONO_FONT.value
        draft.clear()
        when (val m = FontConfig.FONT.value) {
            FontMode.Bundled -> draft.add(BUNDLED_STACK_ID)
            FontMode.System -> draft.add(SYSTEM_STACK_ID)
            is FontMode.Custom -> {
                val id = FontHelper.maybeResolveLegacyEmpty(m.fontId)
                if (id == null) {
                    draft.add(BUNDLED_STACK_ID)
                } else {
                    draft.add(id.token())
                }
                draft.addAll(FontHelper.getActiveFallbackIds().map { it.token() })
            }
        }
    }

    private val reorderable get() = draft.size >= 2 && draft.none { isSentinel(it) }

    @SuppressLint("ClickableViewAccessibility")
    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        preview?.let { items.add(UItem.asCustom(BUTTON_PREVIEW, it)) }

        items.add(UItem.asButton(BUTTON_MONO, LocaleController.getString(R.string.InuMonoFont), monoValue()))
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuMonoFontInfo)))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuAppFontStack)))

        reorderSectionId = adapter.reorderSectionStart()
        for ((index, token) in draft.withIndex()) {
            val row = stackRows.getOrPut(token) {
                StackFontRow(context).also { r ->
                    r.setOnReorderTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            val holder = listView.findContainingViewHolder(r) ?: return@setOnReorderTouchListener false
                            listView.itemTouchHelper.startDrag(holder)
                        }
                        false
                    }
                }
            }
            row.bind(token, primary = index == 0, reorderable = reorderable)
            items.add(UItem.asCustom(row, row.heightDp).apply {
                id = token.hashCode()
                `object` = token
            })
        }
        adapter.reorderSectionEnd()
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuAppFontStackInfo)))

        val addLabel = if (draft.size == 1 && isSentinel(draft[0])) R.string.InuFontStackChoose else R.string.InuFontStackAdd
        items.add(UItem.asButton(BUTTON_ADD, R.drawable.msg_add, LocaleController.getString(addLabel)))

        items.add(UItem.asShadow(null))
        if (warnings.isNotEmpty()) {
            val wv = warningsView ?: StackWarningsView(context).also { warningsView = it }
            wv.setWarnings(warnings)
            items.add(UItem.asCustom(WARNINGS_ROW, wv))
            items.add(UItem.asShadow(null))
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_ADD -> showAddDialog()
            BUTTON_MONO -> showMonoDialog()
            else -> {
                val token = item.`object` as? String ?: return
                if (isSentinel(token)) return
                when {
                    stackRows[token]?.isInRemove(x) == true -> remove(token)
                    else -> showRowMenu(token, view)
                }
            }
        }
    }

    private fun showRowMenu(token: String, anchor: View) {
        val opts = ItemOptions.makeOptions(this, anchor)
        if (draft.indexOf(token) > 0) {
            opts.add(R.drawable.msg_text_outlined, LocaleController.getString(R.string.InuFontStackMakePrimary)) {
                draft.remove(token)
                draft.add(0, token)
                onDraftChanged()
                listView.adapter.update(true)
            }
        }
        opts.add(R.drawable.msg_delete, LocaleController.getString(R.string.InuFontRemove), true) {
            remove(token)
        }
        opts.show()
    }

    private fun remove(token: String) {
        draft.remove(token)
        stackRows.remove(token)
        if (draft.isEmpty()) draft.add(BUNDLED_STACK_ID)
        onDraftChanged()
        listView.adapter.update(true)
    }

    private fun pick(choice: String) {
        when {
            isSentinel(choice) || (draft.size == 1 && isSentinel(draft[0])) -> {
                draft.clear(); draft.add(choice)
            }

            choice !in draft -> draft.add(choice)
        }
        onDraftChanged()
        listView.adapter.update(true)
    }

    private fun onDraftChanged() {
        val primary = draftPrimary()
        val fallbacks = currentFallbacks()
        preview?.setStack(primary, fallbacks, draftMono)
        recomputeWarnings(primary, fallbacks)
    }

    private fun currentFallbacks(): List<String> {
        val primary = draftPrimary()
        return if (primary == null || primary == SYSTEM_STACK_ID) emptyList() else draft.drop(1)
    }

    private fun draftPrimary(): String? = when (val p = draft.firstOrNull()) {
        null, BUNDLED_STACK_ID -> null
        else -> p
    }

    private fun recomputeWarnings(primary: String?, fallbacks: List<String>) {
        val token = ++warningsGen
        if (primary == null || primary == SYSTEM_STACK_ID) {
            warnings = emptyList()
            return
        }
        val style = StackCoverage.style(primary, fallbacks)
        val needScripts = userScripts()
        Utilities.globalQueue.postRunnable {
            val coverage = StackCoverage.scripts(primary, fallbacks) ?: emptySet()
            val out = ArrayList<String>()
            // entiny: warn when font lacks regular face because normal text will render in bold or italic
            if (!style.regular) out.add(LocaleController.getString(R.string.InuFontStackWarnBoldOnly))
            if (!style.upright) out.add(LocaleController.getString(R.string.InuFontStackWarnItalicOnly))
            if (!style.bold) out.add(LocaleController.getString(R.string.InuFontStackWarnBold))
            if (!style.italic) out.add(LocaleController.getString(R.string.InuFontStackWarnItalic))
            for ((script, label) in needScripts) {
                if (script !in coverage) {
                    out.add(LocaleController.formatString(R.string.InuFontStackWarnScript, label))
                }
            }
            AndroidUtilities.runOnUIThread {
                if (token != warningsGen) return@runOnUIThread
                warnings = out
                listView.adapter.update(true)
            }
        }
    }

    private fun userScripts(): Map<Script, String> {
        val langs = LinkedHashSet<String>()
        LocaleController.getInstance().getCurrentLocale()?.language?.let { langs.add(it) }
        langs.add(Locale.getDefault().language)
        val cfg = Resources.getSystem().configuration.locales
        for (i in 0 until cfg.size()) langs.add(cfg[i].language)

        val out = LinkedHashMap<Script, String>()
        out[Script.LATIN] = LocaleController.getString(R.string.InuFontScriptLatin)
        for (lang in langs) {
            val script = scriptForLanguage(lang) ?: continue
            out.getOrPut(script) {
                runCatching { Locale.Builder().setLanguage(lang).build() }
                    .getOrDefault(Locale.ROOT)
                    .getDisplayLanguage(Locale.getDefault())
                    .replaceFirstChar { it.uppercase() }
            }
        }
        return out
    }

    private fun scriptForLanguage(lang: String): Script? = when (lang.lowercase().take(2)) {
        "ru", "uk", "be", "bg", "sr", "mk", "kk", "ky", "mn", "tt", "ba" -> Script.CYRILLIC
        "zh" -> Script.CJK
        "ja" -> Script.KANA
        "ko" -> Script.HANGUL
        "ar", "fa", "ur", "ps" -> Script.ARABIC
        "he", "iw", "yi" -> Script.HEBREW
        "th" -> Script.THAI
        "el" -> Script.GREEK
        else -> null
    }

    private fun showFontPickerDialog(
        titleRes: Int,
        onPick: (String) -> Unit,
        populate: (addRow: (CharSequence, Typeface?, String?, String) -> Unit) -> Unit,
    ) {
        val ctx = parentActivity ?: context ?: return
        val ll = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, AndroidUtilities.dp(4f), 0, AndroidUtilities.dp(4f))
        }
        val scroll = ScrollView(ctx).apply { addView(ll) }
        val dialog = AlertDialog.Builder(ctx, resourceProvider)
            .setTitle(LocaleController.getString(titleRes))
            .setView(scroll)
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .create()
        populate { label, tf, tagToken, choice ->
            ll.addView(pickerRow(ctx, label, tf, tagToken) {
                dialog.dismiss()
                onPick(choice)
            })
        }
        showDialog(dialog)
    }

    private fun showAddDialog() = showFontPickerDialog(R.string.InuFontStackAdd, ::pick) { addRow ->
        addRow(LocaleController.getString(R.string.InuFontDefault), Typeface.DEFAULT, null, BUNDLED_STACK_ID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            addRow(LocaleController.getString(R.string.InuFontSystem), Typeface.DEFAULT, null, SYSTEM_STACK_ID)
        }
        for (font in FontLibrary.getCachedRoster()) {
            if (font !is FontId.Family) continue
            val token = font.token()
            if (FontLibrary.isHidden(font) || token in draft) continue
            addRow(FontLibrary.getFontName(font), FontLibrary.getTypefaceFor(font), token, token)
        }
    }

    private fun showMonoDialog() = showFontPickerDialog(R.string.InuMonoFont, ::pickMono) { addRow ->
        addRow(LocaleController.getString(R.string.InuFontDefault), FontHelper.stockMonospace, null, "")
        for (font in FontLibrary.getCachedRoster()) {
            // entiny: exclude system fonts from mono picker because lazy discovery renders them as stock monospace
            if (font is FontId.System) continue
            if (FontLibrary.isHidden(font)) continue
            addRow(FontLibrary.getFontName(font), FontLibrary.getTypefaceFor(font), font.token(), font.token())
        }
    }

    private fun pickMono(token: String) {
        if (token == draftMono) return
        draftMono = token
        preview?.setStack(draftPrimary(), currentFallbacks(), draftMono)
        listView.adapter.update(true)
    }

    private fun monoValue(): CharSequence {
        if (draftMono.isEmpty()) return LocaleController.getString(R.string.InuFontDefault)
        val mono = FontId.parse(draftMono)
        val name = FontLibrary.getFontName(mono)
        val tf = FontLibrary.getTypefaceFor(mono) ?: return name
        return SpannableString(name).apply {
            setSpan(TypefaceSpan(tf), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun pickerRow(ctx: Context, label: CharSequence, tf: Typeface?, tagToken: String?, onClick: () -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = AndroidUtilities.dp(48f)
            setPadding(AndroidUtilities.dp(20f), AndroidUtilities.dp(6f), AndroidUtilities.dp(20f), AndroidUtilities.dp(6f))
            background = Theme.getSelectorDrawable(false)
            isClickable = true
            setOnClickListener { onClick() }
        }
        val name = TextView(ctx).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            textSize = 16f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            text = label
            typeface = tf ?: Typeface.DEFAULT
            includeFontPadding = false
        }
        row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (tagToken != null) {
            val tag = FontSourceTag.newView(ctx)
            FontSourceTag.bind(tag, FontId.parse(tagToken))
            row.addView(tag, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = AndroidUtilities.dp(10f)
            })
        }
        return row
    }

    private fun apply() {
        val primary = draftPrimary()
        val fallbacks = currentFallbacks()

        val mode: FontMode = when (primary) {
            null -> FontMode.Bundled
            SYSTEM_STACK_ID -> FontMode.System
            else -> {
                val primaryId = FontId.parse(primary)
                val seen = HashSet<FontId>().apply { add(primaryId) }
                FontMode.Custom(
                    primaryId,
                    fallbacks.filter { it != SYSTEM_STACK_ID }.map { FontId.parse(it) }.filter { seen.add(it) },
                )
            }
        }

        InuConfig.prefs.edit(true) {
            FontConfig.FONT.unsafeSet(mode, this)
            FontConfig.MONO_FONT.unsafeSet(draftMono, this)
        }
        val activity = parentActivity ?: return
        InuUtils.restartApp(activity)
    }

    companion object {
        fun isSentinel(token: String) = token == BUNDLED_STACK_ID || token == SYSTEM_STACK_ID

        private val BUTTON_PREVIEW = InuUtils.generateId()
        private val BUTTON_MONO = InuUtils.generateId()
        private val BUTTON_ADD = InuUtils.generateId()
        private val WARNINGS_ROW = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "font-stack",
            titleRes = R.string.InuAppFont,
            iconRes = R.drawable.msg_text_outlined,
            factory = ::FontStackActivity,
            entries = listOf(
                SearchRegistry.Entry("add-fallback", R.string.InuFontStackAdd, BUTTON_ADD),
            ),
        )
    }
}

@SuppressLint("ViewConstructor")
class StackFontRow(context: Context) : FrameLayout(context) {
    val heightDp = 58
    private val handle: ImageView
    private val text: TextView
    private val tagView: TextView
    private val remove: ImageView

    init {
        val v = buildFontRow(this)
        handle = v.handle
        text = v.text
        tagView = v.tag
        remove = v.trailing.apply {
            setImageResource(R.drawable.msg_delete)
            contentDescription = LocaleController.getString(R.string.InuFontRemove)
        }
    }

    fun bind(token: String, primary: Boolean, reorderable: Boolean) {
        val sentinel = FontStackActivity.isSentinel(token)
        handle.visibility = if (reorderable) VISIBLE else GONE
        remove.visibility = if (sentinel) GONE else VISIBLE

        if (sentinel) {
            val labelRes = if (token == SYSTEM_STACK_ID) R.string.InuFontSystem else R.string.InuFontDefault
            text.text = LocaleController.getString(labelRes)
            text.typeface = Typeface.DEFAULT
            FontSourceTag.bindRaw(tagView, primaryLabel(primary), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        } else {
            val font = FontId.parse(token)
            text.text = FontLibrary.getFontName(font)
            text.typeface = FontLibrary.getTypefaceFor(font) ?: Typeface.DEFAULT
            FontSourceTag.bind(tagView, font)
        }
    }

    private fun primaryLabel(primary: Boolean): String =
        LocaleController.getString(if (primary) R.string.InuFontStackPrimary else R.string.InuFontStackFallback)

    fun setOnReorderTouchListener(listener: OnTouchListener) {
        handle.setOnTouchListener(listener)
    }

    fun isInRemove(x: Float): Boolean = remove.visibility == VISIBLE && x >= remove.left && x <= remove.right
}

@SuppressLint("ViewConstructor")
class StackWarningsView(context: Context) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        setPadding(0, AndroidUtilities.dp(4f), 0, AndroidUtilities.dp(4f))
    }

    fun setWarnings(list: List<String>) {
        removeAllViews()
        val rtl = LocaleController.isRTL
        for (w in list) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(AndroidUtilities.dp(21f), AndroidUtilities.dp(8f), AndroidUtilities.dp(21f), AndroidUtilities.dp(8f))
            }
            val icon = ImageView(context).apply {
                setImageResource(R.drawable.warning_sign)
                colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_text_RedRegular), PorterDuff.Mode.SRC_IN)
            }
            val text = TextView(context).apply {
                setTextColor(Theme.getColor(Theme.key_text_RedRegular))
                textSize = 14f
                this.text = w
            }
            if (rtl) {
                row.addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                row.addView(icon, LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL, 12f, 0f, 0f, 0f))
            } else {
                row.addView(icon, LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL, 0f, 0f, 12f, 0f))
                row.addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }
    }
}
