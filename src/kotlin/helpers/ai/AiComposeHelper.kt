package desu.inugram.helpers.ai

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import desu.inugram.InuConfig
import desu.inugram.ui.settings.CategoryChatsSettingsActivity
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.LaunchActivity
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Client-side AI compose: rewrites / continues the chat draft via a user-configured
 * OpenAI-compatible endpoint (chat completions). Replaces the stock server-side
 * aiCompose flow when [InuConfig.AI_COMPOSE_ENABLED] is on.
 */
object AiComposeHelper {

    // ---------------- endpoint CRUD (settings UI + editor) ----------------

    @JvmStatic
    fun endpoints(): List<AiEndpoint> = InuConfig.AI_COMPOSE_ENDPOINTS.value

    @JvmStatic
    fun activeEndpoint(): AiEndpoint? {
        val list = endpoints()
        if (list.isEmpty()) return null
        val activeId = InuConfig.AI_COMPOSE_ACTIVE_ENDPOINT.value
        return list.firstOrNull { it.id == activeId } ?: list.first()
    }

    @JvmStatic
    fun setActiveEndpoint(id: String) {
        InuConfig.AI_COMPOSE_ACTIVE_ENDPOINT.value = id
    }

    @JvmStatic
    fun upsertEndpoint(endpoint: AiEndpoint) {
        val list = endpoints().toMutableList()
        val index = list.indexOfFirst { it.id == endpoint.id }
        if (index >= 0) list[index] = endpoint else list.add(endpoint)
        InuConfig.AI_COMPOSE_ENDPOINTS.value = list
    }

    @JvmStatic
    fun deleteEndpoint(id: String) {
        InuConfig.AI_COMPOSE_ENDPOINTS.value = endpoints().filterNot { it.id == id }
        if (InuConfig.AI_COMPOSE_ACTIVE_ENDPOINT.value == id) InuConfig.AI_COMPOSE_ACTIVE_ENDPOINT.value = ""
    }

    @JvmStatic
    fun newEndpointId(): String = UUID.randomUUID().toString()

    @JvmStatic
    fun host(url: String): String = try {
        URL(url).host ?: ""
    } catch (_: Exception) {
        ""
    }

    // ---------------- entry point (called from the stock patch) ----------------

