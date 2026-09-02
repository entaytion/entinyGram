package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import desu.inugram.InuConfig
import desu.inugram.helpers.icons.PhosphorIconPack
import desu.inugram.helpers.icons.SolarIconPack
import desu.inugram.helpers.icons.VkIconPack
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

@SuppressLint("ViewConstructor")
class IconPackPreviewCell(context: Context) : FrameLayout(context) {

    private var currentPack: Int = InuConfig.ICON_REPLACEMENT.value

    private val previewImageMap = ArrayList<Pair<ImageView, Int>>()

    init {
        setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(12f), AndroidUtilities.dp(16f), AndroidUtilities.dp(12f))

        val cardLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val cardBg = GradientDrawable().apply {
                cornerRadius = AndroidUtilities.dp(16f).toFloat()
                setColor(Theme.getColor(Theme.key_windowBackgroundGray))
            }
            background = cardBg
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(14f), AndroidUtilities.dp(16f), AndroidUtilities.dp(14f))
        }
        addView(cardLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat()))

        // 1. Message Context Menu Bar Mockup
        val menuBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val menuBg = GradientDrawable().apply {
                cornerRadius = AndroidUtilities.dp(12f).toFloat()
                setColor(Theme.getColor(Theme.key_windowBackgroundWhite))
            }
            background = menuBg
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(8f), AndroidUtilities.dp(10f), AndroidUtilities.dp(8f))
        }

        val menuIcons = listOf(
            R.drawable.menu_reply,
            R.drawable.msg_copy,
            R.drawable.msg_forward,
            R.drawable.msg_pin,
            R.drawable.msg_delete,
        )

        for ((index, iconRes) in menuIcons.withIndex()) {
            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setColorFilter(Theme.getColor(Theme.key_dialogIcon))
            }
            previewImageMap.add(Pair(iv, iconRes))
            val lp = LinearLayout.LayoutParams(0, AndroidUtilities.dp(24f), 1f)
            menuBar.addView(iv, lp)
        }
        cardLayout.addView(menuBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 10f))

        // 2. Chat Input Bar Mockup
        val chatBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val inputBg = GradientDrawable().apply {
                cornerRadius = AndroidUtilities.dp(22f).toFloat()
                setColor(Theme.getColor(Theme.key_windowBackgroundWhite))
            }
            background = inputBg
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(6f), AndroidUtilities.dp(8f), AndroidUtilities.dp(6f))
        }

        val smileIv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(Theme.getColor(Theme.key_chat_messagePanelIcons))
        }
        previewImageMap.add(Pair(smileIv, R.drawable.input_smile))
        chatBar.addView(smileIv, LayoutHelper.createLinear(26, 26, 0f, 0f, 8f, 0f))

        val hintTv = TextView(context).apply {
            text = LocaleController.getString(R.string.TypeMessage)
            setTextColor(Theme.getColor(Theme.key_chat_messagePanelHint))
            textSize = 15f
        }
        chatBar.addView(hintTv, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))

        val attachIv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(Theme.getColor(Theme.key_chat_messagePanelIcons))
        }
        previewImageMap.add(Pair(attachIv, R.drawable.input_attach))
        chatBar.addView(attachIv, LayoutHelper.createLinear(26, 26, 4f, 0f, 6f, 0f))

        val scheduleIv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(Theme.getColor(Theme.key_chat_messagePanelIcons))
        }
        previewImageMap.add(Pair(scheduleIv, R.drawable.input_schedule))
        chatBar.addView(scheduleIv, LayoutHelper.createLinear(26, 26, 4f, 0f, 6f, 0f))

        val sendIv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val sendBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Theme.getColor(Theme.key_chat_messagePanelSend))
            }
            background = sendBg
            setColorFilter(Color.WHITE)
            setPadding(AndroidUtilities.dp(4f), AndroidUtilities.dp(4f), AndroidUtilities.dp(4f), AndroidUtilities.dp(4f))
        }
        previewImageMap.add(Pair(sendIv, R.drawable.ic_send))
        chatBar.addView(sendIv, LayoutHelper.createLinear(30, 30, 4f, 0f, 0f, 0f))

        cardLayout.addView(chatBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0f, 0f, 0f, 10f))

        // 3. Navigation & Settings Showcase Row
        val showcaseRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val showcaseBg = GradientDrawable().apply {
                cornerRadius = AndroidUtilities.dp(12f).toFloat()
                setColor(Theme.getColor(Theme.key_windowBackgroundWhite))
            }
            background = showcaseBg
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(8f), AndroidUtilities.dp(10f), AndroidUtilities.dp(8f))
        }

        val showcaseIcons = listOf(
            R.drawable.msg_saved,
            R.drawable.msg_settings,
            R.drawable.msg2_secret,
            R.drawable.msg_theme,
            R.drawable.msg_calls,
            R.drawable.msg_archive,
        )

        for (iconRes in showcaseIcons) {
            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon))
            }
            previewImageMap.add(Pair(iv, iconRes))
            val lp = LinearLayout.LayoutParams(0, AndroidUtilities.dp(24f), 1f)
            showcaseRow.addView(iv, lp)
        }
        cardLayout.addView(showcaseRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        updateIcons()
    }

    fun setPack(packId: Int) {
        if (currentPack != packId) {
            currentPack = packId
            updateIcons()
        }
    }

    private fun updateIcons() {
        for ((imageView, stockRes) in previewImageMap) {
            val mappedRes = when (currentPack) {
                InuConfig.IconReplacementItem.SOLAR -> SolarIconPack.map(stockRes)
                InuConfig.IconReplacementItem.VKUI -> VkIconPack.map(stockRes)
                InuConfig.IconReplacementItem.PHOSPHOR -> PhosphorIconPack.map(stockRes)
                else -> stockRes
            }
            imageView.setImageDrawable(ContextCompat.getDrawable(context, mappedRes))
        }
    }
}

