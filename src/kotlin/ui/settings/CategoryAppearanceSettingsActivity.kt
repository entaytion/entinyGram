package desu.inugram.ui.settings

import org.telegram.messenger.R

/** Category 1 — Зовнішній вигляд та UI (Appearance & Layout). */
class CategoryAppearanceSettingsActivity : CategoryIndexActivity() {
    override val titleRes: Int = R.string.InuCategoryAppearance
    override val children = listOf(
        Child(R.drawable.msg_settings_old, R.string.InuLookAndFeel) { AppearanceSettingsActivity() },
        Child(R.drawable.msg_viewchats, R.string.InuMainPage) { DialogsSettingsActivity() },
        Child(R.drawable.menu_hide_gift, R.string.InuAnnoyances) { AnnoyancesSettingsActivity() },
    )
}
