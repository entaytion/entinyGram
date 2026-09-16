package desu.inugram.helpers.pillstack.pills

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import desu.inugram.helpers.pillstack.PillType
import desu.inugram.helpers.security.GhostHelper
import desu.inugram.ui.settings.GhostModeSettingsActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedTextView
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScaleStateListAnimator
import org.telegram.ui.LaunchActivity

// entiny: tap toggles Ghost Mode, long-press opens its settings screen. Wired to our own GhostHelper, not a re-implementation.
@SuppressLint("ViewConstructor")
class GhostPill(context: Context, resourcesProvider: Theme.ResourcesProvider?) :
    BasePill(context, resourcesProvider), NotificationCenter.NotificationCenterDelegate {

    private val layout = LinearLayout(context)
    private val iconView = ImageView(context)
    private val textView = AnimatedTextView(context, true, true, true)
    private var lastAccount = 0

    init {
        layout.orientation = LinearLayout.HORIZONTAL
        layout.gravity = Gravity.CENTER
        layout.minimumWidth = AndroidUtilities.dp(48f)
        layout.setPadding(AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(10f), 0)
        addView(
            layout, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, 28,
                (if (LocaleController.isRTL) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL
            )
        )

        iconView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        iconView.setImageResource(R.drawable.inu_ghost)
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0f, 0f, 2f, 0f))

        textView.setTextSize(AndroidUtilities.dp(13f).toFloat())
        textView.setIncludeFontPadding(false)
        textView.setTypeface(AndroidUtilities.bold())
        textView.adaptWidth = true
        layout.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL))

        setLoadingTargetView(layout)
        updateColors()
        ScaleStateListAnimator.apply(layout)
        onUpdateData(false)
    }

    override fun getPillId(): Int = PillType.GHOST.id

    override fun getRefreshInterval(): Long = 0

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        onUpdateData(true)
        lastAccount = UserConfig.selectedAccount
        NotificationCenter.getInstance(lastAccount).addObserver(this, NotificationCenter.mainUserInfoChanged)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        NotificationCenter.getInstance(lastAccount).removeObserver(this, NotificationCenter.mainUserInfoChanged)
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        if (id == NotificationCenter.mainUserInfoChanged) onUpdateData(true)
    }

    override fun onUpdateData(force: Boolean) {
        val text = LocaleController.getString(R.string.InuGhostMode)
        if (force || textView.text?.toString() != text) {
            if (force) animateSizeChange()
            textView.setText(text, force)
        }
        updateColors()
    }

    override fun onPillClicked() {
        val wasActive = GhostHelper.isGhostActive()
        GhostHelper.toggleGhostMode()
        onUpdateData(true)
        NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.mainUserInfoChanged)

        val fragment = LaunchActivity.getSafeLastFragment()
        if (fragment != null) {
            BulletinFactory.of(fragment)
                .createSuccessBulletin(
                    LocaleController.getString(if (wasActive) R.string.InuGhostModeDisabled else R.string.InuGhostModeEnabled)
                )
                .show()
        }
    }

    override fun onPillLongClicked(): Boolean {
        val fragment: BaseFragment = LaunchActivity.getSafeLastFragment() ?: return false
        fragment.presentFragment(GhostModeSettingsActivity())
        return true
    }

    override fun drawableHotspotChanged(x: Float, y: Float) {
        if (loading) return
        super.drawableHotspotChanged(x, y)
        layout.drawableHotspotChanged(x - layout.left, y - layout.top)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(if (loading) false else pressed)
        layout.isPressed = if (loading) false else pressed
    }

    override fun updateColors() {
        val active = GhostHelper.isGhostActive()
        val color = if (active) getThemedColor(Theme.key_windowBackgroundWhiteGreenText)
        else getThemedColor(Theme.key_windowBackgroundWhiteBlackText, 0.75f)
        layout.background = Theme.createSimpleSelectorRoundRectDrawable(
            AndroidUtilities.dp(14f),
            if (Theme.isCurrentThemeDark()) getThemedColor(Theme.key_windowBackgroundWhite) else Theme.multAlpha(color, 0.09f),
            Theme.multAlpha(color, 0.1f)
        )
        textView.setTextColor(color)
        iconView.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY)
        updateLoadingColors()
    }
}
