package desu.inugram.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import androidx.core.content.ContextCompat
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.AndroidUtilities.dpf2
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ActionBar.ThemeBind
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl
import org.telegram.utils.glass.GlassEngine
import org.telegram.utils.glass.source.GlassSourceColor
import org.telegram.utils.glass.source.GlassSourceRenderNode

internal class GlassPreviewCell(
    context: Context,
    private val resourcesProvider: Theme.ResourcesProvider?,
) : View(context) {
    private val fallback = GlassSourceColor()
    private val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) GlassSourceRenderNode(fallback) else null
    private val factory = BlurredBackgroundDrawableViewFactory(source ?: fallback, source ?: fallback, fallback).apply {
        setLiquidGlassEffectAllowed(true)
    }
    private val header = factory.create(this, BlurredBackgroundProviderImpl.topPanelChatActivity(resourcesProvider)).apply {
        setRadius(dpf2(18f))
    }
    private val bottomBar = factory.create(this, BlurredBackgroundProviderImpl.inu_mainTabs(resourcesProvider, true)).apply {
        setRadius(dpf2(24f))
    }
    private val icons = intArrayOf(R.drawable.msg_contacts, R.drawable.msg_discussion, R.drawable.msg_settings).map {
        ContextCompat.getDrawable(context, it)?.mutate()
    }
    private val scene = RectF()
    private val clip = Path()
    private val wallpaperBounds = Rect()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val title = getString(R.string.NewThemePreviewName)
    private val incoming = getString(R.string.NewThemePreviewReply2)
    private val outgoing = getString(R.string.NewThemePreviewLine1)
    private var lastWallpaper: Drawable? = null
    private var lastThemeGeneration = -1
    private var lastBlurRadius = -1f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(dp(186f), heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inset = dp(16f)
        scene.set(inset.toFloat(), 0f, (w - inset).toFloat(), (h - dp(8f)).toFloat())
        clip.rewind()
        if (scene.isEmpty) return
        clip.addRoundRect(scene, dpf2(16f), dpf2(16f), Path.Direction.CW)
        val headerHalf = minOf(dp(76f), scene.width().toInt() / 2 - dp(8f))
        header.setBounds(w / 2 - headerHalf, dp(10f), w / 2 + headerHalf, dp(46f))
        bottomBar.setBounds(inset + dp(8f), scene.bottom.toInt() - dp(56f), w - inset - dp(8f), scene.bottom.toInt() - dp(8f))
    }

    fun refreshGlass() {
        header.updateColors()
        header.invalidateDisplayList()
        bottomBar.updateColors()
        bottomBar.invalidateDisplayList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (scene.width() < dp(48f) || scene.height() < dp(88f)) return
        val wallpaper = Theme.getCachedWallpaperNonBlocking()
        val themeGeneration = ThemeBind.generation()
        fallback.setColor(color(Theme.key_windowBackgroundWhite))
        canvas.save()
        canvas.clipPath(clip)
        drawScene(canvas, wallpaper)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && source != null && canvas.isHardwareAccelerated) {
            if (wallpaper !== lastWallpaper || themeGeneration != lastThemeGeneration || source.needUpdateDisplayList(width, height)) {
                drawScene(source.beginRecording(width, height), wallpaper)
                source.endRecording()
                source.invalidateDisplayListForDrawables()
                lastWallpaper = wallpaper
                lastThemeGeneration = themeGeneration
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val liquid = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && GlassEngine.getCurrentGlassSettings().isLiquidGlassEnabled
                val radius = dpf2(if (liquid) 7f else 36f) * InuConfig.LIQUID_GLASS_BLUR.value.coerceIn(10, 200) / 100f
                if (radius != lastBlurRadius) {
                    source.setBlur(radius)
                    lastBlurRadius = radius
                }
            }
        }

        header.draw(canvas)
        bottomBar.draw(canvas)
        textPaint.color = color(Theme.key_glass_defaultIcon)
        textPaint.textSize = dpf2(14f) * resources.configuration.fontScale
        val headerText = TextUtils.ellipsize(title, textPaint, (header.bounds.width() - dp(16f)).coerceAtLeast(0).toFloat(), TextUtils.TruncateAt.END).toString()
        canvas.drawText(headerText, header.bounds.exactCenterX() - textPaint.measureText(headerText) / 2f, header.bounds.exactCenterY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)

        val tabWidth = bottomBar.bounds.width() / icons.size
        val iconHalf = minOf(dp(11f), tabWidth / 3)
        val selectedLeft = bottomBar.bounds.left + tabWidth + dpf2(4f)
        val selectedRight = bottomBar.bounds.left + tabWidth * 2 - dpf2(4f)
        paint.color = Theme.multAlpha(color(Theme.key_glass_tabSelected), 0.09f)
        canvas.drawRoundRect(selectedLeft, bottomBar.bounds.top + dpf2(4f), selectedRight, bottomBar.bounds.bottom - dpf2(4f), dpf2(20f), dpf2(20f), paint)
        icons.forEachIndexed { index, icon ->
            val x = bottomBar.bounds.left + index * tabWidth + tabWidth / 2
            val y = bottomBar.bounds.centerY()
            icon?.setBounds(x - iconHalf, y - iconHalf, x + iconHalf, y + iconHalf)
            icon?.setTint(color(if (index == 1) Theme.key_glass_tabSelectedText else Theme.key_glass_tabUnselected))
            icon?.draw(canvas)
        }
        canvas.restore()
    }

    private fun drawScene(canvas: Canvas, wallpaper: Drawable?) {
        paint.color = fallback.color
        canvas.drawRect(scene, paint)
        wallpaper?.let {
            it.copyBounds(wallpaperBounds)
            it.setBounds(scene.left.toInt(), 0, scene.right.toInt(), scene.bottom.toInt())
            it.draw(canvas)
            it.setBounds(wallpaperBounds)
        }
        drawBubble(canvas, incoming, scene.height() * 0.18f, false)
        drawBubble(canvas, outgoing, scene.height() * 0.42f, true)
        drawBubble(canvas, incoming, scene.height() * 0.70f, false)
    }

    private fun drawBubble(canvas: Canvas, text: CharSequence, top: Float, outgoing: Boolean) {
        val inset = dpf2(10f)
        val alignRight = outgoing != (layoutDirection == LAYOUT_DIRECTION_RTL)
        textPaint.textSize = dpf2(14f) * resources.configuration.fontScale
        textPaint.color = color(if (outgoing) Theme.key_chat_messageTextOut else Theme.key_chat_messageTextIn)
        val label = TextUtils.ellipsize(text, textPaint, (scene.width() - inset * 4).coerceAtLeast(0f), TextUtils.TruncateAt.END).toString()
        val bubbleWidth = textPaint.measureText(label) + inset * 2
        val left = if (alignRight) scene.right - inset - bubbleWidth else scene.left + inset
        val bubbleHeight = maxOf(dpf2(30f), textPaint.descent() - textPaint.ascent() + dpf2(8f))
        paint.color = color(if (outgoing) Theme.key_chat_outBubble else Theme.key_chat_inBubble)
        canvas.drawRoundRect(left, top, left + bubbleWidth, top + bubbleHeight, dpf2(12f), dpf2(12f), paint)
        canvas.drawText(label, left + inset, top + (bubbleHeight - textPaint.ascent() - textPaint.descent()) / 2f, textPaint)
    }

    private fun color(key: Int) = Theme.getColor(key, resourcesProvider)
}
