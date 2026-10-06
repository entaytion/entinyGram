package desu.inugram.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import desu.inugram.InuConfig
import desu.inugram.helpers.chat.ActionButtonStyle
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LiteMode
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceWrapped
import org.telegram.ui.Components.chat.WallpaperBitmapProvider

class InputBarPreviewCell(
    context: Context,
    private val resourcesProvider: Theme.ResourcesProvider? = null,
) : FrameLayout(context) {

    private val wallpaperBitmapProvider = WallpaperBitmapProvider()
    private var glassFactory: BlurredBackgroundDrawableViewFactory? = null
    private var colorProvider: BlurredBackgroundColorProviderThemed? = null
    private var whiteColorProvider: BlurredBackgroundColorProviderThemed? = null
    private var accentColorProvider: BlurredBackgroundColorProviderThemed? = null
    private val sendCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var oneBlockDrawable: BlurredBackgroundDrawable? = null
    private var capsuleDrawable: BlurredBackgroundDrawable? = null
    private var leftBubbleDrawable: BlurredBackgroundDrawable? = null
    private var rightBubbleDrawable: BlurredBackgroundDrawable? = null

    private val attachIconView: ImageView
    private val emojiIconView: ImageView
    private val sendIconView: ImageView

    private var isPlacementEnabled = false
    private var isAppearanceEnabled = false
    private var isCompactEnabled = false
    private var gapPx = 0

    private var lastWallpaper: Drawable? = null
    private var lastIsBlurEnabled = false
    private var lastIsLiquidGlassEnabled = false
    private var lastDrawnWallpaper: Drawable? = null

    init {
        setWillNotDraw(false)
        clipChildren = false
        setPadding(0, AndroidUtilities.dp(CELL_VERTICAL_PADDING_DP.toFloat()), 0, AndroidUtilities.dp(CELL_BOTTOM_PADDING_DP.toFloat()))

        val iconColor = Theme.getColor(Theme.key_glass_defaultIcon, resourcesProvider)
        val sendColor = Theme.getColor(Theme.key_chat_messagePanelSend, resourcesProvider)

        attachIconView = createIconView(context, R.drawable.msg_input_attach2, iconColor)
        emojiIconView = createIconView(context, R.drawable.smiles_tab_smiles, iconColor)
        sendIconView = createIconView(context, R.drawable.send_plane_24, sendColor)

        buildGlassFactory()
        updateInputBarState()
    }

    private fun createIconView(context: Context, resId: Int, color: Int): ImageView {
        val view = ImageView(context)
        view.setImageResource(resId)
        view.scaleType = ImageView.ScaleType.CENTER
        view.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        addView(view)
        return view
    }

    private fun buildGlassFactory() {
        val wallpaper = Theme.getCachedWallpaperNonBlocking()
        val isBlurEnabled = SharedConfig.chatBlurEnabled() && LiteMode.isEnabled(LiteMode.FLAG_CHAT_BLUR)
        val isLiquidGlassEnabled = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)
        if (glassFactory != null && wallpaper == lastWallpaper && isBlurEnabled == lastIsBlurEnabled && isLiquidGlassEnabled == lastIsLiquidGlassEnabled) {
            return
        }
        lastWallpaper = wallpaper
        lastIsBlurEnabled = isBlurEnabled
        lastIsLiquidGlassEnabled = isLiquidGlassEnabled

        val source = if (isBlurEnabled && wallpaper != null) {
            wallpaperBitmapProvider.updateSourceFromBackgroundViewDrawable(wallpaper)
        } else {
            BlurredBackgroundSourceColor()
        }
        val wrappedSource = BlurredBackgroundSourceWrapped()
        wrappedSource.setSource(source)
        val factory = BlurredBackgroundDrawableViewFactory(wrappedSource)
        glassFactory = factory
        val provider = BlurredBackgroundColorProviderThemed(resourcesProvider, Theme.key_chat_messagePanelBackground)
        colorProvider = provider
        whiteColorProvider = object : BlurredBackgroundColorProviderThemed(resourcesProvider, Theme.key_windowBackgroundWhite) {
            override fun getBackgroundColor(): Int {
                if (!isBlurEnabled) return -0x1
                return if (isLiquidGlassEnabled) -0x26000001 else -0x3d000001
            }
        }
        accentColorProvider = BlurredBackgroundColorProviderThemed(resourcesProvider, Theme.key_chat_messagePanelSend)
        if (!isBlurEnabled) {
            provider.setAlpha(1.0f)
        }
        factory.setLiquidGlassEffectAllowed(isLiquidGlassEnabled)
        oneBlockDrawable = createBubble(factory, provider)
        capsuleDrawable = createBubble(factory, provider)
        leftBubbleDrawable = createBubble(factory, provider)
        rightBubbleDrawable = createBubble(factory, provider)
    }

    private fun createBubble(
        factory: BlurredBackgroundDrawableViewFactory,
        provider: BlurredBackgroundColorProviderThemed,
    ): BlurredBackgroundDrawable {
        val drawable = factory.create(this, provider)
        drawable.setRadius(AndroidUtilities.dp(BUBBLE_RADIUS_DP.toFloat()).toFloat())
        return drawable
    }

    fun updateInputBarState() {
        buildGlassFactory()
        isPlacementEnabled = InuConfig.IOS_INPUT_BUTTON_PLACEMENT.value
        isAppearanceEnabled = InuConfig.IOS_INPUT_APPEARANCE.value
        isCompactEnabled = InuConfig.COMPACT_INPUT_SIZE.value && isAppearanceEnabled
        val gapDp = if (isCompactEnabled) GAP_COMPACT_DP else GAP_NORMAL_DP
        gapPx = AndroidUtilities.dp(gapDp.toFloat())

        val leftIcon = if (isPlacementEnabled) attachIconView else emojiIconView
        val rightIcon = if (isPlacementEnabled) emojiIconView else attachIconView
        val edgeInsetDp = (if (isAppearanceEnabled) gapDp else CELL_VERTICAL_PADDING_DP).toFloat()

        leftIcon.layoutParams = LayoutHelper.createFrame(BAR_HEIGHT_DP.toFloat(), BAR_HEIGHT_DP.toFloat(), Gravity.BOTTOM or Gravity.LEFT, edgeInsetDp, 0f, 0f, 0f)
        rightIcon.layoutParams = LayoutHelper.createFrame(BAR_HEIGHT_DP.toFloat(), BAR_HEIGHT_DP.toFloat(), Gravity.BOTTOM or Gravity.RIGHT, 0f, 0f, (BAR_HEIGHT_DP + edgeInsetDp + gapDp), 0f)
        sendIconView.layoutParams = LayoutHelper.createFrame(BAR_HEIGHT_DP.toFloat(), BAR_HEIGHT_DP.toFloat(), Gravity.BOTTOM or Gravity.RIGHT, 0f, 0f, edgeInsetDp, 0f)

        rightBubbleDrawable?.setColorProvider(
            ActionButtonStyle.resolveBubbleColorProvider(whiteColorProvider, colorProvider, accentColorProvider)
        )
        sendIconView.colorFilter = PorterDuffColorFilter(
            ActionButtonStyle.resolveIconColor(resourcesProvider),
            PorterDuff.Mode.SRC_IN,
        )

        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = AndroidUtilities.dp((BAR_HEIGHT_DP + CELL_VERTICAL_PADDING_DP + CELL_BOTTOM_PADDING_DP).toFloat())
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    override fun dispatchDraw(canvas: Canvas) {
        val wallpaper = Theme.getCachedWallpaperNonBlocking()
        if (wallpaper != null) {
            if (wallpaper != lastDrawnWallpaper) {
                lastDrawnWallpaper = wallpaper
                invalidate()
            }
            wallpaper.setBounds(0, 0, width, height)
            wallpaper.draw(canvas)
        }
        val padding = AndroidUtilities.dp(CELL_VERTICAL_PADDING_DP.toFloat())
        val fieldTop = attachIconView.top
        val fieldBottom = attachIconView.bottom
        if (isAppearanceEnabled) {
            val leftIcon = if (isPlacementEnabled) attachIconView else emojiIconView
            val pillLeft = leftIcon.right + gapPx
            val pillRight = sendIconView.left - gapPx
            capsuleDrawable?.let {
                it.setBounds(pillLeft, fieldTop, pillRight, fieldBottom)
                it.draw(canvas)
            }
            leftBubbleDrawable?.let {
                it.setBounds(leftIcon.left, leftIcon.top, leftIcon.right, leftIcon.bottom)
                it.draw(canvas)
            }
            rightBubbleDrawable?.let {
                // entiny: match the stock circle's inset (drawn with radius -3dp below) so the send
                // button doesn't visually grow when switching from stock to iOS appearance
                val inset = AndroidUtilities.dp(3f)
                it.setBounds(sendIconView.left + inset, sendIconView.top + inset, sendIconView.right - inset, sendIconView.bottom - inset)
                it.draw(canvas)
            }
        } else {
            oneBlockDrawable?.let {
                it.setBounds(padding, fieldTop, width - padding, fieldBottom)
                it.draw(canvas)
            }
            sendCirclePaint.color = ActionButtonStyle.resolveBackgroundColor(resourcesProvider)
            val sendCx = (sendIconView.left + sendIconView.right) / 2f
            val sendCy = (sendIconView.top + sendIconView.bottom) / 2f
            canvas.drawCircle(sendCx, sendCy, (sendIconView.right - sendIconView.left) / 2f - AndroidUtilities.dp(3f), sendCirclePaint)
        }
        super.dispatchDraw(canvas)
    }

    companion object {
        private const val BUBBLE_RADIUS_DP = 22
        private const val BAR_HEIGHT_DP = 44
        private const val GAP_NORMAL_DP = 8
        private const val GAP_COMPACT_DP = 2
        private const val CELL_VERTICAL_PADDING_DP = 12
        private const val CELL_BOTTOM_PADDING_DP = 20
    }
}
