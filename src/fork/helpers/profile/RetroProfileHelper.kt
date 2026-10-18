package desu.inugram.helpers.profile

import android.view.View
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ChatObject
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ProfileActivity
import kotlin.math.ceil
import kotlin.math.floor

// entiny: ported from the retro-views plugin (@sophietelle)
object RetroProfileHelper {

    private const val WRAP_CONTENT = -2

    private val dp88: Int get() = AndroidUtilities.dp(88f)
    private val dp24: Int get() = AndroidUtilities.dp(24f)
    private val dp8: Int get() = AndroidUtilities.dp(8f)
    private val dp18: Int get() = AndroidUtilities.dp(18f)
    private val dp38: Int get() = AndroidUtilities.dp(38f)
    private val dp40: Int get() = AndroidUtilities.dp(40f)
    private val dp490: Int get() = AndroidUtilities.dp(490f)
    private val textShift: Float get() = 9 * AndroidUtilities.density
    private val floor11: Float get() = floor((11 * AndroidUtilities.density).toDouble()).toFloat()
    private const val INV42 = 1f / 42f
    private const val SIXTY_OVER_42 = 60f / 42f

    @JvmStatic
    fun isEnabled(p: ProfileActivity): Boolean =
        InuConfig.RETRO_PROFILE.value || RetroSettingsHelper.isRetroSettings(p)

    // entiny: old header stays off during the open-from-avatar transition, which ends in the gallery
    @JvmStatic
    fun useOldHeader(p: ProfileActivity): Boolean = isEnabled(p) && !(p.openAnimationInProgress && p.playProfileAnimation == 2)

    private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    private fun stretchedGrown(p: ProfileActivity, h: Float, top: Float): Float {
        if (h <= dp88) {
            return 0f
        }
        val progress = clamp01(((h - dp88) / (p.listView!!.measuredWidth - top - dp88)) * 3f)
        return 42f * AndroidUtilities.density * ((60f + 42f * progress) * INV42 - SIXTY_OVER_42)
    }

    // entiny: replaces 13.x's header layout, which no longer matches the old one; runs after stock
    @JvmStatic
    fun layoutHeader(p: ProfileActivity) {
        val avatar = p.avatarContainer ?: return
        val listView = p.listView ?: return
        val bar = p.getActionBar() ?: return
        val statusBar = if (bar.occupyStatusBar) AndroidUtilities.statusBarHeight else 0
        val barHeight = ActionBar.getCurrentActionBarHeight().toFloat()
        val h = p.extraHeight

        if (p.isPulledDown) {
            if ((p.expandAnimator != null && p.expandAnimator!!.isRunning) || p.openAnimationInProgress) {
                return
            }
            val top = statusBar + barHeight
            val pulledName = p.nameTextView[1] ?: return
            val pulledOnline = p.onlineTextView[1] ?: return
            pulledName.translationY = top + h - dp38 - pulledName.bottom
            val pulledOnlineY = top + h - dp18 - pulledOnline.bottom
            pulledOnline.translationY = onlineTextY(p, pulledOnlineY)
            p.mediaCounterTextView?.translationY = pulledOnlineY
            return
        }
        if (p.openAnimationInProgress && p.playProfileAnimation == 2) {
            return
        }

        val expandRunning = p.expandAnimator != null && p.expandAnimator!!.isRunning
        val diff = clamp01(h / dp88)

        val avatarY = statusBar + barHeight * 0.5f * (1f + diff) - 21 * AndroidUtilities.density +
            27 * AndroidUtilities.density * diff + bar.translationY
        val avatarX = 64 * AndroidUtilities.density - 47 * AndroidUtilities.density * diff

        // entiny: old avatar is 42dp, grows to 60dp while collapsing and 102dp while stretching into the gallery
        var oldScale = (42 + 18 * diff) * INV42
        val stretching = h > dp88
        if (stretching) {
            val newTop = statusBar + barHeight
            val progress = clamp01(((h - dp88) / (listView.measuredWidth - newTop - dp88)) * 3f)
            oldScale = (60 + 42 * progress) * INV42
        }
        val grown = if (stretching) 42 * AndroidUtilities.density * (oldScale - SIXTY_OVER_42) else 0f
        val scale = oldScale * 0.42f

        if (expandRunning) {
            // entiny: the gallery collapse places the avatar itself, so only pin the scale
            p.avatarScale = scale
            avatar.scaleX = scale
            avatar.scaleY = scale
            return
        }

        avatar.scaleX = scale
        avatar.scaleY = scale
        avatar.alpha = 1f
        p.storyView?.alpha = 1f
        avatar.translationX = avatarX
        avatar.translationY = ceil(avatarY.toDouble()).toFloat()
        p.avatarX = avatarX
        p.avatarY = avatarY
        p.avatarScale = scale

        val extra = 42 * AndroidUtilities.density * oldScale - 42 * AndroidUtilities.density
        p.timeItem?.let {
            it.translationX = avatarX + 16 * AndroidUtilities.density + extra
            it.translationY = avatarY + 15 * AndroidUtilities.density + extra
        }
        p.communityItem?.let {
            it.translationX = avatarX + 28 * AndroidUtilities.density + extra
            it.translationY = avatarY + 26.5f * AndroidUtilities.density + extra
        }
        p.starBgItem?.let {
            it.translationX = avatarX + 28 * AndroidUtilities.density + extra
            it.translationY = avatarY + 24 * AndroidUtilities.density + extra
        }
        p.starFgItem?.let {
            it.translationX = avatarX + 28 * AndroidUtilities.density + extra
            it.translationY = avatarY + 24 * AndroidUtilities.density + extra
        }

        val textX = textShift - 21 * AndroidUtilities.density * diff + grown
        val floorY = floor(avatarY.toDouble()).toFloat()
        val nameY = floorY + AndroidUtilities.dp(1.3f) + 7 * AndroidUtilities.density * diff +
            p.titleAnimationsYDiff * (1f - p.avatarAnimationProgress) + grown * 0.5f
        val onlineY = floorY + dp24 + floor11 * diff + grown * 0.5f
        val nameScale = 1f + 0.12f * diff
        p.nameX = textX
        p.nameY = nameY
        p.onlineX = textX
        p.onlineY = onlineY

        val names = p.nameTextView
        val onlines = p.onlineTextView
        for (a in names.indices) {
            val name = names[a] ?: continue
            name.translationX = textX
            name.translationY = nameY
            name.scaleX = nameScale
            name.scaleY = nameScale
            val online = onlines.getOrNull(a)
            if (online != null) {
                online.translationX = onlineTextX(p, textX)
                online.translationY = onlineTextY(p, onlineY)
            }
        }
        p.mediaCounterTextView?.let {
            it.translationX = textX
            it.translationY = onlineY
        }

        // entiny: 11.14.1 left the text widths alone while opening and while stretching
        if (p.openAnimationInProgress || stretching) {
            p.updateCollectibleHint()
            return
        }
        layoutTextWidths(p, diff, textX)
        p.updateCollectibleHint()
    }

