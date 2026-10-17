package desu.inugram

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.update.UpdateHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.DocumentObject
import org.telegram.messenger.Emoji
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedEmojiSpan
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.URLSpanReplacement

class UpdateAppAlertDialog(
    context: Context,
    private val appUpdate: TLRPC.TL_help_appUpdate,
    private val accountNum: Int,
) : BottomSheet(context, false) {

    private val shadowDrawable: Drawable = context.resources.getDrawable(R.drawable.sheet_shadow_round).mutate().apply {
        colorFilter = PorterDuffColorFilter(Theme.getColor(Theme.key_dialogBackground), PorterDuff.Mode.MULTIPLY)
    }

    private val container: FrameLayout
    private val scrollView: NestedScrollView
    private val linearLayout: LinearLayout
    private val location = IntArray(2)
    private var scrollOffsetY = 0

    companion object {
        private const val BUTTON_HEIGHT = 44f
        private const val BUTTON_ROW_TOP_MARGIN = 16f
        private const val BUTTON_ROW_BOTTOM_MARGIN = 16f
        private const val BUTTON_ROW_HEIGHT = BUTTON_HEIGHT + BUTTON_ROW_TOP_MARGIN + BUTTON_ROW_BOTTOM_MARGIN

        private const val EMOJI_ADDED = 5278611606756942667L
        private const val EMOJI_CHANGED = 5276240711795107620L
        private const val EMOJI_FIXED = 5276442772826515132L
        private const val EMOJI_REMOVED = 5276384644739129761L
    }

    init {
        setCanceledOnTouchOutside(false)
        setApplyTopPadding(false)
        setApplyBottomPadding(false)

        container = object : FrameLayout(context) {
            override fun setTranslationY(translationY: Float) {
                super.setTranslationY(translationY)
                updateLayout()
            }

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                if (ev.action == MotionEvent.ACTION_DOWN && scrollOffsetY != 0 && ev.y < scrollOffsetY) {
                    dismiss()
                    return true
                }
                return super.onInterceptTouchEvent(ev)
            }

            override fun onTouchEvent(e: MotionEvent): Boolean {
                return !isDismissed && super.onTouchEvent(e)
            }

            override fun onDraw(canvas: Canvas) {
                val top = (scrollOffsetY - backgroundPaddingTop - translationY).toInt()
                shadowDrawable.setBounds(0, top, measuredWidth, measuredHeight)
                shadowDrawable.draw(canvas)
            }
        }
        container.setWillNotDraw(false)
        containerView = container

        scrollView = object : NestedScrollView(context) {
            private var ignoreLayout = false

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val height = MeasureSpec.getSize(heightMeasureSpec)
                measureChildWithMargins(linearLayout, widthMeasureSpec, 0, heightMeasureSpec, 0)
                val contentHeight = linearLayout.measuredHeight
                var padding = (height / 5 * 2)
                val visiblePart = height - padding
                if (contentHeight - visiblePart < AndroidUtilities.dp(90f)
                    || contentHeight < height / 2 + AndroidUtilities.dp(90f)
                ) {
                    padding = height - contentHeight
                }
                if (padding < 0) padding = 0
                if (paddingTop != padding) {
                    ignoreLayout = true
                    setPadding(0, padding, 0, 0)
                    ignoreLayout = false
                }
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            }

            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                super.onLayout(changed, l, t, r, b)
                updateLayout()
            }

            override fun requestLayout() {
                if (ignoreLayout) return
                super.requestLayout()
            }

            override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
                super.onScrollChanged(l, t, oldl, oldt)
                updateLayout()
            }
        }.apply {
            isFillViewport = true
            setWillNotDraw(false)
            clipToPadding = false
            isVerticalScrollBarEnabled = false
        }
        container.addView(
            scrollView,
            LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat(),
                Gravity.LEFT or Gravity.TOP, 0f, 0f, 0f, BUTTON_ROW_HEIGHT,
            ),
        )

        linearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(
            linearLayout,
            LayoutHelper.createScroll(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT or Gravity.TOP,
            ),
        )

        val isBeta = UpdateHelper.pendingIsBeta

        // 1. Header row: icon on left, title + version/size on right (in one horizontal line)
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        if (appUpdate.sticker != null) {
            val imageView = BackupImageView(context)
            val sticker = appUpdate.sticker
            val docLocation = ImageLocation.getForDocument(sticker)
            val svgThumb = DocumentObject.getSvgThumb(sticker.thumbs, Theme.key_windowBackgroundGray, 1.0f)
            if (svgThumb != null) {
                imageView.setImage(docLocation, "100_100", svgThumb, 0, "update")
            } else {
                val thumb = FileLoader.getClosestPhotoSizeWithSize(sticker.thumbs, 90)
                imageView.setImage(docLocation, "100_100", ImageLocation.getForDocument(thumb, sticker), null, 0, "update")
            }
            headerRow.addView(
                imageView,
                LayoutHelper.createLinear(48, 48, Gravity.CENTER_VERTICAL, 0, 0, 14, 0),
            )
        } else {
            val iconView = ImageView(context).apply {
                setImageResource(R.drawable.msg_download)
                setColorFilter(Theme.getColor(Theme.key_featuredStickers_addButton))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton), 0.14f))
                }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val p = AndroidUtilities.dp(12f)
                setPadding(p, p, p, p)
            }
            headerRow.addView(
                iconView,
                LayoutHelper.createLinear(48, 48, Gravity.CENTER_VERTICAL, 0, 0, 14, 0),
            )
        }

        val titleColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val titleView = TextView(context).apply {
            typeface = AndroidUtilities.bold()
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            text = if (isBeta) "entinyGram β" else "entinyGram"
        }
        titleColumn.addView(
            titleView,
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT),
        )

        val sizeBytes = appUpdate.document?.size ?: 0L
        val sizeStr = if (sizeBytes > 0) AndroidUtilities.formatFileSize(sizeBytes) else ""
        val newName = UpdateHelper.pendingBetaUpdate?.version?.takeIf { it.isNotBlank() }
        val newCode = appUpdate.version?.takeIf { it.isNotBlank() }
        val newStr = listOfNotNull(newName?.let { "v$it" }, newCode?.let { "($it)" }).joinToString(" ")
        val oldStr = "v${UpdateHelper.stockVersionName} (${UpdateHelper.packageInfo.versionCode})"
        val versionInfo = SpannableStringBuilder().apply {
            if (newStr.isNotEmpty()) {
                append(oldStr)
                setSpan(StrikethroughSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(" → ").append(newStr)
            }
            if (sizeStr.isNotEmpty()) {
                if (isNotEmpty()) append(" • ")
                append(sizeStr)
            }
            if (isEmpty()) append(LocaleController.getString(R.string.AppUpdate))
        }

        val messageView = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            text = versionInfo
        }
        titleColumn.addView(
            messageView,
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f),
        )

        headerRow.addView(
            titleColumn,
            LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL),
        )

        linearLayout.addView(
            headerRow,
            LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL, 20, 20, 20, 0,
            ),
        )

        // 2. Centered pill badge: architecture + plugins/pluginless variant
        val isPlugins = !BuildConfig.INU_PLUGINLESS
        val isUk = LocaleController.getInstance().currentLocale?.language?.lowercase() in listOf("uk", "ru", "be")
        val primaryAbi = Build.SUPPORTED_ABIS?.firstOrNull() ?: "arm64-v8a"
        val archLabel = when {
            primaryAbi.contains("arm64") -> "arm64"
            primaryAbi.contains("armeabi") || primaryAbi.contains("arm") -> "arm7"
            primaryAbi.contains("x86_64") -> "x86_64"
            primaryAbi.contains("x86") -> "x86"
            else -> primaryAbi
        }
        val variantLabel = if (isUk) {
            if (isPlugins) "з плагінами" else "без плагінів"
        } else {
            if (isPlugins) "plugins" else "pluginless"
        }

        val pillColor = if (isBeta) {
            Theme.getColor(Theme.key_text_RedRegular)
        } else {
            Theme.getColor(Theme.key_featuredStickers_addButton)
        }

        val badgeView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(3.5f), AndroidUtilities.dp(10f), AndroidUtilities.dp(3.5f))
            setTextColor(pillColor)
            background = GradientDrawable().apply {
                cornerRadius = AndroidUtilities.dp(12f).toFloat()
                setColor(Theme.multAlpha(pillColor, 0.12f))
            }
            text = "$archLabel • $variantLabel"
        }
        linearLayout.addView(
            badgeView,
            LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL, 20, 10, 20, 0,
            ),
        )

        // 3. Changelog card with clickable links & beautiful formatting
        val changelogView = AnimatedEmojiSpan.TextViewEmojis(context).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            movementMethod = AndroidUtilities.LinkMovementMethodMy()
            setLinkTextColor(Theme.getColor(Theme.key_dialogTextLink))
            setLineSpacing(AndroidUtilities.dp(2f).toFloat(), 1.15f)
            gravity = Gravity.LEFT or Gravity.TOP
            text = parseChangelog(appUpdate.text, appUpdate.entities, paint.fontMetricsInt)
        }

        val changelogCard = FrameLayout(context).apply {
            background = Theme.createRoundRectDrawable(
                AndroidUtilities.dp(16f),
                Theme.getColor(Theme.key_windowBackgroundGray),
            )
            setPadding(
                AndroidUtilities.dp(16f),
                AndroidUtilities.dp(14f),
                AndroidUtilities.dp(16f),
                AndroidUtilities.dp(14f),
            )
        }
        changelogCard.addView(
            changelogView,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat()),
        )
        linearLayout.addView(
            changelogCard,
            LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT or Gravity.TOP, 16, 16, 16, 8,
            ),
        )

        // 4. Action buttons
        val radius = BUTTON_HEIGHT / 2f
        val scheduleButton = makeButton(
            R.string.AppUpdateRemindMeLater,
            background = Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(radius), 0, Theme.getColor(Theme.key_dialogButtonSelector),
            ),
            textColor = Theme.getColor(Theme.key_dialogTextBlack),
            bold = false,
        ) {
            UpdateHelper.clearPending()
            dismiss()
        }
        val doneButton = makeButton(
            R.string.AppUpdateDownloadNow,
            background = Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, radius),
            textColor = Theme.getColor(Theme.key_featuredStickers_buttonText),
            bold = true,
        ) {
            UpdateHelper.startDownload(accountNum)
            dismiss()
        }
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(scheduleButton, LinearLayout.LayoutParams(0, AndroidUtilities.dp(BUTTON_HEIGHT), 1f).apply {
                marginEnd = AndroidUtilities.dp(8f)
            })
            addView(doneButton, LinearLayout.LayoutParams(0, AndroidUtilities.dp(BUTTON_HEIGHT), 1f))
        }
        container.addView(
            buttonRow,
            LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(),
                Gravity.LEFT or Gravity.BOTTOM,
                16f, BUTTON_ROW_TOP_MARGIN, 16f, BUTTON_ROW_BOTTOM_MARGIN,
            ),
        )
    }

    private fun parseChangelog(
        rawText: String?,
        rawEntities: ArrayList<TLRPC.MessageEntity>?,
        fontMetricsInt: Paint.FontMetricsInt?,
    ): CharSequence {
        if (rawText.isNullOrBlank()) {
            return AndroidUtilities.replaceTags(LocaleController.getString(R.string.AppUpdateChangelogEmpty))
        }

        var text = rawText
        var entities = rawEntities

        // 1. Language section extraction if bilingual post
        val hasUk = text.contains("🇺🇦") || text.contains("UK:")
        val hasEn = text.contains("🇺🇸") || text.contains("EN:") || text.contains("Eng:")

        val lang = LocaleController.getInstance().currentLocale?.language?.lowercase() ?: "en"
        val isCyrillic = lang in listOf("uk", "ru", "be")

        if (hasUk && hasEn) {
            val ukPos = text.indexOf("🇺🇦").takeIf { it >= 0 } ?: text.indexOf("UK:")
            val enPos = text.indexOf("🇺🇸").takeIf { it >= 0 } ?: (text.indexOf("EN:").takeIf { it >= 0 } ?: text.indexOf("Eng:"))

            if (ukPos >= 0 && enPos >= 0) {
                val (sliceStart, sliceEnd) = if (isCyrillic) {
                    if (ukPos < enPos) ukPos to enPos else ukPos to text.length
                } else {
                    if (enPos < ukPos) enPos to ukPos else enPos to text.length
                }

                val sliced = text.substring(sliceStart, sliceEnd).trim()
                entities = sliceEntities(entities, sliceStart, sliceEnd)
                text = sliced

                val headerMatch = Regex("^(?:🇺🇦|🇺🇸|🇬🇧)\\s*(?:UK|EN|Eng)?\\s*:\\s*", RegexOption.IGNORE_CASE).find(text)
                if (headerMatch != null) {
                    val headerLen = headerMatch.value.length
                    text = text.substring(headerLen).trimStart()
                    entities = shiftEntities(entities, -headerLen)
                }
            }
        }

        // 2. Strip technical hashtags
        text = text.replace(Regex("#(?:release|prerelease|update)\\b", RegexOption.IGNORE_CASE), "").trim()

        // 2.5. Normalize markdown section headers (### Added:, ### Rewrited:, ### Fixed:, ### Deleted:) into emoji headers
        text = text.replace(Regex("^###\\s*(Added|New(?:\\s+Features)?|Додано|Добавлено)\\s*:?", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))) {
            if (isCyrillic) "❤️ Додано:" else "❤️ Added:"
        }
        text = text.replace(Regex("^###\\s*(Changed|Rewrited|Rewritten|Improvements?(?:\\s*&\\s*Polish)?|Змінено|Изменено)\\s*:?", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))) {
            if (isCyrillic) "⚠️ Змінено:" else "⚠️ Changed:"
        }
        text = text.replace(Regex("^###\\s*(Fixed|Bug\\s*Fixes|Виправлено|Исправлено)\\s*:?", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))) {
            if (isCyrillic) "🎨 Виправлено:" else "🎨 Fixed:"
        }
        text = text.replace(Regex("^###\\s*(Removed|Deleted|Видалено|Удалено)\\s*:?", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))) {
            if (isCyrillic) "🗑 Видалено:" else "🗑 Removed:"
        }

        // 3. Extract and parse markdown links: [label](url) -> label with TLRPC.TL_messageEntityTextUrl
        val customEntities = ArrayList<TLRPC.MessageEntity>()
        if (entities != null) customEntities.addAll(entities)

        val linkRe = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")
        if (linkRe.containsMatchIn(text)) {
            val sb = StringBuilder()
            var lastIdx = 0
            for (m in linkRe.findAll(text)) {
                sb.append(text, lastIdx, m.range.first)
                val label = m.groupValues[1]
                val url = m.groupValues[2]
                val offset = sb.length
                sb.append(label)
                customEntities.add(TLRPC.TL_messageEntityTextUrl().apply {
                    this.offset = offset
                    this.length = label.length
                    this.url = url
                })
                lastIdx = m.range.last + 1
            }
            sb.append(text, lastIdx, text.length)
            text = sb.toString()
        }

        // 3.5. Pre-release commit lines: normalize "7f3a9c1: description" -> "7f3a9c1 — description"
        val commitColonRe = Regex("^([0-9a-f]{7,10})(:[ \\t]+)", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        val commitMatches = commitColonRe.findAll(text).toList()
        if (commitMatches.isNotEmpty()) {
            val sb = StringBuilder(text)
            for (m in commitMatches.asReversed()) {
                val sepGroup = m.groups[2] ?: continue
                val sepStart = sepGroup.range.first
                val sepEndExcl = sepGroup.range.last + 1
                val replacement = " — "
                val delta = replacement.length - (sepEndExcl - sepStart)
                sb.replace(sepStart, sepEndExcl, replacement)
                if (delta != 0) {
                    for (entity in customEntities) {
                        if (entity.offset >= sepEndExcl) {
                            entity.offset += delta
                        }
                    }
                }
            }
            text = sb.toString()
        }

        // 4. Highlight section headers and attach TranslucentPack custom emojis
        val sectionHeaderRe = Regex("^(❤️|✨|⚠️|⚡|🎨|🛠️|🛠|🗑️|🗑)\\s*[^\\n]+:?", RegexOption.MULTILINE)
        for (m in sectionHeaderRe.findAll(text)) {
            val emojiGroup = m.groups[1]
            if (emojiGroup != null) {
                val docId = when (emojiGroup.value) {
                    "❤️", "✨" -> EMOJI_ADDED
                    "⚠️", "⚡" -> EMOJI_CHANGED
                    "🎨", "🛠️", "🛠" -> EMOJI_FIXED
                    "🗑", "🗑️" -> EMOJI_REMOVED
                    else -> 0L
                }
                if (docId != 0L) {
                    customEntities.add(TLRPC.TL_messageEntityCustomEmoji().apply {
                        this.offset = emojiGroup.range.first
                        this.length = emojiGroup.range.last - emojiGroup.range.first + 1
                        this.document_id = docId
                    })
                }
            }
            customEntities.add(TLRPC.TL_messageEntityBold().apply {
                this.offset = m.range.first
                this.length = m.range.last - m.range.first + 1
            })
        }

        // 5. Build SpannableStringBuilder and apply entities + custom emojis
        val builder = SpannableStringBuilder(text)
        Emoji.replaceEmoji(builder, fontMetricsInt, false)
        if (customEntities.isNotEmpty()) {
            MessageObject.addEntitiesToText(builder, customEntities, false, false, false, false)
            MessageObject.replaceAnimatedEmoji(builder, customEntities, fontMetricsInt, false, 1f, 0)
        }

        // 5.5. Style pre-release commit hashes (bold monospace) and separators (muted gray)
        val commitStyledRe = Regex("^([0-9a-f]{7,10})( — )", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        for (m in commitStyledRe.findAll(text)) {
            val hashGroup = m.groups[1]
            val sepGroup = m.groups[2]
            if (hashGroup != null) {
                val hStart = hashGroup.range.first
                val hEnd = hashGroup.range.last + 1
                builder.setSpan(TypefaceSpan("monospace"), hStart, hEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(StyleSpan(Typeface.BOLD), hStart, hEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(ForegroundColorSpan(Theme.getColor(Theme.key_dialogTextLink)), hStart, hEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (sepGroup != null) {
                val sStart = sepGroup.range.first
                val sEnd = sepGroup.range.last + 1
                builder.setSpan(ForegroundColorSpan(Theme.getColor(Theme.key_dialogTextGray3)), sStart, sEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        // 6. Hook tg:// links so clicking them dismisses the update dialog and navigates cleanly
        val urlSpans = builder.getSpans(0, builder.length, URLSpan::class.java)
        for (span in urlSpans) {
            val start = builder.getSpanStart(span)
            val end = builder.getSpanEnd(span)
            val flags = builder.getSpanFlags(span)
            val url = span.url
            val isCommitHash = (end - start in 7..10) && text.substring(start, end).all {
                it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
            }
            builder.removeSpan(span)
            builder.setSpan(object : URLSpanReplacement(url) {
                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    if (isCommitHash) {
                        ds.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                        ds.isUnderlineText = false
                    }
                }

                override fun onClick(widget: View) {
                    if (url != null && url.startsWith("tg://", ignoreCase = true)) {
                        dismiss()
                    }
                    super.onClick(widget)
                }
            }, start, end, flags)
        }

        return builder
    }

    private fun sliceEntities(
        entities: ArrayList<TLRPC.MessageEntity>?,
        start: Int,
        end: Int,
    ): ArrayList<TLRPC.MessageEntity> {
        val out = arrayListOf<TLRPC.MessageEntity>()
        if (entities == null) return out
        for (entity in entities) {
            if (entity.offset + entity.length <= start) continue
            if (entity.offset >= end) continue
            val clippedStart = maxOf(entity.offset, start)
            val clippedEnd = minOf(entity.offset + entity.length, end)
            val cloned = InuUtils.cloneTLObject(entity, TLRPC.MessageEntity::TLdeserialize) ?: continue
            cloned.offset = clippedStart - start
            cloned.length = clippedEnd - clippedStart
            out.add(cloned)
        }
        return out
    }

    private fun shiftEntities(
        entities: ArrayList<TLRPC.MessageEntity>?,
        shift: Int,
    ): ArrayList<TLRPC.MessageEntity> {
        val out = arrayListOf<TLRPC.MessageEntity>()
        if (entities == null) return out
        for (entity in entities) {
            val newOffset = entity.offset + shift
            if (newOffset >= 0) {
                entity.offset = newOffset
                out.add(entity)
            }
        }
        return out
    }

    private fun makeButton(
        textRes: Int,
        background: Drawable,
        textColor: Int,
        bold: Boolean,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = LocaleController.getString(textRes)
        isAllCaps = false
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        setTextColor(textColor)
        if (bold) typeface = AndroidUtilities.bold()
        this.background = background
        setOnClickListener { onClick() }
    }

    private fun updateLayout() {
        val child = linearLayout.getChildAt(0) ?: return
        child.getLocationInWindow(location)
        val top = location[1] - AndroidUtilities.dp(24f)
        val newOffset = top.coerceAtLeast(0)
        if (scrollOffsetY != newOffset) {
            scrollOffsetY = newOffset
            scrollView.invalidate()
        }
    }

    override fun canDismissWithSwipe(): Boolean = false

    override fun onOpenAnimationEnd() {
        super.onOpenAnimationEnd()
        UpdateHelper.revealPendingUpdate()
    }
}
