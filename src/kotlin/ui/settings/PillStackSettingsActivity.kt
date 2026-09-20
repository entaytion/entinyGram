package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.pillstack.PillRegistry
import desu.inugram.helpers.pillstack.RateInstances
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

// entiny: top-level Pill Stack settings -- master toggle, visible-at-once, pill enable/reorder, and rate-pair management.
class PillStackSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuPillStack)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_MASTER,
                R.string.InuPillStack,
                R.string.InuPillStackInfo,
                InuConfig.PILL_STACK_ENABLED.value,
                experimental = true
            )
        )
        if (!InuConfig.PILL_STACK_ENABLED.value) {
            items.add(UItem.asShadow(null))
            return
        }
        items.add(
            UItem.asButton(
                BUTTON_VISIBLE_COUNT,
                LocaleController.getString(R.string.InuPillStackVisibleCount),
                InuConfig.PILL_STACK_VISIBLE_COUNT.value.toString()
            )
        )
        items.add(mkSubPageButton(BUTTON_PILLS, LocaleController.getString(R.string.InuPillStackPills)))
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuPillStackRates)))
        for (instance in RateInstances.getAll()) {
            items.add(
                UItem.asButton(
                    RATE_ROW_ID_BASE + instance.id,
                    PillRegistry.getIconRes(instance.id) ?: R.drawable.pillstack_usd,
                    RateInstances.getLabel(instance),
                )
            )
        }
        if (RateInstances.canAddMore()) {
            items.add(UItem.asButton(BUTTON_ADD_RATE, LocaleController.getString(R.string.InuPillStackAddRate)))
        }
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when {
            item.id == TOGGLE_MASTER -> {
                val new = InuConfig.PILL_STACK_ENABLED.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                // entiny: rows are rebuilt in fillItems; adapter.update re-runs it, softRebuild didn't refresh this screen's list
                listView?.adapter?.update(true)
            }

            item.id == BUTTON_VISIBLE_COUNT -> RadioItemOptions.show(
                this, view,
                listOf("1", "2", "3"),
                InuConfig.PILL_STACK_VISIBLE_COUNT.value - 1,
            ) { which ->
                InuConfig.PILL_STACK_VISIBLE_COUNT.value = which + 1
                listView?.adapter?.update(true)
            }

            item.id == BUTTON_PILLS -> presentFragment(PillStackLayoutActivity())

            item.id == BUTTON_ADD_RATE -> presentFragment(RatePairEditActivity())

            item.id >= RATE_ROW_ID_BASE -> {
                val instanceId = item.id - RATE_ROW_ID_BASE
                if (RateInstances.get(instanceId) != null) presentFragment(RatePairEditActivity(instanceId))
            }
        }
    }

    // entiny: rate pairs are edited on a child screen -- re-fill rows when we come back
    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    companion object {
        private val TOGGLE_MASTER = InuUtils.generateId()
        private val BUTTON_VISIBLE_COUNT = InuUtils.generateId()
        private val BUTTON_PILLS = InuUtils.generateId()
        private val BUTTON_ADD_RATE = InuUtils.generateId()
        private const val RATE_ROW_ID_BASE = 100_000_000

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "pill-stack",
            titleRes = R.string.InuPillStack,
            iconRes = R.drawable.outline_search_1_24,
            factory = ::PillStackSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("pill-stack-master", R.string.InuPillStack, TOGGLE_MASTER),
            ),
        )
    }
}