    private fun layoutTextWidths(p: ProfileActivity, diff: Float, textX: Float) {
        val name = p.nameTextView[1] ?: return
        val online = p.onlineTextView[1] ?: return
        val counter = p.mediaCounterTextView ?: return
        val online2 = p.onlineTextView[2] ?: return
        val online3 = p.onlineTextView[3] ?: return

        val viewWidth = if (AndroidUtilities.isTablet()) dp490 else AndroidUtilities.displaySize.x
        var items = 0
        if (p.editItemVisible) items += 48
        if (p.callItemVisible) items += 48
        if (p.videoCallItemVisible) items += 48
        if (p.searchItem != null) items += 48
        val buttonsWidth = AndroidUtilities.dp(118 + 8 + (40 + items * (1f - p.mediaHeaderAnimationProgress)))
        val minWidth = viewWidth - buttonsWidth

        var shrink = 1f
        if (diff != 1f) shrink = (diff * 0.15f) / (1f - diff)
        val keep = if (1f - shrink < 0f) 0f else 1f - shrink
        val width = floor((viewWidth - buttonsWidth * keep - (name.translationX - textShift)).toDouble()).toFloat()

        val nameScaleNow = name.scaleX
        val nameText = name.text
        val nameWidth = ((if (nameText == null) 0f else name.paint.measureText(nameText.toString())) * nameScaleNow +
            name.sideDrawablesSize)
        val nameParams = name.layoutParams
        val prevNameWidth = nameParams.width
        var newNameWidth = ceil(nameWidth.toDouble()).toFloat()
        if (width < nameWidth) {
            newNameWidth = Math.max(minWidth.toFloat(), ceil(((width - dp24) / (nameScaleNow + (1.12f - nameScaleNow) * 7f)).toDouble()).toFloat())
        }
        newNameWidth = Math.min((viewWidth - name.x) / nameScaleNow - dp8, newNameWidth)
        nameParams.width = floor(newNameWidth.toDouble()).toInt()
        if (nameParams.width != prevNameWidth) name.requestLayout()

        val onlineText = online.text
        val onlineWidth = (if (onlineText == null) 0f else online.paint.measureText(onlineText.toString())) +
            online.rightDrawableWidth
        val onlineParams = online.layoutParams as android.widget.FrameLayout.LayoutParams
        val counterParams = counter.layoutParams as android.widget.FrameLayout.LayoutParams
        val prevOnlineWidth = onlineParams.width
        val rightMargin = ceil((online.translationX - textShift + dp8 + dp40 * (1f - diff)).toDouble()).toInt()
        onlineParams.rightMargin = rightMargin
        counterParams.rightMargin = rightMargin
        var newOnlineWidth = WRAP_CONTENT
        if (width < onlineWidth) newOnlineWidth = Math.round(width)
        onlineParams.width = newOnlineWidth
        counterParams.width = newOnlineWidth
        if (prevOnlineWidth != newOnlineWidth) {
            online2.layoutParams.width = newOnlineWidth
            online2.requestLayout()
            online3.layoutParams.width = newOnlineWidth
            online3.requestLayout()
            online.requestLayout()
            counter.requestLayout()
        }
    }

