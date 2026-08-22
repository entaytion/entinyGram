package desu.inugram.helpers

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import androidx.core.content.ContextCompat
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.SimpleTextView
import org.telegram.ui.ActionBar.Theme

/**
 * Developer badges: entinyGram dev (self), inuGram dev (credit to origin author)
 * and official entinyGram channels. Rendered next to the name in chat headers,
 * message names, dialogs, search results and profiles.
 *
 * Sizing: the badge is a [LayerDrawable] whose intrinsic size must match the target
 * context (SimpleTextView and ImageSpan both lay out from getIntrinsicWidth/Height,
 * ignoring the bounds set here), so [BadgeDrawable] reports the desired dp size.
 */
object DevBadgesHelper {

    const val ID_ENTINY_DEV = 650849996L
    const val ID_INU_DEV = 1787945512L

    private val ENTINY_USERS = setOf(ID_ENTINY_DEV, 8926481003L)
    private val ENTINY_CHANNELS = setOf(4346771715L, 4319600055L, 4296417802L, 3915376475L)
    private val INU_CHANNELS = setOf(3968318575L, 3752050109L, 3705403809L)

    /** Inline/list badges (message names, dialogs, search rows) — matches Telegram's inline premium star. */
    const val BADGE_SIZE_DP = 14
    /** Chat header title badge. */
    const val HEADER_BADGE_SIZE_DP = 16
    /** Profile name badge — matches the profile name row icon scale. */
    const val PROFILE_BADGE_SIZE_DP = 20

    @JvmStatic
    fun normalizeId(rawId: Long): Long {
        var id = kotlin.math.abs(rawId)
        if (id > 1_000_000_000_000L) {
            id -= 1_000_000_000_000L
        }
        return id
    }

    @JvmStatic
    fun isEntiny(id: Long): Boolean {
        if (id == 0L) return false
        val norm = normalizeId(id)
        return norm in ENTINY_USERS || norm in ENTINY_CHANNELS
    }

    @JvmStatic
    fun isInu(id: Long): Boolean {
        if (id == 0L) return false
        val norm = normalizeId(id)
        return norm == ID_INU_DEV || norm in INU_CHANNELS
    }

    /**
     * Custom ReplacementSpan for developer badges inside text.
     * Unlike standard Android ImageSpan (which aligns to the baseline causing jumpy icons),
     * this span centers the badge vertically around the font's cap-height/line center,
     * perfectly matching how AnimatedEmojiSpan aligns custom emojis.
     */
    class BadgeSpan(
        private val drawable: Drawable,
        private val sizePx: Int
    ) : android.text.style.ReplacementSpan() {
        override fun getSize(paint: android.graphics.Paint, text: CharSequence, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int {
            if (fm != null) {
                val paintFm = paint.fontMetricsInt
                fm.ascent = paintFm.ascent
                fm.descent = paintFm.descent
                fm.top = paintFm.top
                fm.bottom = paintFm.bottom
            }
            return sizePx + AndroidUtilities.dp(8f)
        }

        override fun draw(canvas: android.graphics.Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
            val cy = top + (bottom - top) / 2f
            val halfSide = sizePx / 2f
            val badgeY = (cy - halfSide).toInt()
            val badgeX = x.toInt() + AndroidUtilities.dp(4f)
            drawable.setBounds(badgeX, badgeY, badgeX + sizePx, badgeY + sizePx)
            drawable.draw(canvas)
        }
    }

    /**
     * LayerDrawable that reports the requested intrinsic size. Every consumer
     * (SimpleTextView drawables, ImageSpan, setDrawableBounds) lays out from
     * getIntrinsicWidth/Height, so without this override the badge would render at
     * the vector's raw 20dp in every context.
     */
    private class BadgeDrawable(
        background: GradientDrawable,
        icon: Drawable,
        insetPx: Int,
        private val sizePx: Int
    ) : LayerDrawable(arrayOf(background, icon)) {
        init {
            setLayerInset(1, insetPx, insetPx, insetPx, insetPx)
            setBounds(0, 0, sizePx, sizePx)
        }

        override fun getIntrinsicWidth(): Int = sizePx
        override fun getIntrinsicHeight(): Int = sizePx
    }

    @JvmStatic
    fun badgeResFor(id: Long): Int {
        if (InuConfig.HIDE_DEV_BADGES.value || id == 0L) return 0
        if (isEntiny(id)) return R.drawable.inu_badge_entiny
        if (isInu(id)) return R.drawable.inu_badge_inu
        return 0
    }

    @JvmStatic
    fun badgeResFor(userId: Long, chatId: Long): Int {
        if (InuConfig.HIDE_DEV_BADGES.value) return 0
        if (userId != 0L) {
            val res = badgeResFor(userId)
            if (res != 0) return res
        }
        if (chatId != 0L) {
            val res = badgeResFor(chatId)
            if (res != 0) return res
        }
        return 0
    }

    @JvmStatic
    fun badgeDrawable(context: Context, id: Long): Drawable? =
        badgeDrawable(context, id, 0L, BADGE_SIZE_DP, false)

    @JvmStatic
    fun badgeDrawable(context: Context, userId: Long, chatId: Long): Drawable? =
        badgeDrawable(context, userId, chatId, BADGE_SIZE_DP, false)

    @JvmStatic
    fun badgeDrawable(context: Context, userId: Long, chatId: Long, sizeDp: Int): Drawable? =
        badgeDrawable(context, userId, chatId, sizeDp, false)

    @JvmStatic
    fun badgeDrawable(
        context: Context,
        userId: Long,
        chatId: Long,
        sizeDp: Int,
        forceLight: Boolean
    ): Drawable? {
        val res = badgeResFor(userId, chatId)
        if (res == 0) return null
        val icon = ContextCompat.getDrawable(context, res) ?: return null
        val isLightContent = forceLight || Theme.isCurrentThemeDark()
        icon.setTint(if (isLightContent) 0xFFFFFFFF.toInt() else 0xFF202124.toInt())
        val sizePx = AndroidUtilities.dp(sizeDp.toFloat())
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = sizePx * 0.25f
            setColor(
                when {
                    forceLight -> 0x66000000.toInt()
                    isLightContent -> 0x4DFFFFFF.toInt()
                    else -> 0x24000000.toInt()
                }
            )
        }
        val insetPx = (sizePx * 0.16f).toInt().coerceAtLeast(AndroidUtilities.dp(1.5f))
        val drawable = BadgeDrawable(background, icon, insetPx, sizePx)
        drawable.setBounds(0, 0, sizePx, sizePx)
        return drawable
    }