    @JvmStatic
    fun showEditor(context: Context, text: CharSequence, onUse: Utilities.Callback<CharSequence>) {
        val isPremium = UserConfig.getInstance(UserConfig.selectedAccount).isPremium()
        val hasEndpoint = activeEndpoint()?.url?.isNotBlank() == true
        if (!isPremium && !hasEndpoint) {
            // Show a proper alert instead of silently failing
            org.telegram.ui.ActionBar.AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.InuAiCompose))
                .setMessage(LocaleController.getString(R.string.InuAiPremiumRequired))
                .setPositiveButton(LocaleController.getString(R.string.InuAiOpenSettings)) { _, _ ->
                    org.telegram.ui.LaunchActivity.instance?.presentFragment(
                        CategoryChatsSettingsActivity()
                    )
                }
                .setNegativeButton(LocaleController.getString(R.string.Cancel),
                    null as org.telegram.ui.ActionBar.AlertDialog.OnButtonClickListener?)
                .show()
            return
        }
        AiComposeSheet(context, text, onUse).show()
    }

    // ---------------- request ----------------

    fun request(
        endpoint: AiEndpoint,
        systemPrompt: String,
        userText: String,
        onResult: (result: String?, error: String?) -> Unit,
    ) {
        Utilities.globalQueue.postRunnable {
            var result: String? = null
            var error: String? = null
            try {
                val json = JSONObject()
                    .put("model", endpoint.model.ifBlank { "gpt-4o-mini" })
                    .put(
                        "messages",
                        JSONArray()
                            .put(JSONObject().put("role", "system").put("content", systemPrompt))
                            .put(JSONObject().put("role", "user").put("content", userText))
                    )

                val temp = InuConfig.AI_TEMPERATURE.value
                if (temp != 1.0f) {
                    json.put("temperature", temp.toDouble())
                }

                if (InuConfig.AI_REASONING_ENABLED.value) {
                    val effort = InuConfig.AI_REASONING_EFFORT.value
                    json.put("reasoning_effort", effort)
                    if (endpoint.url.contains("openrouter")) {
                        json.put("include_reasoning", true)
                    }
                }

                val body = json.toString()
                val conn = (URL(endpoint.url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    if (endpoint.apiKey.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer ${endpoint.apiKey}")
                    }
                }
                val resp = try {
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val body2 = (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    if (code !in 200..299) throw IOException("HTTP $code: ${body2.take(300)}")
                    body2
                } finally {
                    conn.disconnect()
                }
                val msgObj = JSONObject(resp)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                
                val content = msgObj.optString("content", "").trim()
                val reasoning = msgObj.optString("reasoning_content", "").ifBlank { msgObj.optString("reasoning", "") }.trim()

                result = if (InuConfig.AI_ONLY_ANSWER.value) {
                    content.ifBlank { reasoning }
                } else if (reasoning.isNotBlank() && content.isNotBlank()) {
                    "💭 $reasoning\n\n$content"
                } else {
                    content.ifBlank { reasoning }
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            val r = result
            val err = error
            AndroidUtilities.runOnUIThread { onResult(r, err) }
        }
    }

    // ---------------- actions (Fix tab) ----------------

    class Action(val labelRes: Int, val systemPrompt: String)

    internal val ACTIONS = listOf(
        Action(R.string.InuAiActionRewrite, "Rewrite the following text to improve clarity, flow and style while keeping the meaning. Return only the rewritten text."),
        Action(R.string.InuAiActionContinue, "Continue the following text naturally, as if writing the next part of the same message. Return only the continuation."),
        Action(R.string.InuAiActionSummarize, "Summarize the following text concisely. Return only the summary."),
        Action(R.string.InuAiActionFixGrammar, "Fix grammar, spelling and punctuation in the following text. Do not change the meaning. Return only the corrected text."),
    )

    // ---------------- style presets (Style tab) ----------------

    class StylePreset(val labelRes: Int, val emoji: String, val systemPrompt: String)

    internal val STYLE_PRESETS = listOf(
        StylePreset(R.string.InuAiStyleBiblical, "🕯️", "Rewrite in a biblical, archaic and solemn style using thee/thou language and scripture-like prose. Return only the rewritten text."),
        StylePreset(R.string.InuAiStyleCorp, "💼", "Rewrite in a professional corporate style — formal, concise and business-appropriate. Return only the rewritten text."),
        StylePreset(R.string.InuAiStyleZen, "🗿", "Rewrite in a calm, minimalist zen style — peaceful, contemplative, with short serene sentences. Return only the rewritten text."),
        StylePreset(R.string.InuAiStyleViking, "🪓", "Rewrite in a bold Norse warrior style — heroic, dramatic and battle-ready. Return only the rewritten text."),
        StylePreset(R.string.InuAiStyleShort, "🎯", "Rewrite as an extremely short, punchy version keeping only the core message. Return only the rewritten text."),
        StylePreset(R.string.InuAiStyleFormal, "🤝", "Rewrite in a formal, educated and polished style. Return only the rewritten text."),
        StylePreset(R.string.InuAiStyleTribal, "🛖", "Rewrite in a tribal, primal and earthy style with raw emotion. Return only the rewritten text."),
        StylePreset(R.string.InuAiStyleSarcastic, "😏", "Rewrite with heavy sarcasm and dry wit. Return only the rewritten text."),
        StylePreset(R.string.InuAiStylePoetic, "📜", "Rewrite as a short poem or poetic prose with rhythm and vivid imagery. Return only the rewritten text."),
    )

    // ---------------- translate languages ----------------

    class TranslateLang(val code: String, val displayName: String, val nativeName: String)

    internal val TRANSLATE_LANGS = listOf(
        TranslateLang("uk", "Ukrainian", "Українська"),
        TranslateLang("en", "English", "English"),
        TranslateLang("ru", "Russian", "Русский"),
        TranslateLang("pl", "Polish", "Polski"),
        TranslateLang("de", "German", "Deutsch"),
        TranslateLang("fr", "French", "Français"),
        TranslateLang("es", "Spanish", "Español"),
        TranslateLang("it", "Italian", "Italiano"),
        TranslateLang("ja", "Japanese", "日本語"),
    )
}


// ==================== AiComposeSheet ====================

private class AiComposeSheet(
    context: Context,
    originalText: CharSequence,
    private val onUse: Utilities.Callback<CharSequence>,
) : BottomSheet(context, false) {

    private enum class Tab { STYLE, TRANSLATE, FIX }

    private val userText = originalText.toString().trim()
    private var running = false
    private var emojiEnabled = false
    private var lastResult: String? = null
    private var currentTab = Tab.STYLE
    private val tabViews = mutableListOf<TextView>()
    private val tabIcons = mutableListOf<ImageView>()
    private var translateToIdx = 0

    private val contentArea = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var loadingText: TextView? = null
    private var resultText: TextView? = null
    private var resultActions: LinearLayout? = null

    init {
        setApplyBottomPadding(false)
        setApplyTopPadding(false)
        fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite))

        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // Header: "ШІ-редактор" + Close button
        root.addView(buildHeader(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val endpoint = AiComposeHelper.activeEndpoint()
        if (endpoint == null || endpoint.url.isBlank()) {
            root.addView(noEndpointView(endpoint == null), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        } else {
            // Tab bar (Icon + Text tabs)
            root.addView(buildTabBar(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16f, 8f, 16f, 16f))

            // Tab content
            root.addView(contentArea, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

            // Loading indicator
            loadingText = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
                text = LocaleController.getString(R.string.InuAiGenerating)
                gravity = Gravity.CENTER_HORIZONTAL
                visibility = View.GONE
            }
            root.addView(loadingText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            
            // Result text card
            resultText = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
                setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
                setLineSpacing(AndroidUtilities.dp(3f).toFloat(), 1.1f)
                setTextIsSelectable(true)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(Theme.getColor(Theme.key_windowBackgroundGray))
                }
                visibility = View.GONE
            }
            root.addView(resultText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 8, 16, 8))

            // Use / Copy buttons
            resultActions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = View.GONE
            }
            val useBtn = roundedButton(LocaleController.getString(R.string.InuAiUse)) { applyResult() }
            val copyBtn = roundedButton(LocaleController.getString(R.string.InuAiCopy)) { copyResult() }
            resultActions!!.addView(useBtn, LayoutHelper.createLinear(0, 44, 1f, 0, 0, 8, 0))
            resultActions!!.addView(copyBtn, LayoutHelper.createLinear(0, 44, 1f, 8, 0, 0, 0))
            root.addView(resultActions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 12, 16, 20))

            switchTab(Tab.STYLE)
        }

        val scroll = NestedScrollView(context).apply {
            addView(root)
            isFillViewport = true
        }
        setCustomView(scroll)
    }

    // ------------------------------------------------------------------ Header & Tab bar

    private fun buildHeader(): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(12))
        }

        val titleTv = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            typeface = AndroidUtilities.bold()
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            text = LocaleController.getString(R.string.InuAiCompose)
        }
        container.addView(titleTv, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))

        val closeBtn = ImageView(context).apply {
            setImageResource(R.drawable.msg_close)
            setColorFilter(Theme.getColor(Theme.key_dialogTextGray3))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL)
            setOnClickListener { dismiss() }
        }
        container.addView(closeBtn, LinearLayout.LayoutParams(dp(36), dp(36)))

        return container
    }

    private class TabItem(val tab: Tab, val iconRes: Int, val labelRes: Int)

    private fun buildTabBar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val tabs = listOf(
            TabItem(Tab.TRANSLATE, R.drawable.msg_translate, R.string.InuAiTabTranslate),
            TabItem(Tab.STYLE, R.drawable.msg_edit, R.string.InuAiTabStyle),
            TabItem(Tab.FIX, R.drawable.msg_search, R.string.InuAiTabFix),
        )

        tabs.forEach { item ->
            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { switchTab(item.tab) }
            }

            val iconIv = ImageView(context).apply {
                setImageResource(item.iconRes)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }

            val tv = TextView(context).apply {
                text = LocaleController.getString(item.labelRes)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
                typeface = AndroidUtilities.bold()
                isSingleLine = true
            }

            layout.addView(iconIv, LinearLayout.LayoutParams(dp(72), dp(42)))
            layout.addView(tv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 4f, 0f, 0f))

            tabViews.add(tv)
            tabIcons.add(iconIv)

            bar.addView(layout, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))
        }

        return bar
    }

    private fun switchTab(tab: Tab) {
        currentTab = tab

        val accentColor = Theme.getColor(Theme.key_featuredStickers_addButton)
        val textBlack = Theme.getColor(Theme.key_dialogTextBlack)
        val textGray = Theme.getColor(Theme.key_dialogTextGray3)

        val tabs = listOf(Tab.TRANSLATE, Tab.STYLE, Tab.FIX)
        tabs.forEachIndexed { i, t ->
            val tv = tabViews[i]
            val iconIv = tabIcons[i]
            val active = t == tab

            if (active) {
                iconIv.background = GradientDrawable().apply {
                    cornerRadius = dp(21f).toFloat()
                    setColor(ColorUtils.setAlphaComponent(accentColor, 40))
                }
                iconIv.setColorFilter(accentColor)
                tv.setTextColor(accentColor)
            } else {
                iconIv.background = null
                iconIv.setColorFilter(textBlack)
                tv.setTextColor(textGray)
            }
        }

        clearResult()
        contentArea.removeAllViews()
        val content = when (tab) {
            Tab.STYLE -> buildStyleContent()
            Tab.TRANSLATE -> buildTranslateContent()
            Tab.FIX -> buildFixContent()
        }
        contentArea.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
    }

    // ------------------------------------------------------------------ Style tab

    private fun buildStyleContent(): LinearLayout {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // Horizontally scrollable vertical style cards
        val scrollView = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val cardsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        AiComposeHelper.STYLE_PRESETS.forEach { preset ->
            cardsRow.addView(
                styleCard(preset.emoji, LocaleController.getString(preset.labelRes)) {
                    val extra = if (emojiEnabled) " Include relevant emoji throughout the response." else ""
                    runPrompt(preset.systemPrompt + extra)
                },
                LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply { marginEnd = dp(12) }
            )
        }
        scrollView.addView(cardsRow)
        root.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        // Middle sub-header row: "Виберіть стиль" pill + "емоджі" toggle button
        val subHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }

        val subtitlePill = TextView(context).apply {
            text = LocaleController.getString(R.string.InuAiTabStyle)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            typeface = AndroidUtilities.bold()
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(Theme.getColor(Theme.key_windowBackgroundGray))
            }
        }
        subHeaderRow.addView(subtitlePill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        subHeaderRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        subHeaderRow.addView(buildEmojiToggle())
        root.addView(subHeaderRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        // Text preview card (selectable & full text display)
        val textPreviewCard = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextIsSelectable(true)
            text = userText
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Theme.getColor(Theme.key_windowBackgroundGray))
            }
        }
        root.addView(textPreviewCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 16, 8))

        return root
    }

    // ------------------------------------------------------------------ Translate tab

    private fun buildTranslateContent(): LinearLayout {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // Source text section
        val sourceHeader = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            typeface = AndroidUtilities.bold()
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            text = LocaleController.getString(R.string.InuAiTranslateFrom)
        }
        root.addView(sourceHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 8, 16, 6))

        val sourceText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextIsSelectable(true)
            text = userText
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Theme.getColor(Theme.key_windowBackgroundGray))
            }
        }
        root.addView(sourceText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 16, 12))

        root.addView(divider(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 16, 4, 16, 12))

        // Target language row: Interactive picker chip + emoji toggle
        val targetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(12))
        }

        val targetLangChip = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            typeface = AndroidUtilities.bold()
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = Theme.createSimpleSelectorRoundRectDrawable(
                dp(18f),
                Theme.getColor(Theme.key_windowBackgroundGray),
                ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_dialogTextBlack), 30)
            )
            text = LocaleController.formatString(R.string.InuAiTranslateTo, AiComposeHelper.TRANSLATE_LANGS[translateToIdx].nativeName) + " ▾"
            setOnClickListener { showLanguagePicker(this) }
        }
        targetRow.addView(targetLangChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        targetRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        targetRow.addView(buildEmojiToggle())
        root.addView(targetRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        // Translate run button
        val translateBtn = roundedButton(LocaleController.getString(R.string.InuAiTranslateRun)) {
            val lang = AiComposeHelper.TRANSLATE_LANGS[translateToIdx]
            val extra = if (emojiEnabled) " Include relevant emoji." else ""
            runPrompt("Translate the following text to ${lang.displayName}. Return only the translated text.$extra")
        }
        root.addView(translateBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 16, 4, 16, 12))

        return root
    }

    private fun showLanguagePicker(chipView: TextView) {
        val items = AiComposeHelper.TRANSLATE_LANGS.map { lang ->
            desu.inugram.ui.settings.RadioDialogBuilder.Item(lang.nativeName, lang.displayName)
        }
        desu.inugram.ui.settings.RadioDialogBuilder(context)
            .setTitle(LocaleController.getString(R.string.InuTranslationTarget))
            .setItems(items, translateToIdx) { dialog, which ->
                translateToIdx = which
                chipView.text = LocaleController.formatString(R.string.InuAiTranslateTo, AiComposeHelper.TRANSLATE_LANGS[translateToIdx].nativeName) + " ▾"
                dialog.dismiss()
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show()
    }

    // ------------------------------------------------------------------ Fix tab

    private fun buildFixContent(): LinearLayout {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // Source text card
        val sourceText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextIsSelectable(true)
            text = userText
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Theme.getColor(Theme.key_windowBackgroundGray))
            }
        }
        root.addView(sourceText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 8, 16, 12))

        root.addView(divider(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 16, 4, 16, 12))

        // Clean action cards list
        val actionsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (action in AiComposeHelper.ACTIONS) {
            actionsContainer.addView(
                actionRow(LocaleController.getString(action.labelRes)) {
                    runPrompt(action.systemPrompt)
                },
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 2, 16, 2)
            )
        }
        root.addView(actionsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        root.addView(divider(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 16, 10, 16, 10))

        // Custom prompt input card
        val customCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Theme.getColor(Theme.key_windowBackgroundGray))
            }
        }

        val customInput = EditText(context).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
            setBackgroundColor(Color.TRANSPARENT)
            hint = LocaleController.getString(R.string.InuAiCustomPrompt)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        customCard.addView(customInput, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))

        val runBtn = roundedButton("➔") {
            val p = customInput.text.toString().trim()
            if (p.isNotBlank()) runPrompt(p)
        }
        customCard.addView(runBtn, LayoutHelper.createLinear(40, 40, 0, 0, 0, 4, 0))

        root.addView(customCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 4, 16, 12))

        return root
    }

    // ------------------------------------------------------------------ Widgets

    private fun styleCard(emoji: String, label: String, onClick: () -> Unit): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { onClick() }
        }

        val emojiTv = TextView(context).apply {
            text = emoji
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24f)
            gravity = Gravity.CENTER
        }
        card.addView(emojiTv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        val labelTv = TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            typeface = AndroidUtilities.bold()
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            gravity = Gravity.CENTER
        }
        card.addView(labelTv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 4f, 0f, 0f))

        return card
    }

    private fun buildEmojiToggle(): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                emojiEnabled = !emojiEnabled
                refreshEmojiToggle(this)
            }
        }

        val radioCircle = View(context).apply {
            id = View.generateViewId()
        }
        container.addView(radioCircle, LayoutHelper.createLinear(18, 18, 0f, 0f, 6f, 0f))

        val tv = TextView(context).apply {
            id = View.generateViewId()
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            text = LocaleController.getString(R.string.InuAiEmoji)
        }
        container.addView(tv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        refreshEmojiToggle(container)
        return container
    }

    private fun refreshEmojiToggle(container: LinearLayout) {
        val circle = container.getChildAt(0)
        val tv = container.getChildAt(1) as TextView
        val accentColor = Theme.getColor(Theme.key_featuredStickers_addButton)
        val textGray = Theme.getColor(Theme.key_dialogTextGray3)

        if (emojiEnabled) {
            circle.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accentColor)
            }
            tv.setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
        } else {
            circle.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), textGray)
                setColor(Color.TRANSPARENT)
            }
            tv.setTextColor(textGray)
        }
    }

    private fun actionRow(text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            this.text = text
            setPadding(dp(20), dp(13), dp(20), dp(13))
            background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL)
            setOnClickListener { onClick() }
        }

    private fun roundedButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            typeface = AndroidUtilities.bold()
            text = label
            setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText))
            background = Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(8f),
                Theme.getColor(Theme.key_featuredStickers_addButton),
                ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_windowBackgroundWhite), 120)
            )
            setOnClickListener { onClick() }
        }

    private fun divider(): View = View(context).apply {
        setBackgroundColor(Theme.getColor(Theme.key_divider))
    }

    private fun noEndpointView(missing: Boolean): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        box.addView(
            TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
                text = LocaleController.getString(if (missing) R.string.InuAiNoEndpoint else R.string.InuAiBadEndpoint)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 20, 20, 0)
        )
        val settingsBtn = roundedButton(LocaleController.getString(R.string.InuAiOpenSettings)) {
            dismiss()
            LaunchActivity.instance?.presentFragment(CategoryChatsSettingsActivity())
        }
        box.addView(settingsBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 20, 0, 20, 10))
        return box
    }

    // ------------------------------------------------------------------ Request

    private fun runPrompt(prompt: String) {
        if (running) return
        val endpoint = AiComposeHelper.activeEndpoint() ?: return
        if (userText.isEmpty()) return
        clearResult()
        setRunning(true)
        AiComposeHelper.request(endpoint, prompt, userText) { result, error ->
            setRunning(false)
            if (result != null) {
                lastResult = result
                resultText?.setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
                resultText?.text = result
                resultText?.visibility = View.VISIBLE
                resultActions?.visibility = View.VISIBLE
            } else {
                resultText?.setTextColor(Theme.getColor(Theme.key_text_RedBold))
                resultText?.text = LocaleController.formatString(R.string.InuAiError, error ?: "?")
                resultText?.visibility = View.VISIBLE
                resultActions?.visibility = View.GONE
            }
        }
    }

    private fun clearResult() {
        lastResult = null
        resultText?.visibility = View.GONE
        resultActions?.visibility = View.GONE
    }

    private fun setRunning(value: Boolean) {
        running = value
        loadingText?.visibility = if (value) View.VISIBLE else View.GONE
    }

    private fun applyResult() {
        val result = lastResult ?: return
        dismiss()
        onUse.run(result)
    }

    private fun copyResult() {
        val result = lastResult ?: return
        AndroidUtilities.addToClipboard(result)
        Toast.makeText(context, LocaleController.getString(R.string.TextCopied), Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = AndroidUtilities.dp(v.toFloat())
    private fun dp(v: Float): Int = AndroidUtilities.dp(v)
}
