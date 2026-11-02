package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.helpers.plugins.market.MarketEntry
import desu.inugram.helpers.plugins.market.MarketTag
import desu.inugram.helpers.plugins.market.MarketTags
import desu.inugram.helpers.plugins.ui.PluginManifestIcons
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper

internal enum class MarketAction { INSTALL, UPDATE, INSTALLED }

internal fun marketActionText(action: MarketAction): String = LocaleController.getString(
    when (action) {
        MarketAction.INSTALL -> R.string.InuPluginRepoInstall
        MarketAction.UPDATE -> R.string.InuPluginRepoUpdate
        MarketAction.INSTALLED -> R.string.InuPluginsInstalled
    }
)

/** a rounded button: filled for what the person can do, outlined for what is already done */
internal fun styleMarketAction(view: TextView, action: MarketAction) {
    val accent = Theme.getColor(Theme.key_featuredStickers_addButton)
    view.text = marketActionText(action)
    view.textSize = 13f
    view.typeface = AndroidUtilities.bold()
    view.gravity = Gravity.CENTER
    view.setSingleLine(true)
    view.setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(7f), AndroidUtilities.dp(16f), AndroidUtilities.dp(7f))
    if (action == MarketAction.INSTALLED) {
        val gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText)
        view.setTextColor(gray)
        view.background = Theme.createRoundRectDrawable(AndroidUtilities.dp(16f), Theme.multAlpha(gray, 0.14f))
    } else {
        view.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText))
        view.background = Theme.createRoundRectDrawable(AndroidUtilities.dp(16f), accent)
    }
}

internal class MarketChip(val key: String, val text: CharSequence, val selected: Boolean, val onClick: () -> Unit)

/** two scrolling rows of pills: what the person looks at (all, updates, installed) and the categories */
@SuppressLint("ViewConstructor")
internal class MarketPillsCell(context: Context) : LinearLayout(context) {
    private val statesRow = LinearLayout(context)
    private val tagsRow = LinearLayout(context)

    init {
        orientation = VERTICAL
        isClickable = true
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        for (row in listOf(statesRow, tagsRow)) {
            row.setPadding(AndroidUtilities.dp(12f), 0, AndroidUtilities.dp(12f), 0)
            val scroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = OVER_SCROLL_NEVER
                addView(row, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            }
            addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 8f, 0f, 2f))
        }
    }

    fun bind(states: List<MarketChip>, tags: List<MarketChip>) {
        fill(statesRow, states)
        fill(tagsRow, tags)
    }

    private fun fill(row: LinearLayout, chips: List<MarketChip>) {
        row.removeAllViews()
        for (chip in chips) {
            row.addView(chipView(chip), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 8f, 0f))
        }
    }

    private fun chipView(chip: MarketChip): TextView = TextView(context).apply {
        text = chip.text
        textSize = 13.5f
        gravity = Gravity.CENTER
        setSingleLine(true)
        setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(8f), AndroidUtilities.dp(14f), AndroidUtilities.dp(8f))
        val base = Theme.getColor(Theme.key_featuredStickers_addButton)
        if (chip.selected) {
            typeface = AndroidUtilities.bold()
            setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText))
            background = Theme.createRoundRectDrawable(AndroidUtilities.dp(18f), base)
        } else {
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
            background = Theme.createRoundRectDrawable(AndroidUtilities.dp(18f), Theme.multAlpha(base, 0.14f))
        }
        setOnClickListener { chip.onClick() }
    }
}

/** the line above the list that says there is something to update */
@SuppressLint("ViewConstructor")
internal class MarketBannerCell(context: Context) : LinearLayout(context) {
    private val title = TextView(context).apply {
        textSize = 15f
        typeface = AndroidUtilities.bold()
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
    }
    private val subtitle = TextView(context).apply {
        textSize = 13f
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val button = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val accent = Theme.getColor(Theme.key_featuredStickers_addButton)
        background = Theme.createRoundRectDrawable(AndroidUtilities.dp(16f), Theme.multAlpha(accent, 0.14f))
        setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(12f), AndroidUtilities.dp(12f), AndroidUtilities.dp(12f))
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        texts.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))
        addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 8, 0))
        addView(button, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 0, 0))
    }

    fun bind(titleText: CharSequence, subtitleText: CharSequence, buttonText: CharSequence, onClick: () -> Unit) {
        title.text = titleText
        subtitle.text = subtitleText
        styleMarketAction(button, MarketAction.UPDATE)
        button.text = buttonText
        button.setOnClickListener { onClick() }
        setOnClickListener { onClick() }
    }
}

private fun bindMarketIcon(view: BackupImageView, entry: MarketEntry) {
    PluginManifestIcons.bindIcon(view, entry.manifest.icon, PluginManifestIcons.createPlaceholder(view.context))
}

internal class MarketTagPills(context: Context) : HorizontalScrollView(context) {
    private val pills = LinearLayout(context)

    init {
        layoutDirection = if (LocaleController.isRTL) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(pills, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(ids: List<String>) {
        pills.removeAllViews()
        val tags = ids.mapNotNull { MarketTags.get(it) }.distinctBy { it.id }.take(MarketTags.MAX_PER_PLUGIN)
        tags.forEachIndexed { index, tag ->
            val pill = PluginPill(context, accent = index == 0).apply {
                text = LocaleController.getString(tag.nameRes)
            }
            pills.addView(pill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT).apply {
                marginEnd = if (index == tags.lastIndex) 0 else AndroidUtilities.dp(6f)
            })
        }
        visibility = if (tags.isEmpty()) GONE else VISIBLE
    }
}

/** the shelf of the developer's own plugins: big cards that scroll sideways */
@SuppressLint("ViewConstructor")
internal class MarketFeaturedCell(context: Context) : LinearLayout(context) {
    private val title = TextView(context).apply {
        textSize = 15f
        typeface = AndroidUtilities.bold()
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader))
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val count = TextView(context).apply {
        textSize = 13f
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        setSingleLine(true)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val head = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = if (LocaleController.isRTL) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR
        minimumHeight = AndroidUtilities.dp(48f)
        setPadding(AndroidUtilities.dp(20f), 0, AndroidUtilities.dp(20f), 0)
        background = Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2)
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private val cards = LinearLayout(context)