    // entiny: 13.x measures the header text with a lerp curve, the old header measured the actual text
    @JvmStatic
    fun skipNeedLayoutText(p: ProfileActivity, startWidth: Int): Boolean {
        if (!isEnabled(p)) return false
        if (startWidth != 0) return false
        if (p.openAnimationInProgress || p.isPulledDown) return false
        return p.expandAnimator == null || !p.expandAnimator!!.isRunning
    }

    @JvmStatic
    fun fixAvatarImageInCenter(p: ProfileActivity): Boolean {
        if (!useOldHeader(p)) return false
        val avatar = p.avatarContainer ?: return false
        if (p.listView == null) return false
        val diff = clamp01(p.extraHeight / dp88)
        var x = 64 * AndroidUtilities.density - 47 * AndroidUtilities.density * diff
        if (p.isPulledDown || (p.expandAnimator != null && p.expandAnimator!!.isRunning)) {
            x *= (1f - p.currentExpandAnimatorValue)
        }
        p.avatarX = x
        avatar.translationX = x
        return true
    }

    @JvmStatic
    fun refreshNameAndOnlineYBasedOnExpand(p: ProfileActivity, diff: Float, newTop: Float): Boolean {
        if (!useOldHeader(p)) return false
        val bar = p.getActionBar() ?: return false
        if (p.listView == null) return false
        val h = p.extraHeight
        val d = clamp01(h / dp88)

        val statusBar = if (bar.occupyStatusBar) AndroidUtilities.statusBarHeight else 0
        val avatarY = statusBar + ActionBar.getCurrentActionBarHeight() * 0.5f * (1f + d) -
            21 * AndroidUtilities.density + 27 * AndroidUtilities.density * d + bar.translationY
        val top = (statusBar + ActionBar.getCurrentActionBarHeight()).toFloat()
        val grown = stretchedGrown(p, h, top)

        val floorY = floor(avatarY.toDouble()).toFloat()
        val textX = textShift - 21 * AndroidUtilities.density * d + grown
        p.nameX = textX
        p.onlineX = textX
        p.nameY = floorY + AndroidUtilities.dp(1.3f) + 7 * AndroidUtilities.density * d +
            p.titleAnimationsYDiff * (1f - p.avatarAnimationProgress) + grown * 0.5f
        p.onlineY = floorY + dp24 + floor11 * d + grown * 0.5f
        return true
    }

    @JvmStatic
    fun beforeSetAvatarExpandProgress(p: ProfileActivity) {
        val bar = p.getActionBar() ?: return
        val listView = p.listView ?: return
        if (p.openAnimationInProgress && p.playProfileAnimation == 2) return
        val statusBar = if (bar.occupyStatusBar) AndroidUtilities.statusBarHeight else 0
        val h = p.extraHeight
        val diff = clamp01(h / dp88)

        p.avatarY = statusBar + ActionBar.getCurrentActionBarHeight() * 0.5f * (1f + diff) -
            21 * AndroidUtilities.density + 27 * AndroidUtilities.density * diff + bar.translationY
        var oldScale = (42 + 18 * diff) * INV42
        if (h > dp88) {
            val top = (statusBar + ActionBar.getCurrentActionBarHeight()).toFloat()
            val progress = clamp01(((h - dp88) / (listView.measuredWidth - top - dp88)) * 3f)
            oldScale = (60 + 42 * progress) * INV42
        }
        p.avatarScale = oldScale * 0.42f
    }

