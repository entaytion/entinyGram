package desu.inugram.helpers.dialogs

import desu.inugram.InuConfig
import org.telegram.tgnet.TLRPC

object SuggestionFilter {
    private val hiddenByFlag: Map<String, InuConfig.BoolItem> = mapOf(
        "BIRTHDAY_SETUP" to InuConfig.HIDE_SUGGESTION_BIRTHDAY_SETUP,
        "BIRTHDAY_CONTACTS_TODAY" to InuConfig.HIDE_SUGGESTION_BIRTHDAY_CONTACTS,
        "VALIDATE_PASSWORD" to InuConfig.HIDE_SUGGESTION_PASSWORD,
        "SETUP_PASSWORD" to InuConfig.HIDE_SUGGESTION_PASSWORD,
        "VALIDATE_PHONE_NUMBER" to InuConfig.HIDE_SUGGESTION_PHONE,
        "PREMIUM_ANNUAL" to InuConfig.HIDE_SUGGESTION_PREMIUM,
        "PREMIUM_UPGRADE" to InuConfig.HIDE_SUGGESTION_PREMIUM,
        "PREMIUM_RESTORE" to InuConfig.HIDE_SUGGESTION_PREMIUM,
        "PREMIUM_CHRISTMAS" to InuConfig.HIDE_SUGGESTION_PREMIUM,
        "PREMIUM_GRACE" to InuConfig.HIDE_SUGGESTION_PREMIUM,
    )

    @JvmStatic
    fun filterPromoData(res: TLRPC.TL_help_promoData) {
        res.pending_suggestions.removeAll { hiddenByFlag[it]?.value == true }
        if (InuConfig.HIDE_SUGGESTION_CUSTOM.value) {
            res.custom_pending_suggestion = null
        }
        // proxy owners can attach a "sponsor" chat/channel that Telegram pins atop the dialog
        // list while their proxy is in use (TL_help_getPromoData's `proxy` flag + `peer`) —
        // strip it here so no promo dialog is ever created for it.
        if (res.proxy && InuConfig.HIDE_PROXY_SPONSOR_CHAT.value) {
            res.proxy = false
            res.peer = null
        }
        // non-proxy promo chats: Telegram's PSA slots (psa_type non-empty -> PROMO_TYPE_PSA) and
        // plain sponsored promo chats (PROMO_TYPE_OTHER). Both are pinned atop the dialog list the
        // same way; clearing `peer` makes MessagesController resolve promoDialogId to 0 -> no dialog.
        if (!res.proxy && InuConfig.HIDE_PSA_PROMO_CHAT.value) {
            res.peer = null
            res.psa_type = null
            res.psa_message = null
        }
    }
}
