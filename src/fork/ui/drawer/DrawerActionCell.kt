@file:Suppress("DEPRECATION")

package desu.inugram.ui.drawer

import android.content.Context
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import desu.inugram.helpers.dialogs.DrawerM3SectionsHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DocumentObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.FilterCreateActivity

class DrawerActionCell(context: Context) : FrameLayout(context) {

    private val imageView: BackupImageView
    private val textView: TextView
    private var currentId: Int = 0
    private val rect = RectF()

    init {
        val isRTL = LocaleController.isRTL
        val startGravity = if (isRTL) Gravity.RIGHT else Gravity.LEFT
        imageView = BackupImageView(context)
        imageView.setColorFilter(PorterDuffColorFilter(DrawerM3SectionsHelper.menuIconColor(), PorterDuff.Mode.SRC_IN))
        imageView.imageReceiver.setFileLoadingPriority(FileLoader.PRIORITY_HIGH)

        textView = TextView(context)
        DrawerM3SectionsHelper.styleMenuLabel(textView, 15f, AndroidUtilities.bold())
        textView.gravity = Gravity.CENTER_VERTICAL or startGravity
        addView(imageView, LayoutHelper.createFrame(24, 24f, startGravity or Gravity.CENTER_VERTICAL, if (isRTL) 0f else 16f, 0f, if (isRTL) 16f else 0f, 0f))
        addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat(), startGravity or Gravity.TOP, if (isRTL) 16f else 56f, 0f, if (isRTL) 56f else 16f, 0f))

        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val error = currentId == 8 && MessagesController.getInstance(UserConfig.selectedAccount).pendingSuggestions.let {
            it.contains("VALIDATE_PHONE_NUMBER") || it.contains("VALIDATE_PASSWORD")
        }
        if (error) {
            val countTop = (measuredHeight - AndroidUtilities.dp(23f)) / 2
            val countWidth = AndroidUtilities.dp(9f)
            val countLeft = measuredWidth - countWidth - AndroidUtilities.dp(25f)

            val x = countLeft - AndroidUtilities.dp(5.5f)
            rect.set(x.toFloat(), countTop.toFloat(), (x + countWidth + AndroidUtilities.dp(14f)).toFloat(), (countTop + AndroidUtilities.dp(23f)).toFloat())
            Theme.chat_docBackPaint.color = Theme.getColor(Theme.key_chats_archiveBackground)
            canvas.drawRoundRect(rect, 11.5f * AndroidUtilities.density, 11.5f * AndroidUtilities.density, Theme.chat_docBackPaint)

            val w = Theme.dialogs_errorDrawable.intrinsicWidth
            val h = Theme.dialogs_errorDrawable.intrinsicHeight
            Theme.dialogs_errorDrawable.setBounds(
                (rect.centerX() - w / 2).toInt(), (rect.centerY() - h / 2).toInt(),
                (rect.centerX() + w / 2).toInt(), (rect.centerY() + h / 2).toInt()
            )
            Theme.dialogs_errorDrawable.draw(canvas)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(DrawerM3SectionsHelper.menuItemHeight(), MeasureSpec.EXACTLY)
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        DrawerM3SectionsHelper.styleMenuLabel(textView, 15f, AndroidUtilities.bold())
    }

    fun setTextAndIcon(id: Int, text: CharSequence, resId: Int) {
        currentId = id
        try {
            textView.text = text
            DrawerM3SectionsHelper.styleMenuLabel(textView, 15f, AndroidUtilities.bold())
            imageView.setColorFilter(PorterDuffColorFilter(DrawerM3SectionsHelper.menuIconColor(), PorterDuff.Mode.SRC_IN))
            imageView.setImageResource(resId)
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    fun setBot(bot: TLRPC.TL_attachMenuBot) {
        currentId = bot.bot_id.toInt()
        try {
            DrawerM3SectionsHelper.styleMenuLabel(textView, 15f, AndroidUtilities.bold())
            textView.text = if (bot.side_menu_disclaimer_needed) applyNewSpan(bot.short_name) else bot.short_name
            val botIcon = MediaDataController.getSideAttachMenuBotIcon(bot)
            if (botIcon != null) {
                val photoSize = FileLoader.getClosestPhotoSizeWithSize(botIcon.icon.thumbs, 24 * 3)
                val svgThumb = DocumentObject.getSvgThumb(botIcon.icon.thumbs, Theme.key_emptyListPlaceholder, 0.2f)
                imageView.setImage(
                    ImageLocation.getForDocument(botIcon.icon), "24_24",
                    ImageLocation.getForDocument(photoSize, botIcon.icon), "24_24",
                    svgThumb ?: context.getDrawable(R.drawable.msg_bot)!!.mutate(),
                    bot
                )
            } else {
                imageView.setImageResource(R.drawable.msg_bot)
            }
        } catch (e: Throwable) {
            FileLog.e(e)
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = TextView::class.java.name
        info.addAction(AccessibilityNodeInfo.ACTION_CLICK)
        info.addAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        info.text = textView.text
    }

    companion object {
        @JvmStatic
        fun applyNewSpan(str: String): CharSequence {
            val ssb = SpannableStringBuilder(str)
            ssb.append("  d")
            val span = FilterCreateActivity.NewSpan(10f)
            span.setColor(Theme.getColor(Theme.key_premiumGradient1))
            ssb.setSpan(span, ssb.length - 1, ssb.length, 0)
            return ssb
        }
    }
}
