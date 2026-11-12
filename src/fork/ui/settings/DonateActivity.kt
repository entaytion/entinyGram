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
    )
    private val usdt = listOf(
        Method(R.drawable.inu_cryptocurrency_trx, "USDT · TRON (TRC-20)", TRON),
        Method(R.drawable.inu_simple_icons_ton, "USDT · TON", TON),
        Method(R.drawable.inu_tabler_currency_solana, "USDT · Solana", SOLANA),
        Method(R.drawable.inu_simple_icons_tether, "USDT · Ethereum, BSC, Base", EVM),
    )
    private val coins = listOf(
        Method(R.drawable.inu_simple_icons_ton, "GRAM (TON)", TON),
        Method(R.drawable.inu_simple_icons_bitcoin, "BTC", BITCOIN),
        Method(R.drawable.inu_simple_icons_ethereum, "ETH · Ethereum, Base", EVM),
        Method(R.drawable.inu_simple_icons_bnbchain, "BNB · BSC", EVM),
        Method(R.drawable.inu_tabler_currency_solana, "SOL", SOLANA),
        Method(R.drawable.inu_cryptocurrency_trx, "TRX", TRON),
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
        items.add(UItem.asHeader("USDT"))
        usdt.forEach { items.add(mkTwoLineEntry(it.id, it.icon, it.title, it.shown)) }
        items.add(UItem.asShadow(null))
        items.add(UItem.asHeader(str(R.string.InuDonateSectionCoins)))
        coins.forEach { items.add(mkTwoLineEntry(it.id, it.icon, it.title, it.shown)) }
        items.add(UItem.asShadow(null))
        items.add(UItem.asHeader(str(R.string.InuDonateSectionOther)))
        other.forEach { items.add(mkTwoLineEntry(it.id, it.icon, it.title, it.shown)) }
        items.add(UItem.asShadow(str(R.string.InuDonateThanks)))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        val method = (cards + crypto + usdt + coins + other).firstOrNull { it.id == item.id } ?: return
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
        private const val TON = "UQBVF34OLvKx8trJsMaJfjVOg0kdr75C463c53HfRYav3XyQ"
        private const val EVM = "0x1D0b404e9C2aa1aE18824a2C7f2f428A279edF01"
        private const val SOLANA = "Na3jT5GE73rfSD8RMNUC7VkjWF4jysRjkm8vw93xu94"
        private const val TRON = "TQxoxjRTRe6QZqcyapdA26XaAiaDmt1ZBU"
        private const val BITCOIN = "bc1qh4zs0adawjse4sj75klyp5qs2vlqcsd7y9sp36"
    }
}
