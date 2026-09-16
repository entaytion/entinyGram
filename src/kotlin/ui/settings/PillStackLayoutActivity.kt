package desu.inugram.ui.settings

import desu.inugram.InuConfig
import desu.inugram.helpers.pillstack.PillType
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R

// entiny: enable/reorder the fixed Pill Stack types (Clock, Weather, RAM, ...).
class PillStackLayoutActivity : MenuOrderActivity<PillType>() {
    override val config get() = InuConfig.PILL_STACK_LAYOUT
    override val infoStringRes = R.string.InuPillStackInfo
    override val headerStringRes = R.string.InuPillStackPills
    override val resetStringRes = R.string.InuPillStackReset

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuPillStackPills)
}