    @JvmStatic
    fun afterSetAvatarExpandProgress(p: ProfileActivity) {
        val bar = p.getActionBar() ?: return
        if (p.listView == null) return
        if (p.openAnimationInProgress && p.playProfileAnimation == 2) return
        val name = p.nameTextView[1] ?: return
        val online = p.onlineTextView[1] ?: return

        val v = p.currentExpandAnimatorValue
        val u = 1f - v
        val statusBar = if (bar.occupyStatusBar) AndroidUtilities.statusBarHeight else 0
        val newTop = statusBar + ActionBar.getCurrentActionBarHeight().toFloat()
        val h = p.extraHeight

        val nameXEnd = 18 * AndroidUtilities.density - name.left
        val nameYEnd = newTop + h - dp38 - name.bottom
        val nameCx = dp8 + p.nameX + (nameXEnd - p.nameX) * 0.5f
        val nameCy = dp8 + p.nameY + (nameYEnd - p.nameY) * 0.5f
        name.translationX = u * u * p.nameX + 2 * u * v * nameCx + v * v * nameXEnd
        name.translationY = u * u * p.nameY + 2 * u * v * nameCy + v * v * nameYEnd

        val onlineXEnd = 16 * AndroidUtilities.density - online.left
        val onlineYEnd = newTop + h - dp18 - online.bottom
        val onlineCx = dp8 + p.onlineX + (onlineXEnd - p.onlineX) * 0.5f
        val onlineCy = dp8 + p.onlineY + (onlineYEnd - p.onlineY) * 0.5f
        val onlineX = u * u * p.onlineX + 2 * u * v * onlineCx + v * v * onlineXEnd
        val onlineY = u * u * p.onlineY + 2 * u * v * onlineCy + v * v * onlineYEnd
        online.translationX = onlineTextX(p, onlineX)
        online.translationY = onlineTextY(p, onlineY)
        p.mediaCounterTextView?.let {
            it.translationX = onlineX
            it.translationY = onlineY
        }

        if (h > dp88) {
            name.pivotY = v * name.measuredHeight
            name.scaleX = 1.12f + 0.55f * v
            name.scaleY = 1.12f + 0.55f * v
        }
    }

    @JvmStatic
    fun onlineTextX(p: ProfileActivity, x: Float): Float {
        if (!useOldHeader(p)) {
            return x
        }
        return x + p.customPhotoOffset
    }

    @JvmStatic
    fun onlineTextY(p: ProfileActivity, y: Float): Float {
        if (!useOldHeader(p)) {
            return y
        }
        return y + p.getRatingViewTranslationYOffset()
    }

    @JvmStatic
    fun updateTopViewColor(p: ProfileActivity, progress: Float): Boolean {
        val top = p.topView ?: return false
        if (p.playProfileAnimation == 2 && p.avatarColor != 0) return false
        var from = p.actionBarAnimationColorFrom
        if (from == 0) from = p.getThemedColor(Theme.key_actionBarDefault)
        if (org.telegram.messenger.SharedConfig.chatBlurEnabled()) {
            from = androidx.core.graphics.ColorUtils.setAlphaComponent(from, 0)
        }
        top.setBackgroundColor(
            androidx.core.graphics.ColorUtils.blendARGB(from, p.getThemedColor(Theme.key_avatar_backgroundActionBarBlue), progress)
        )
        return true
    }

    // entiny: removes the 12.x header widgets the old header never had
    @JvmStatic
    fun stripHeader(p: ProfileActivity) {
        for (view in arrayOf<View?>(p.actionsView, p.musicView)) {
            val parent = view?.parent as? android.view.ViewGroup ?: continue
            parent.removeView(view)
        }
        p.actionsView = null
        p.musicView = null

        val writeButton = p.writeButton
        if (writeButton != null) {
            val cameraDrawable = p.cameraDrawable
            if (p.imageUpdater != null && cameraDrawable != null) {
                writeButton.setAnimation(cameraDrawable)
            }
            writeButton.visibility = View.VISIBLE
            if (!writeButtonInitiallyVisible(p)) {
                writeButton.tag = 0
                writeButton.scaleX = 0.2f
                writeButton.scaleY = 0.2f
                writeButton.alpha = 0f
            }
        }

        p.listView?.let {
            it.disableSections()
            it.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray))
        }
    }

    private fun writeButtonInitiallyVisible(p: ProfileActivity): Boolean {
        val diff = Math.min(1f, Math.max(0f, p.extraHeight / dp88))
        var visible = diff > 0.2f && !p.searchMode && !p.myProfile && (p.imageUpdater == null || p.setAvatarRow == -1)
        if (visible && p.chatId != 0L) {
            val chat = p.currentChat ?: return false
            val info = p.chatInfo ?: return false
            visible = ChatObject.isChannel(chat) && !chat.megagroup && info.linked_chat_id != 0L
        }
        return visible
    }

    private val WHITE_ROW_TYPES = intArrayOf(
        1, 2, 3, 4, 5, 6, 8, 17, 18, 19, 20, 21, 22, 23, 24, 25, 27, 29, 30, 32, 33
    )

    @JvmStatic
    fun applyRowBackground(view: View, viewType: Int) {
        for (type in WHITE_ROW_TYPES) {
            if (type == viewType) {
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))
                return
            }
        }
    }
}
