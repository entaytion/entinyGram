package desu.inugram.ui.settings

import android.view.View
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class TosSettingsActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuTOS)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asButton(
                BUTTON_BETA_INFO,
                R.drawable.ic_beta_badge,
                LocaleController.getString(R.string.InuBetaFeatureTitle)
            )
        )
        items.add(UItem.asShadow(null))

        items.add(mkSubPageButton(CAT_GHOST_MODE, R.drawable.inu_ghost_filled, LocaleController.getString(R.string.InuGhostMode)))
        items.add(mkSubPageButton(CAT_ANTI_DELETION, R.drawable.inu_tabler_trash_off, LocaleController.getString(R.string.InuAntiDeletion)))
        items.add(mkSubPageButton(CAT_SELF_DESTRUCT, R.drawable.inu_tabler_flame, LocaleController.getString(R.string.InuSelfDestructMedia)))
        items.add(mkSubPageButton(CAT_CONTENT_PROTECTION, R.drawable.inu_tabler_shield_lock, LocaleController.getString(R.string.InuContentProtectionBypass)))
        items.add(mkSubPageButton(CAT_TOS_PROFILE, R.drawable.inu_tabler_crown, LocaleController.getString(R.string.InuTosProfile)))
        items.add(mkSubPageButton(CAT_UNLIMITED_LIMITS, R.drawable.inu_tabler_infinity, LocaleController.getString(R.string.InuUnlimitedLimits)))
        items.add(mkSubPageButton(CAT_STALKER_PACK, R.drawable.inu_tabler_radar, LocaleController.getString(R.string.InuStalkerPack)))
        items.add(mkSubPageButton(CAT_REGEX_FILTER, R.drawable.inu_tabler_filter, LocaleController.getString(R.string.InuRegexFilter)))
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_BETA_INFO -> showBetaBottomSheet()
            CAT_GHOST_MODE -> presentFragment(GhostModeSettingsActivity())
            CAT_ANTI_DELETION -> presentFragment(AntiDeletionSettingsActivity())
            CAT_SELF_DESTRUCT -> presentFragment(SelfDestructSettingsActivity())
            CAT_CONTENT_PROTECTION -> presentFragment(ContentProtectionSettingsActivity())
            CAT_TOS_PROFILE -> presentFragment(TosProfileActivity())
            CAT_UNLIMITED_LIMITS -> presentFragment(UnlimitedLimitsSettingsActivity())
            CAT_STALKER_PACK -> presentFragment(StalkerPackSettingsActivity())
            CAT_REGEX_FILTER -> presentFragment(RegexFilterSettingsActivity())
        }
    }

    private fun showBetaBottomSheet() {
        org.telegram.ui.Components.BulletinFactory.of(this)
            .createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.InuBetaFeatureInfo))
            .show()
    }

    companion object {
        private val BUTTON_BETA_INFO = InuUtils.generateId()
        private val CAT_GHOST_MODE = InuUtils.generateId()
        private val CAT_ANTI_DELETION = InuUtils.generateId()
        private val CAT_SELF_DESTRUCT = InuUtils.generateId()
        private val CAT_CONTENT_PROTECTION = InuUtils.generateId()
        private val CAT_TOS_PROFILE = InuUtils.generateId()
        private val CAT_UNLIMITED_LIMITS = InuUtils.generateId()
        private val CAT_STALKER_PACK = InuUtils.generateId()
        private val CAT_REGEX_FILTER = InuUtils.generateId()

        @JvmField val PAGE = SearchRegistry.Page(
            slug = "tos",
            titleRes = R.string.InuTOS,
            iconRes = R.drawable.inu_tabler_lock_open,
            factory = ::TosSettingsActivity,
            entries = emptyList(),
        )
    }
}
