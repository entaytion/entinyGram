package desu.inugram.helpers

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.SimpleTextView
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

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

    private val ENTINY_CHANNELS = setOf(4346771715L, 4319600055L, 4296417802L)
    private val INU_CHANNELS = setOf(3968318575L)

    /** Inline/list badges (message names, dialogs, search rows) — matches Telegram's inline premium star. */
    const val BADGE_SIZE_DP = 12
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
        return norm == ID_ENTINY_DEV || norm in ENTINY_CHANNELS
    }

    @JvmStatic
    fun isInu(id: Long): Boolean {
        if (id == 0L) return false
        val norm = normalizeId(id)
        return norm == ID_INU_DEV || norm in INU_CHANNELS
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
        badgeDrawable(context, id, 0L, BADGE_SIZE_DP)

    @JvmStatic
    fun badgeDrawable(context: Context, userId: Long, chatId: Long): Drawable? =
        badgeDrawable(context, userId, chatId, BADGE_SIZE_DP)

    @JvmStatic
    fun badgeDrawable(context: Context, userId: Long, chatId: Long, sizeDp: Int): Drawable? {
        val res = badgeResFor(userId, chatId)
        if (res == 0) return null
        val icon = ContextCompat.getDrawable(context, res) ?: return null
        val dark = Theme.isCurrentThemeDark()
        icon.setTint(if (dark) 0xFFFFFFFF.toInt() else 0xFF202124.toInt())
        val sizePx = AndroidUtilities.dp(sizeDp.toFloat())
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = sizePx * 0.22f
            setColor(if (dark) 0x59FFFFFF.toInt() else 0x2E000000.toInt())
        }
        // Icon fills the pill — a small relative inset keeps it crisp without shrinking the glyph.
        val insetPx = (AndroidUtilities.dp(1f) + sizePx / 8).coerceAtLeast(1)
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
        }
        if (titleTextView.getRightDrawable2() is BadgeDrawable) {
            titleTextView.setRightDrawable2(null)
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
        val isChannel = normalizeId(targetId) in ENTINY_CHANNELS || normalizeId(targetId) in INU_CHANNELS || targetId < 0
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

    @JvmStatic
    private fun showDevInfoSheet(context: Context, badgeRes: Int, title: CharSequence, info: CharSequence) {
        val sheet = BottomSheet(context, false)
        val container = LinearLayout(context)
        container.orientation = LinearLayout.VERTICAL

        val image = ImageView(context)
        image.setImageResource(badgeRes)
        container.addView(
            image,
            LayoutHelper.createLinear(56, 56, Gravity.CENTER_HORIZONTAL or Gravity.TOP, 0f, 20f, 0f, 0f)
        )

        val titleView = TextView(context)
        titleView.text = title
        titleView.textSize = 16f
        titleView.setTypeface(AndroidUtilities.bold())
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
        titleView.gravity = Gravity.CENTER
        container.addView(
            titleView,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 20f, 12f, 20f, 0f)
        )

        val infoView = TextView(context)
        infoView.text = info
        infoView.textSize = 14f
        infoView.setTextColor(Theme.getColor(Theme.key_dialogTextGray2))
        infoView.gravity = Gravity.CENTER
        container.addView(
            infoView,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 20f, 6f, 20f, 0f)
        )

        val okView = TextView(context)
        okView.text = LocaleController.getString(R.string.OK)
        okView.textSize = 14f
        okView.setTextColor(Theme.getColor(Theme.key_dialogTextBlue2))
        okView.gravity = Gravity.CENTER
        okView.setPadding(0, AndroidUtilities.dp(10f), 0, AndroidUtilities.dp(14f))
        okView.setOnClickListener { sheet.dismiss() }
        container.addView(
            okView,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0f, 0f, 0f, 0f)
        )

        sheet.setCustomView(container)
        sheet.show()
    }

    /**
     * Appends the developer badge as an inline ImageSpan to the chat header title so it
     * renders right after the name without conflicting with premium/verified drawables.
     */
    @JvmStatic
    fun applyTitleBadge(titleTextView: SimpleTextView, userId: Long, chatId: Long) {
        val badge = badgeDrawable(titleTextView.context, userId, chatId) ?: return
        val base = titleTextView.text ?: return
        val sb = SpannableStringBuilder(base)
        sb.append(' ')
        val start = sb.length
        sb.append(' ')
        sb.setSpan(ImageSpan(badge, ImageSpan.ALIGN_BASELINE), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        titleTextView.setText(sb)
    }

    @JvmStatic
    fun applyRightBadge(titleTextView: SimpleTextView, userId: Long, chatId: Long, premium: Boolean) {
        applyRightBadge(titleTextView, userId, chatId, premium, HEADER_BADGE_SIZE_DP)
    }

    @JvmStatic
    fun applyRightBadge(titleTextView: SimpleTextView, userId: Long, chatId: Long, premium: Boolean, sizeDp: Int) {
        val badge = badgeDrawable(titleTextView.context, userId, chatId, sizeDp)
        if (badge == null) {
            clearBadge(titleTextView)
            return
        }
        if (premium) {
            titleTextView.setRightDrawable2(badge)
        } else {
            // A recycled action-bar title can retain the previous premium/right-2 drawable.
            // Clear it before placing the developer badge in the primary slot.
            titleTextView.setRightDrawable2(null)
            titleTextView.setRightDrawable(badge)
        }
        titleTextView.setRightDrawableOnClick { showDevInfo(titleTextView.context, userId, chatId) }
    }

    @JvmStatic
    fun appendBadge(context: Context, text: CharSequence, userId: Long, chatId: Long): CharSequence =
        appendBadge(context, text, userId, chatId, BADGE_SIZE_DP)

    @JvmStatic
    fun appendBadge(context: Context, text: CharSequence, userId: Long, chatId: Long, sizeDp: Int): CharSequence {
        val badge = badgeDrawable(context, userId, chatId, sizeDp) ?: return text
        val result = SpannableStringBuilder(text)
        result.append(' ')
        val start = result.length
        result.append(' ')
        result.setSpan(ImageSpan(badge, ImageSpan.ALIGN_BASELINE), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
