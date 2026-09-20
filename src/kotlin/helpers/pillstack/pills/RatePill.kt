package desu.inugram.helpers.pillstack.pills

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import desu.inugram.helpers.pillstack.ExchangeRates
import desu.inugram.helpers.pillstack.GoldPrice
import desu.inugram.helpers.pillstack.PillCurrencies
import desu.inugram.helpers.pillstack.RateInstances
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedTextView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.ScaleStateListAnimator
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicReference

// entiny: one base->target rate pill; the pair is user-configurable via RateInstances (settings -> Pill Stack -> Currency rates).
@SuppressLint("ViewConstructor")
class RatePill(context: Context, resourcesProvider: Theme.ResourcesProvider?, private val instanceId: Int) :
    BasePill(context, resourcesProvider) {

    private class RateCache {
        val cachedPrice = AtomicReference<String?>()
        val cachedCurrency = AtomicReference<String?>()
    }

    companion object {
        private val caches = HashMap<Int, RateCache>()

        private fun cacheFor(instanceId: Int): RateCache = synchronized(caches) {
            caches.getOrPut(instanceId) { RateCache() }
        }

        // entiny: pair changed under us -- the remembered price belongs to the old pair
        fun clearCache(instanceId: Int) {
            synchronized(caches) { caches.remove(instanceId) }
        }
    }

    private val layout = LinearLayout(context)
    private val iconView = ImageView(context)
    private val textView = AnimatedTextView(context, true, true, true)
    private var requestInFlight = false

    init {
        layout.orientation = LinearLayout.HORIZONTAL
        layout.gravity = Gravity.CENTER
        layout.minimumWidth = AndroidUtilities.dp(48f)
        layout.setPadding(AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(8f), 0)
        addView(
            layout, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, 28,
                (if (LocaleController.isRTL) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL
            )
        )

        iconView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0f, 0f, 4f, 0f))

        textView.setTextSize(AndroidUtilities.dp(13f).toFloat())
        textView.setIncludeFontPadding(false)
        textView.setTypeface(AndroidUtilities.bold())
        textView.adaptWidth = true
        layout.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL))

        setLoadingTargetView(layout)
        updateColors()
        ScaleStateListAnimator.apply(layout)

        cache().cachedPrice.get()?.let { setData(it, false) }
    }

    override fun getPillId(): Int = instanceId

    private fun instance() = RateInstances.get(instanceId)

    private fun baseCurrency(): String = instance()?.from ?: RateInstances.defaultBase()

    private fun targetSelection(): String = instance()?.to ?: PillCurrencies.AUTO

    private fun cache(): RateCache = cacheFor(instanceId)

    override fun getRefreshInterval(): Long = 5 * 60 * 1000L

    override fun onPillClicked() {
        onUpdateData(true)
    }

    override fun onPillLongClicked(): Boolean = false

    private fun fetchRate(target: String, force: Boolean, callback: Utilities.Callback<BigDecimal?>) {
        val base = baseCurrency()
        if (force) {
            ExchangeRates.clearCache()
            if (base == "XAU") GoldPrice.clearCache()
        }
        if (base != "XAU") {
            ExchangeRates.fetch { state -> callback.run(state?.getRate(base, target)) }
            return
        }
        GoldPrice.fetch { usdPrice ->
            if (usdPrice == null) {
                callback.run(null)
                return@fetch
            }
            if (target == "USD") {
                callback.run(usdPrice)
                return@fetch
            }
            ExchangeRates.fetch { state ->
                val conversion = state?.getRate("USD", target)
                callback.run(conversion?.let { usdPrice.multiply(it) })
            }
        }
    }

    override fun onUpdateData(force: Boolean) {
        val cache = cache()
        val target = ExchangeRates.resolveTargetCurrency(targetSelection())
        var cached = cache.cachedPrice.get()
        if (cache.cachedCurrency.get() != target) cached = null
        if (!force && cached != null && !isRefreshDue()) {
            setData(cached, false)
            return
        }
        if (requestInFlight) return
        requestInFlight = true
        if (force) animateSizeChange()
        startLoading()
        if (cached == null && cache.cachedPrice.get() == null) {
            iconView.visibility = GONE
            textView.visibility = GONE
        } else {
            iconView.setImageResource(RateInstances.getBaseIcon(baseCurrency()))
            iconView.visibility = VISIBLE
            textView.visibility = VISIBLE
        }
        fetchRate(target, force) { rate ->
            requestInFlight = false
            if (rate == null) {
                val fallback = cache.cachedPrice.get()
                if (fallback != null) setData(fallback, true) else setErrorState()
                return@fetchRate
            }
            val price = formatPrice(rate, target)
            cache.cachedPrice.set(price)
            cache.cachedCurrency.set(target)
            setData(price, true)
            markDataUpdated()
        }
    }

    private fun formatPrice(value: BigDecimal, currency: String): String =
        PillCurrencies.formatFiatPrice(value, currency)
            ?: (value.setScale(RateInstances.getScale(baseCurrency()), RoundingMode.HALF_UP).toPlainString() + " " + currency)

    private fun setData(price: String, animated: Boolean) {
        stopLoading()
        if (animated) animateSizeChange()
        iconView.setImageResource(RateInstances.getBaseIcon(baseCurrency()))
        iconView.visibility = VISIBLE
        textView.setText(price, animated)
        textView.visibility = VISIBLE
    }

    private fun setErrorState() {
        stopLoading()
        animateSizeChange()
        iconView.setImageResource(R.drawable.msg_retry)
        iconView.visibility = VISIBLE
        textView.setText(LocaleController.getString(R.string.Retry), true)
        textView.visibility = VISIBLE
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
        layout.background = ColoredBackground(RateInstances.getBaseColorTop(baseCurrency()), RateInstances.getBaseColorBottom(baseCurrency()))
        textView.setTextColor(0xFFFFFFFF.toInt())
        iconView.setColorFilter(0xFFFFFFFF.toInt())
        updateLoadingColors()
    }

    override fun updateLoadingColors() {
        loadingDrawable?.setColors(
            Theme.multAlpha(0xFFFFFFFF.toInt(), 0.1f),
            Theme.multAlpha(0xFFFFFFFF.toInt(), 0.3f)
        )
    }
}