    init {
        orientation = VERTICAL
        isClickable = true
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
        head.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
        head.addView(count, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        val arrow = ImageView(context).apply {
            setImageResource(R.drawable.msg_arrowright)
            scaleType = ImageView.ScaleType.CENTER
            scaleX = if (LocaleController.isRTL) -1f else 1f
            colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        head.addView(arrow, LayoutHelper.createLinear(24, 24).apply { marginStart = AndroidUtilities.dp(8f) })
        addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        cards.setPadding(AndroidUtilities.dp(14f), 0, AndroidUtilities.dp(8f), AndroidUtilities.dp(14f))
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(cards, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
    }

    fun bind(
        titleText: CharSequence,
        countText: CharSequence,
        entries: List<MarketEntry>,
        action: (MarketEntry) -> MarketAction,
        onOpen: (MarketEntry) -> Unit,
        onAction: (MarketEntry) -> Unit,
        onOpenAll: () -> Unit,
    ) {
        title.text = titleText
        count.text = countText
        head.contentDescription = "$titleText, $countText"
        head.setOnClickListener { onOpenAll() }
        cards.removeAllViews()
        for (entry in entries) {
            cards.addView(card(entry, action(entry), onOpen, onAction), LayoutHelper.createLinear(136, LayoutHelper.WRAP_CONTENT, 0f, 0f, 10f, 0f))
        }
    }

    private fun card(entry: MarketEntry, action: MarketAction, onOpen: (MarketEntry) -> Unit, onAction: (MarketEntry) -> Unit): View {
        val tint = Theme.getColor(Theme.key_featuredStickers_addButton)
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Theme.createRoundRectDrawable(AndroidUtilities.dp(18f), Theme.multAlpha(tint, 0.13f))
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(12f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f))
        }
        val icon = BackupImageView(context).apply { setRoundRadius(AndroidUtilities.dp(14f)) }
        bindMarketIcon(icon, entry)
        card.addView(icon, LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 0))
        card.addView(TextView(context).apply {
            text = entry.name
            textSize = 15f
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 10f, 0f, 0f))
        val tags = MarketTagPills(context).apply { bind(entry.tags) }
        card.addView(tags, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 4f, 0f, 0f))
        card.addView(TextView(context).apply {
            text = entry.manifest.author.orEmpty()
            visibility = if (tags.visibility == GONE && text.isNotEmpty()) VISIBLE else GONE
            textSize = 12.5f
            gravity = Gravity.CENTER
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
        }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))
        val button = TextView(context)
        styleMarketAction(button, action)
        button.setOnClickListener { onAction(entry) }
        card.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 12f, 0f, 0f))
        card.setOnClickListener { onOpen(entry) }
        return card
    }
}

/** a plugin in the list: icon, name, a line about it, its categories and the button that matches what it is now */
@SuppressLint("ViewConstructor")
internal class MarketRow(context: Context) : LinearLayout(context) {
    private val icon = BackupImageView(context).apply { setRoundRadius(AndroidUtilities.dp(10f)) }
    private val title = TextView(context).apply {
        textSize = 15f
        typeface = AndroidUtilities.bold()
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
    }
    private val pill = PluginPill(context, accent = true)
    private val description = TextView(context).apply {
        textSize = 13f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText))
    }
    private val tags = MarketTagPills(context)
    private val button = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Theme.createSelectorWithBackgroundDrawable(
            Theme.getColor(Theme.key_windowBackgroundWhite),
            Theme.getColor(Theme.key_listSelector),
        )
        val titleLine = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        titleLine.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))
        titleLine.addView(pill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 6f, 0f, 0f, 0f))
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(titleLine, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        texts.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))
        texts.addView(tags, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 4f, 0f, 0f))
        val views = listOf<Pair<View, LayoutParams>>(
            icon to LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 16, 0, 12, 0),
            texts to LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 8, 8, 8),
            button to LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 16, 0),
        )
        for ((view, params) in if (LocaleController.isRTL) views.asReversed() else views) addView(view, params)
    }

    fun bind(entry: MarketEntry, action: MarketAction, onOpen: () -> Unit, onAction: () -> Unit) {
        bindMarketIcon(icon, entry)
        title.text = entry.name
        pill.text = if (entry.official) LocaleController.getString(R.string.InuMarketOfficial) else null
        pill.visibility = if (entry.official) VISIBLE else GONE
        val text = entry.manifest.description(LocaleController.getInstance().currentLocaleInfo?.langCode).orEmpty()
        description.text = text
        description.visibility = if (text.isEmpty()) GONE else VISIBLE
        tags.bind(entry.tags)
        styleMarketAction(button, action)
        button.textSize = 12.5f
        button.setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(5f), AndroidUtilities.dp(12f), AndroidUtilities.dp(5f))
        button.setOnClickListener { onAction() }
        setOnClickListener { onOpen() }
    }
}

internal fun marketTagChip(tag: MarketTag, count: Int, selected: Boolean, onClick: () -> Unit): MarketChip =
    MarketChip(tag.id, "${LocaleController.getString(tag.nameRes)} · $count", selected, onClick)