    @JvmStatic
    fun isBadgeDrawable(drawable: Drawable?): Boolean = drawable is BadgeDrawable

    /**
     * Removes only the developer badge from both right-drawable slots of a title
     * view, leaving stock icons (verified check, premium star, scam, emoji status)
     * untouched. Used to prevent the badge from duplicating across profile header
     * name rows.
     */
    @JvmStatic
    fun clearBadge(titleTextView: SimpleTextView) {
        if (titleTextView.getRightDrawable() is BadgeDrawable) {
            titleTextView.setRightDrawable(null)
            titleTextView.setRightDrawableOnClick(null)
        }
        if (titleTextView.getRightDrawable2() is BadgeDrawable) {
            titleTextView.setRightDrawable2(null)
            titleTextView.setRightDrawable2OnClick(null)
        }
    }

    @JvmStatic
    fun badgeContentDescription(): String = LocaleController.getString(R.string.InuDevBadgeAccess)

    @JvmStatic
    fun showDevInfo(context: Context, userId: Long, chatId: Long) {
        val res = badgeResFor(userId, chatId)
        if (res == 0) return
        val targetId = if (userId != 0L) userId else chatId
        val isInu = res == R.drawable.inu_badge_inu
        val isChannel = (normalizeId(targetId) in ENTINY_CHANNELS || normalizeId(targetId) in INU_CHANNELS || targetId < 0) && normalizeId(targetId) !in ENTINY_USERS
        val title = when {
            isChannel && isInu -> LocaleController.getString(R.string.InuDevBadgeInuChannel)
            isChannel -> LocaleController.getString(R.string.InuDevBadgeChannel)
            isInu -> LocaleController.getString(R.string.InuDevBadgeInu)
            else -> LocaleController.getString(R.string.InuDevBadgeEntiny)
        }
        val info = when {
            isChannel && isInu -> LocaleController.getString(R.string.InuDevBadgeInuChannelInfo)
            isChannel -> LocaleController.getString(R.string.InuDevBadgeChannelInfo)
            isInu -> LocaleController.getString(R.string.InuDevBadgeInuInfo)
            else -> LocaleController.getString(R.string.InuDevBadgeEntinyInfo)
        }
        org.telegram.ui.Components.BulletinFactory.global()
            .createSimpleBulletin(R.raw.info, title, info)
            .show()
    }

