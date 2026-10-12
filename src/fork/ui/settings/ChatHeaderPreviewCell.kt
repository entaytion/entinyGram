package desu.inugram.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.theme.NonIslandHelper
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ContactsController
import org.telegram.messenger.LiteMode
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BackDrawable
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.ChatAvatarContainer
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode
import org.telegram.ui.Components.chat.ViewPositionWatcher
import org.telegram.ui.Stories.recorder.StoryEntry

@SuppressLint("ViewConstructor")
class ChatHeaderPreviewCell(context: Context, private val fragment: BaseFragment) : FrameLayout(context) {

    private val resourcesProvider = fragment.resourceProvider
    private val positionWatcher = ViewPositionWatcher(this)
    private val backgroundColorSource = BlurredBackgroundSourceColor()
    private val wallpaperSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        BlurredBackgroundSourceRenderNode(backgroundColorSource).apply {
            setUnderSource(backgroundColorSource)
            setBlur(dp(12f).toFloat())
        }
    } else null

    init {
        setWillNotDraw(false)
        clipChildren = false
        contentDescription = LocaleController.getString(R.string.InuChatHeaderSettings)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        refresh()
    }

    fun refresh() {
        positionWatcher.clear()
        removeAllViews()
        backgroundColorSource.setColor(Theme.getColor(Theme.key_chat_wallpaper, resourcesProvider))
        val bar = ActionBar(context, resourcesProvider).apply {
            setOccupyStatusBar(false)
            setItemsColor(Theme.getColor(Theme.key_actionBarDefaultIcon, resourcesProvider), false)
            setItemsBackgroundColor(Theme.getColor(Theme.key_actionBarDefaultSelector, resourcesProvider), false)
            setCastShadows(false)
            setBackButtonDrawable(BackDrawable(false))
            inu_nonIsland = NonIslandHelper.chatElements()
        }
        val menu = bar.createMenu()
        val moreItem = menu.addItem(0, R.drawable.ic_ab_other)
        moreItem.iconView.visibility = if (InuUtils.chatAvatarInMenuSlot()) INVISIBLE else VISIBLE
        moreItem.setShowSubmenuByMove(false)

        val header = object : ChatAvatarContainer(context, fragment, false, resourcesProvider) {
            override fun inu_isRealChatHeader() = true
            override fun useAnimatedSubtitle() = true
        }
        header.setActionBar(bar)
        bar.setChatAvatarContainer(header)
        header.setOccupyStatusBar(false)
        header.allowDrawStories = false
        header.allowShorterStatus = true
        header.clipChildren = false
        val user = UserConfig.getInstance(fragment.currentAccount).currentUser
        header.setUserAvatar(user, true)
        header.avatarImageView.visibility = if (InuUtils.hideChatAvatar()) GONE else VISIBLE
        header.setTitle(user?.let { ContactsController.formatName(it.first_name, it.last_name) }
            ?.takeIf { it.isNotBlank() } ?: LocaleController.getString(R.string.AppName))
        header.setSubtitle(LocaleController.getString(R.string.Online))
        bar.addView(header, 0, LayoutHelper.createFrame(
            LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT.toFloat(), Gravity.TOP or Gravity.LEFT,
            52f, 0f, 52f, 0f,
        ))
        menu.bringToFront()

        val factory = BlurredBackgroundDrawableViewFactory(wallpaperSource ?: backgroundColorSource)
        factory.setSourceRootView(positionWatcher, this)
        factory.setLiquidGlassEffectAllowed(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS))
        bar.setupGlass(factory, BlurredBackgroundProviderImpl.topPanelChatActivity(resourcesProvider))
        header.setGlassMode()
        bar.setIOSUnreadBadgeAvailable(true)
        bar.backButtonImageView.checkUnreadView(12)
        bar.updateBackPillWidth(false)
        bar.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 70, Gravity.CENTER))
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(116f), MeasureSpec.EXACTLY))
    }

    override fun onInterceptTouchEvent(event: MotionEvent) = true

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawWallpaper(canvas)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && width > 0 && height > 0) {
            wallpaperSource?.let { source ->
                val recording = source.beginRecording(width, height)
                drawWallpaper(recording)
                source.endRecording()
            }
        }
        if (Theme.wallpaperLoadTask != null) postInvalidateOnAnimation()
    }

    private fun drawWallpaper(canvas: Canvas) {
        canvas.drawColor(Theme.getColor(Theme.key_chat_wallpaper, resourcesProvider))
        StoryEntry.drawBackgroundDrawable(canvas, Theme.getCachedWallpaperNonBlocking(), width, height)
    }
}