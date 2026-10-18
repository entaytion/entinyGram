package desu.inugram.helpers.pillstack.pills

import android.annotation.SuppressLint
import android.content.Context
import desu.inugram.helpers.pillstack.PillType
import desu.inugram.helpers.security.GhostHelper
import desu.inugram.ui.settings.GhostModeSettingsActivity
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.LaunchActivity

// entiny: tap toggles Ghost Mode, long-press opens its settings screen. Wired to our own GhostHelper, not a re-implementation.
@SuppressLint("ViewConstructor")
class GhostPill(context: Context, resourcesProvider: Theme.ResourcesProvider?) :
    BasePill(context, resourcesProvider), NotificationCenter.NotificationCenterDelegate {

    private var lastAccount = 0

    init {
        setupPill(R.drawable.inu_ghost, startPaddingDp = 8f, endPaddingDp = 10f, iconGapDp = 2f)
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

    override fun onPillLongClicked(): Boolean = showPillMenu {
        add(R.drawable.inu_ghost, LocaleController.getString(R.string.InuGhostMode)) {
            LaunchActivity.getSafeLastFragment()?.presentFragment(GhostModeSettingsActivity())
        }
        addSettingsEntry()
    }

    override fun updateColors() = applyPillColors(if (GhostHelper.isGhostActive()) activeAccent() else defaultAccent())
}
