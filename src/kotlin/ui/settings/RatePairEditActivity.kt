package desu.inugram.ui.settings

import android.view.View
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.pillstack.ExchangeRates
import desu.inugram.helpers.pillstack.GoldPrice
import desu.inugram.helpers.pillstack.PillCurrencies
import desu.inugram.helpers.pillstack.RateInstances
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import java.math.BigDecimal
import java.math.RoundingMode

// entiny: dedicated editor for one rate pill (base -> target), applied live on every pick.
class RatePairEditActivity(private val instanceId: Int = -1) : SettingsPageActivity() {

    private var editId = instanceId
    private var base: String
    private var target: String
    private var previewValue: String? = null

    init {
        val instance = if (instanceId != -1) RateInstances.get(instanceId) else null
        base = instance?.from ?: RateInstances.defaultBase()
        target = instance?.to ?: PillCurrencies.AUTO
    }

    override fun getTitle(): CharSequence =
        "${RateInstances.getBaseLabel(base)} → ${PillCurrencies.getTargetCurrencyLabel(target)}"

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            UItem.asButton(
                BUTTON_BASE,
                RateInstances.getBaseIcon(base),
                LocaleController.getString(R.string.InuPillStackRateBase),
                RateInstances.getBaseLabel(base),
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_TARGET,
                R.drawable.pillstack_usd,
                LocaleController.getString(R.string.InuPillStackRateTarget),
                PillCurrencies.getTargetCurrencyLabel(target),
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_NONE,
                LocaleController.formatString(
                    R.string.InuPillStackRatePreview,
                    RateInstances.getBaseLabel(base),
                    previewValue ?: LocaleController.getString(R.string.Checking),
                ),
                null,
            )
        )
        if (editId != -1) {
            items.add(
                UItem.asButton(BUTTON_DELETE, R.drawable.msg_delete, LocaleController.getString(R.string.Delete))
            )
        }
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_BASE -> CurrencyPickerActivity.present(this, CurrencyPickerActivity.MODE_BASE, base, null) { picked ->
                if (picked == target) target = PillCurrencies.AUTO
                base = picked
                apply()
            }

            BUTTON_TARGET -> CurrencyPickerActivity.present(this, CurrencyPickerActivity.MODE_TARGET, target, base) { picked ->
                target = picked
                apply()
            }

            BUTTON_DELETE -> {
                if (editId != -1) RateInstances.remove(editId)
                finishFragment()
            }
        }
    }

    private fun apply() {
        if (editId == -1) {
            RateInstances.create(base, target)?.let { editId = it.id }
        } else {
            RateInstances.setPair(editId, base, target)
        }
        actionBar.setTitle(getTitle())
        listView?.adapter?.update(true)
        refreshPreview()
    }

    private fun refreshPreview() {
        val activeBase = base
        val resolvedTarget = ExchangeRates.resolveTargetCurrency(target)
        val onRate: (BigDecimal?) -> Unit = { rate ->
            previewValue = rate?.let { format(it, resolvedTarget) }
            if (base == activeBase) listView?.adapter?.update(true)
        }
        if (activeBase == "XAU") {
            GoldPrice.fetch { usdPrice ->
                if (usdPrice == null || resolvedTarget == "USD") {
                    onRate(usdPrice)
                    return@fetch
                }
                ExchangeRates.fetch { state ->
                    val conversion = state?.getRate("USD", resolvedTarget)
                    onRate(conversion?.let { usdPrice.multiply(it) })
                }
            }
        } else {
            ExchangeRates.fetch { state -> onRate(state?.getRate(activeBase, resolvedTarget)) }
        }
    }

    private fun format(value: BigDecimal, currency: String): String =
        PillCurrencies.formatFiatPrice(value, currency)
            ?: (value.setScale(RateInstances.getScale(base), RoundingMode.HALF_UP).toPlainString() + " " + currency)

    override fun onResume() {
        super.onResume()
        refreshPreview()
    }

    companion object {
        private val BUTTON_BASE = InuUtils.generateId()
        private val BUTTON_TARGET = InuUtils.generateId()
        private val BUTTON_DELETE = InuUtils.generateId()
        private val BUTTON_NONE = InuUtils.generateId()
    }
}
