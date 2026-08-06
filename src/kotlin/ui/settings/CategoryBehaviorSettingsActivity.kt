package desu.inugram.ui.settings

import org.telegram.messenger.R

/** Category 4 — Поведінка та інструменти (Behavior & Tools). */
class CategoryBehaviorSettingsActivity : CategoryIndexActivity() {
    override val titleRes: Int = R.string.InuCategoryBehavior
    override val children = listOf(
        Child(R.drawable.avd_speed, R.string.InuBehavior) { BehaviorSettingsActivity() },
        Child(R.drawable.msg_translate, R.string.InuTranslator) { TranslatorSettingsActivity() },
    )
}