    /**
     * Appends the developer badge as an inline BadgeSpan to the text view,
     * maintaining exact vertical centering alongside normal text and AnimatedEmojiSpans.
     */
    @JvmStatic
    fun appendInlineBadge(
        titleTextView: SimpleTextView,
        userId: Long,
        chatId: Long,
        sizeDp: Int,
        forceLight: Boolean
    ) {
        val badge = badgeDrawable(titleTextView.context, userId, chatId, sizeDp, forceLight) ?: return
        val base = titleTextView.text ?: return
        val sb = SpannableStringBuilder(base)
        val spans = sb.getSpans(0, sb.length, BadgeSpan::class.java)
        for (span in spans) {
            val s = sb.getSpanStart(span)
            val e = sb.getSpanEnd(span)
            sb.removeSpan(span)
            if (s >= 0 && e <= sb.length && e > s) {
                sb.delete(s, e)
            }
        }
        val start = sb.length
        sb.append(" ")
        val sizePx = AndroidUtilities.dp(sizeDp.toFloat())
        sb.setSpan(BadgeSpan(badge, sizePx), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        titleTextView.setText(sb)
    }

    @JvmStatic
    fun appendInlineBadge(titleTextView: SimpleTextView, userId: Long, chatId: Long, sizeDp: Int) {
        appendInlineBadge(titleTextView, userId, chatId, sizeDp, false)
    }

    @JvmStatic
    fun appendInlineBadge(titleTextView: SimpleTextView, userId: Long, chatId: Long) {
        appendInlineBadge(titleTextView, userId, chatId, BADGE_SIZE_DP, false)
    }

    @JvmStatic
    fun applyTitleBadge(titleTextView: SimpleTextView, userId: Long, chatId: Long) {
        appendInlineBadge(titleTextView, userId, chatId, HEADER_BADGE_SIZE_DP, false)
    }

    /**
     * Deterministic slot allocation with strict priority:
     * - dev-badge NEVER evicts stock verified check, emoji status or premium star.
     * - If both slots are occupied by stock drawables (hasStatus && hasVerified),
     *   the badge is rendered inline after the name using [appendInlineBadge].
     * - If only status is occupied, badge goes to Slot 2 (rightDrawable2).
     * - If only verified is occupied (or both free), badge goes to Slot 1 (rightDrawable).
     */
    @JvmStatic
    fun applyRightBadge(
        titleTextView: SimpleTextView,
        userId: Long,
        chatId: Long,
        hasStatus: Boolean,
        hasVerified: Boolean,
        sizeDp: Int,
        forceLight: Boolean
    ) {
        clearBadge(titleTextView)
        val badge = badgeDrawable(titleTextView.context, userId, chatId, sizeDp, forceLight) ?: return

        when {
            hasStatus && hasVerified -> {
                appendInlineBadge(titleTextView, userId, chatId, sizeDp, forceLight)
            }
            hasStatus -> {
                titleTextView.setRightDrawable2(badge)
                titleTextView.setRightDrawable2OnClick { showDevInfo(titleTextView.context, userId, chatId) }
            }
            hasVerified -> {
                titleTextView.setRightDrawable(badge)
                titleTextView.setRightDrawableOnClick { showDevInfo(titleTextView.context, userId, chatId) }
            }
            else -> {
                titleTextView.setRightDrawable(badge)
                titleTextView.setRightDrawableOnClick { showDevInfo(titleTextView.context, userId, chatId) }
            }
        }
    }

    @JvmStatic
    fun applyRightBadge(titleTextView: SimpleTextView, userId: Long, chatId: Long, hasStatus: Boolean, hasVerified: Boolean, sizeDp: Int) {
        applyRightBadge(titleTextView, userId, chatId, hasStatus, hasVerified, sizeDp, false)
    }

    @JvmStatic
    fun applyRightBadge(titleTextView: SimpleTextView, userId: Long, chatId: Long, hasStatus: Boolean, hasVerified: Boolean) {
        applyRightBadge(titleTextView, userId, chatId, hasStatus, hasVerified, BADGE_SIZE_DP, false)
    }

    @JvmStatic
    fun applyRightBadge(titleTextView: SimpleTextView, userId: Long, chatId: Long, sizeDp: Int) {
        applyRightBadge(titleTextView, userId, chatId, false, false, sizeDp, false)
    }

    @JvmStatic
    fun applyRightBadge(titleTextView: SimpleTextView, userId: Long, chatId: Long) {
        applyRightBadge(titleTextView, userId, chatId, false, false, BADGE_SIZE_DP, false)
    }

    @JvmStatic
    fun appendBadge(context: Context, text: CharSequence, userId: Long, chatId: Long): CharSequence =
        appendBadge(context, text, userId, chatId, BADGE_SIZE_DP)

    @JvmStatic
    fun appendBadge(context: Context, text: CharSequence, userId: Long, chatId: Long, sizeDp: Int): CharSequence {
        val badge = badgeDrawable(context, userId, chatId, sizeDp, false) ?: return text
        val result = SpannableStringBuilder(text)
        val start = result.length
        result.append(" ")
        val sizePx = AndroidUtilities.dp(sizeDp.toFloat())
        result.setSpan(BadgeSpan(badge, sizePx), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return result
    }

    @JvmStatic
    fun applyChatTitleBadge(titleTextView: SimpleTextView, dialogId: Long) {
        applyTitleBadge(
            titleTextView,
            if (dialogId > 0) dialogId else 0L,
            if (dialogId < 0) dialogId else 0L,
        )
    }
}
