package desu.inugram.ui.settings

import android.view.View
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.browser.Browser
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class DonateActivity : SettingsPageActivity() {

    private class Method(val icon: Int, val title: String, val value: String, val url: String? = null) {
        val id = InuUtils.generateId()
        val shown get() = if (url != null || value.length <= 26) value else value.take(12) + "…" + value.takeLast(10)
    }

    private val cards = listOf(
        Method(R.drawable.inu_tabler_pig_money, str(R.string.InuDonateMonobankJar), "send.monobank.ua", MONOBANK_JAR),
        Method(R.drawable.inu_tabler_credit_card, str(R.string.InuDonateMonobankCard), "4874 1000 3949 4128"),
    )
    private val crypto = listOf(
        Method(R.drawable.inu_tabler_send, str(R.string.InuDonateDirect), "@entaytion · 0.1 GRAM", TELEGRAM_SEND),
        Method(R.drawable.inu_tabler_brand_telegram, "CryptoBot", "t.me/send", CRYPTO_BOT),
        Method(R.drawable.inu_simple_icons_ethereum, "ETH / USDC (Base)", "0x1D8cf5A876188256931a6D2FB1F4FAFCB5C03a99"),
        Method(R.drawable.inu_simple_icons_bitcoin, "BTC", "bc1qk9n4mru8skrdw94k6g5s5wcm0ucwlrwqlgxjq7"),
        Method(R.drawable.inu_tabler_currency_solana, "SOL", "BWKK9NwmMFMa4N4MbRYg8yNH2A3zNJxNYNxTBSNZAzXr"),
        Method(R.drawable.inu_tabler_diamond, "GRAM (TON)", "UQBgCQmC-kT8W2AQfEUavuIszQEN-iuR8LUUNJkHeIN2oW5m"),
    )
    private val other = listOf(
        Method(R.drawable.inu_tabler_world, str(R.string.InuDonateWebsite), "entaytion.is-a.dev/donate", SITE),
    )

    override fun getTitle(): CharSequence = str(R.string.InuDonateSheetTitle)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(UItem.asShadow(str(R.string.InuDonateSheetInfo)))
        items.add(UItem.asHeader(str(R.string.InuDonateSectionCards)))
        cards.forEach { items.add(mkTwoLineEntry(it.id, it.icon, it.title, it.shown)) }
        items.add(UItem.asShadow(null))
        items.add(UItem.asHeader(str(R.string.InuDonateSectionCrypto)))
        crypto.forEach { items.add(mkTwoLineEntry(it.id, it.icon, it.title, it.shown)) }
        items.add(UItem.asShadow(null))
        items.add(UItem.asHeader(str(R.string.InuDonateSectionOther)))
        other.forEach { items.add(mkTwoLineEntry(it.id, it.icon, it.title, it.shown)) }
        items.add(UItem.asShadow(str(R.string.InuDonateThanks)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val method = (cards + crypto + other).firstOrNull { it.id == item.id } ?: return
        if (method.url != null) {
            Browser.openUrl(context ?: return, method.url)
        } else {
            AndroidUtilities.addToClipboard(method.value.replace(" ", ""))
            BulletinFactory.of(this).createCopyBulletin(str(R.string.InuDonateCopied)).show()
        }
    }

    private fun str(res: Int) = LocaleController.getString(res)

    companion object {
        private const val MONOBANK_JAR = "https://send.monobank.ua/jar/RGYa8zo8y"
        private const val CRYPTO_BOT = "https://t.me/send?start=IVncNnKfmByl"
        private const val TELEGRAM_SEND = "https://t.me/sendgrams?to=entaytion&amount=0.1&text=thx+for+entiny"
        private const val SITE = "https://entaytion.is-a.dev/donate"
    }
}
