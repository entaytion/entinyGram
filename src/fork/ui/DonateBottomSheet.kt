package desu.inugram.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.browser.Browser
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.LayoutHelper

class DonateBottomSheet(private val fragment: BaseFragment) : BottomSheet(fragment.parentActivity, false) {

    private class Entry(val icon: Int, val title: String, val value: String, val url: String? = null)

    init {
        setApplyBottomPadding(false)
        setApplyTopPadding(false)
        fixNavigationBar(getThemedColor(Theme.key_windowBackgroundWhite))

        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        container.addView(
            TextView(context).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
                typeface = AndroidUtilities.bold()
                text = LocaleController.getString(R.string.InuDonateSheetTitle)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 20, 24, 0),
        )
        container.addView(
            TextView(context).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
                text = LocaleController.getString(R.string.InuDonateSheetInfo)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 28, 6, 28, 12),
        )

        entries().forEach { container.addView(row(it), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56)) }
        container.addView(
            TextView(context).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
                text = LocaleController.getString(R.string.InuDonateThanks)
            },
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 12, 20),
        )

        setCustomView(NestedScrollView(context).apply { addView(container) })
    }

    private fun entries() = listOf(
        Entry(R.drawable.inu_tabler_pig_money, LocaleController.getString(R.string.InuDonateMonobankJar), "send.monobank.ua", MONOBANK_JAR),
        Entry(R.drawable.inu_tabler_credit_card, LocaleController.getString(R.string.InuDonateMonobankCard), "4874 1000 3949 4128"),
        Entry(R.drawable.inu_tabler_brand_telegram, "CryptoBot", "t.me/send", CRYPTO_BOT),
        Entry(R.drawable.inu_tabler_currency_ethereum, "ETH / USDC (Base)", "0x1D8cf5A876188256931a6D2FB1F4FAFCB5C03a99"),
        Entry(R.drawable.inu_tabler_currency_bitcoin, "BTC", "bc1qk9n4mru8skrdw94k6g5s5wcm0ucwlrwqlgxjq7"),
        Entry(R.drawable.inu_tabler_currency_solana, "SOL", "BWKK9NwmMFMa4N4MbRYg8yNH2A3zNJxNYNxTBSNZAzXr"),
        Entry(R.drawable.inu_tabler_diamond, "GRAM (TON)", "UQBgCQmC-kT8W2AQfEUavuIszQEN-iuR8LUUNJkHeIN2oW5m"),
        Entry(R.drawable.inu_tabler_world, LocaleController.getString(R.string.InuDonateWebsite), "entaytion.is-a.dev/donate", SITE),
    )

    private fun row(entry: Entry) = LinearLayout(context).apply {
        val rtl = LocaleController.isRTL
        val side = if (rtl) Gravity.RIGHT else Gravity.LEFT
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(24f), 0, dp(24f), 0)
        background = Theme.getSelectorDrawable(false)
        val icon = ImageView(context).apply {
            setImageResource(entry.icon)
            setColorFilter(Theme.getColor(Theme.key_featuredStickers_addButton))
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                gravity = side
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
                setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
                text = entry.title
            }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
            addView(TextView(context).apply {
                gravity = side
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                setTextColor(Theme.getColor(Theme.key_dialogTextGray3))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                text = entry.value
            }, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))
        }
        if (rtl) {
            addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
            addView(icon, LayoutHelper.createLinear(24, 24, 16f, 0f, 0f, 0f))
        } else {
            addView(icon, LayoutHelper.createLinear(24, 24, 0f, 0f, 16f, 0f))
            addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f))
        }
        setOnClickListener {
            if (entry.url != null) {
                dismiss()
                Browser.openUrl(context, entry.url)
            } else {
                AndroidUtilities.addToClipboard(entry.value.replace(" ", ""))
                BulletinFactory.of(fragment).createCopyBulletin(LocaleController.getString(R.string.InuDonateCopied)).show()
            }
        }
    }

    companion object {
        private const val MONOBANK_JAR = "https://send.monobank.ua/jar/RGYa8zo8y"
        private const val CRYPTO_BOT = "https://t.me/send?start=IVncNnKfmByl"
        private const val SITE = "https://entaytion.is-a.dev/donate"
    }
}
