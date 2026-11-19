package desu.inugram.helpers.chat

import android.graphics.Canvas
import android.view.View
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.utils.DrawableUtils
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed

class IosInputGlass {
    private var factory: BlurredBackgroundDrawableViewFactory? = null
    private var colorProvider: BlurredBackgroundColorProviderThemed? = null
    private var whiteSendProvider: BlurredBackgroundColorProviderThemed? = null
    private var accentSendProvider: BlurredBackgroundColorProviderThemed? = null
    private var currentSendProvider: BlurredBackgroundColorProviderThemed? = null

    @JvmField var fieldPill: BlurredBackgroundDrawable? = null
    @JvmField var attach: BlurredBackgroundDrawable? = null
    @JvmField var topView: BlurredBackgroundDrawable? = null
    @JvmField var send: BlurredBackgroundDrawable? = null
    @JvmField var voice: BlurredBackgroundDrawable? = null
    @JvmField var done: BlurredBackgroundDrawable? = null
    @JvmField var emoji: BlurredBackgroundDrawable? = null
    @JvmField var expandStickers: BlurredBackgroundDrawable? = null
    @JvmField var cancelBot: BlurredBackgroundDrawable? = null
    @JvmField var sendSlotAttach: BlurredBackgroundDrawable? = null
    @JvmField var ai: BlurredBackgroundDrawable? = null
    @JvmField var rich: BlurredBackgroundDrawable? = null
    private var recordDelete: BlurredBackgroundDrawable? = null

    fun setFactory(
        factory: BlurredBackgroundDrawableViewFactory?,
        colorProvider: BlurredBackgroundColorProviderThemed?,
        whiteSendProvider: BlurredBackgroundColorProviderThemed?,
        accentSendProvider: BlurredBackgroundColorProviderThemed?,
    ) {
        this.factory = factory
        this.colorProvider = colorProvider
        this.whiteSendProvider = whiteSendProvider
        this.accentSendProvider = accentSendProvider
        send = null
        recordDelete = null
    }

    fun sendColorProvider(): BlurredBackgroundColorProviderThemed? =
        ActionButtonStyle.resolveBubbleColorProvider(whiteSendProvider, colorProvider, accentSendProvider)

    fun create(field: View, root: View, sendSlot: View, textField: View) {
        val factory = factory ?: return
        val provider = colorProvider ?: return
        if (send != null) return
        val sendProvider = sendColorProvider()
        currentSendProvider = sendProvider
        fieldPill = factory.bubble(field, provider)
        attach = factory.bubble(field, provider)
        emoji = factory.bubble(field, provider)
        topView = factory.bubble(root, provider)
        voice = factory.bubble(sendSlot, sendProvider)
        expandStickers = factory.bubble(sendSlot, provider)
        cancelBot = factory.bubble(sendSlot, provider)
        sendSlotAttach = factory.bubble(sendSlot, provider)
        done = factory.bubble(textField, sendProvider)
        ai = factory.bubble(textField, provider)
        rich = factory.bubble(textField, provider)
        send = factory.bubble(sendSlot, sendProvider)
    }

    fun recordDelete(panel: View): BlurredBackgroundDrawable? {
        if (recordDelete == null && IosInputHelper.isAppearance()) {
            val factory = factory ?: return null
            val provider = colorProvider ?: return null
            recordDelete = factory.bubble(panel, provider)
        }
        return recordDelete
    }

    fun syncSendColor(): BlurredBackgroundColorProviderThemed? {
        if (colorProvider == null) return null
        val target = sendColorProvider()
        if (send != null && target !== currentSendProvider) {
            currentSendProvider = target
            send?.setColorProvider(target)
            voice?.setColorProvider(target)
            done?.setColorProvider(target)
        }
        return target
    }

    private fun BlurredBackgroundDrawableViewFactory.bubble(view: View, provider: BlurredBackgroundColorProviderThemed?): BlurredBackgroundDrawable =
        create(view, provider).setRadius(AndroidUtilities.dp(RADIUS_DP.toFloat()).toFloat())

    companion object {
        private const val RADIUS_DP = 22

        @JvmStatic
        fun drawCorner(canvas: Canvas, bubble: BlurredBackgroundDrawable?, right: Int, bottom: Int, sizeDp: Int, alpha: Float, scale: Float) {
            if (bubble == null) return
            val size = AndroidUtilities.dp(sizeDp.toFloat())
            val inset = IosInputHelper.bubbleInsetPx()
            bubble.setBounds(right - size + inset, bottom - size + inset, right - inset, bottom - inset)
            bubble.alpha = (255 * alpha).toInt()
            DrawableUtils.drawWithScale(canvas, bubble, scale)
        }

        @JvmStatic
        fun drawTopView(canvas: Canvas, bubble: BlurredBackgroundDrawable?, child: View) {
            if (bubble == null || child.visibility != View.VISIBLE || child.alpha <= 0) return
            val translationY = child.translationY.toInt()
            bubble.setRadius(child.height / 2f)
            bubble.setBounds(child.left, child.top + translationY, child.right, child.bottom + translationY)
            bubble.alpha = (255 * child.alpha).toInt()
            bubble.draw(canvas)
        }
    }
}
