package desu.inugram.helpers.media

import desu.inugram.InuConfig
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_stars
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayList

/**
 * Restores gifts Telegram has since removed from the public Star Gifts catalog.
 *
 * The catalog-injection approach (force a full `getStarGifts` refresh, clone a
 * "donor" gift and clone remotely-listed ids/prices/sticker refs over it) and the
 * remote gift list + sticker pack it consumes are ported from the "Deleted Gift
 * Sender" plugin (materialgram/exteraGram plugin ecosystem) by @binbash_0
 * (https://github.com/binbash-0/DeletedGifts-Plugin), reused with credit per the
 * author's own permission note in that plugin's source.
 */
object StarGiftsHelper {

    private const val GIFTS_URL = "https://raw.githubusercontent.com/binbash-0/DeletedGifts-Plugin/refs/heads/main/gift_list.json"
    private const val GIFTS_CHANNEL_USERNAME = "DeletedGiftsChannel"
    private const val GIFTS_CHANNEL_ID = 3530444329L
    private const val DEFAULT_STICKER_PACK = "DeletedGiftsStickers"

    // Fallback insert position if the live catalog is ever shorter than expected.
    private const val LATEST_GIFT_POSITION = 11

    private data class DeletedGift(val id: Long, val price: Long, val stickerNumber: Int, val debugName: String)

    @Volatile private var deletedGifts: List<DeletedGift> = emptyList()
    @Volatile private var stickerPackName: String = DEFAULT_STICKER_PACK
    @Volatile private var stickerPackDocs: List<TLRPC.Document> = emptyList()
    @Volatile private var refreshInFlight = false
    @Volatile private var stickerFetchInFlight = false

    @JvmStatic
    fun patchStarGifts(response: TL_stars.StarGifts?): TL_stars.StarGifts? {
        if (response == null) return response
        if (!InuConfig.HIDDEN_STAR_GIFTS.value) {
            android.util.Log.d("StarGiftsHelper", "patch: toggle is off, skipping")
            return response
        }
        if (response !is TL_stars.TL_starGifts) {
            android.util.Log.d("StarGiftsHelper", "patch: response is ${response.javaClass.simpleName}, not a full TL_starGifts (probably a cached starGiftsNotModified) -- skipping")
            return response
        }
        try {
            val giftsList = response.gifts ?: return response
            if (giftsList.isEmpty()) return response

            for (i in 0 until giftsList.size) {
                val gift = giftsList[i]
                if (gift != null && gift.attributes == null) {
                    gift.attributes = ArrayList()
                }
            }

            injectDeletedGifts(giftsList)
        } catch (e: Throwable) {
            android.util.Log.d("StarGiftsHelper", "Failed to patch star gifts", e)
        }
        return response
    }

    private fun injectDeletedGifts(giftsList: ArrayList<TL_stars.StarGift>) {
        // refreshed on every catalog fetch, not just once -- self-heals if the first fetch
        // silently failed (no network yet, GitHub blocked before the channel fallback kicks
        // in, etc.) instead of permanently giving up after one failed attempt.
        refreshDeletedGiftsList()
        val gifts = deletedGifts
        if (gifts.isEmpty()) {
            android.util.Log.d("StarGiftsHelper", "inject: no remote gift list yet, skipping this pass")
            return
        }
        if (stickerPackDocs.isEmpty()) {
            loadStickerPack()
            android.util.Log.d("StarGiftsHelper", "inject: sticker pack not cached yet, injected gifts will use the donor's sticker as a placeholder")
        }

        var donor: TL_stars.TL_starGift? = null
        val existingIds = HashSet<Long>()
        for (candidate in giftsList) {
            if (candidate == null) continue
            existingIds.add(candidate.id)
            if (donor == null && candidate is TL_stars.TL_starGift && candidate.sticker != null) {
                donor = candidate
            }
        }
        val donorGift = donor ?: run {
            android.util.Log.d("StarGiftsHelper", "inject: no TL_starGift with a sticker found to clone as donor")
            return
        }

        val toInject = gifts.filter { it.id !in existingIds }
        if (toInject.isEmpty()) {
            android.util.Log.d("StarGiftsHelper", "inject: nothing to add, all ${gifts.size} remote ids already present in the catalog")
            return
        }

        val insertBase = minOf(LATEST_GIFT_POSITION, giftsList.size)
        toInject.forEachIndexed { i, gift ->
            try {
                val clone = cloneDonor(donorGift)
                clone.id = gift.id
                clone.stars = gift.price
                clone.sticker = previewSticker(gift.stickerNumber) ?: donorGift.sticker
                clone.attributes = ArrayList()
                val pos = minOf(insertBase + i, giftsList.size)
                giftsList.add(pos, clone)
            } catch (e: Throwable) {
                android.util.Log.d("StarGiftsHelper", "Failed to inject deleted gift ${gift.debugName}", e)
            }
        }
        android.util.Log.d("StarGiftsHelper", "inject: injected ${toInject.size}/${gifts.size} gifts, catalog now has ${giftsList.size}")
    }

    private fun cloneDonor(donor: TL_stars.TL_starGift): TL_stars.TL_starGift {
        val clone = TL_stars.TL_starGift()
        clone.flags = donor.flags
        clone.limited = donor.limited
        clone.sold_out = donor.sold_out
        clone.birthday = donor.birthday
        clone.require_premium = donor.require_premium
        clone.resale_ton_only = donor.resale_ton_only
        clone.limited_per_user = donor.limited_per_user
        clone.peer_color_available = donor.peer_color_available
        clone.per_user_total = donor.per_user_total
        clone.per_user_remains = donor.per_user_remains
        clone.locked_until_date = donor.locked_until_date
        clone.can_upgrade = donor.can_upgrade
        clone.auction = donor.auction
        clone.gift_id = donor.gift_id
        clone.sticker = donor.sticker
        clone.stars = donor.stars
        clone.availability_remains = donor.availability_remains
        clone.availability_total = donor.availability_total
        clone.availability_resale = donor.availability_resale
        clone.convert_stars = donor.convert_stars
        clone.first_sale_date = donor.first_sale_date
        clone.last_sale_date = donor.last_sale_date
        clone.upgrade_stars = donor.upgrade_stars
        clone.resell_min_stars = donor.resell_min_stars
        clone.theme_available = donor.theme_available
        clone.burned = donor.burned
        clone.crafted = donor.crafted
        clone.theme_peer = donor.theme_peer
        clone.peer_color = donor.peer_color
        clone.host_id = donor.host_id
        clone.title = donor.title
        clone.slug = donor.slug
        clone.num = donor.num
        clone.owner_id = donor.owner_id
        clone.owner_name = donor.owner_name
        clone.owner_address = donor.owner_address
        clone.attributes = if (donor.attributes != null) ArrayList(donor.attributes) else ArrayList()
        clone.availability_issued = donor.availability_issued
        clone.gift_address = donor.gift_address
        clone.released_by = donor.released_by
        clone.value_amount = donor.value_amount
        clone.value_currency = donor.value_currency
        clone.value_usd_amount = donor.value_usd_amount
        clone.resell_amount = donor.resell_amount
        clone.auction_slug = donor.auction_slug
        clone.gifts_per_round = donor.gifts_per_round
        clone.offer_min_stars = donor.offer_min_stars
        clone.auction_start_date = donor.auction_start_date
        clone.upgrade_variants = donor.upgrade_variants
        clone.background = donor.background
        clone.craft_chance_permille = donor.craft_chance_permille
        return clone
    }

    private fun previewSticker(index: Int): TLRPC.Document? {
        val docs = stickerPackDocs
        if (docs.isEmpty()) return null
        val idx = if (index >= 1) minOf(index, docs.size - 1) else index.coerceIn(0, docs.size - 1)
        return docs.getOrNull(idx)
    }

    @JvmStatic
    fun refreshDeletedGiftsList() {
        if (!InuConfig.HIDDEN_STAR_GIFTS.value || refreshInFlight) return
        refreshInFlight = true
        Utilities.globalQueue.postRunnable {
            try {
                applyDeletedGiftsList(fetchJson(GIFTS_URL))
            } catch (e: Throwable) {
                android.util.Log.d("StarGiftsHelper", "Primary gift source failed, trying fallback channel", e)
                fetchFallbackFromChannel()
            } finally {
                refreshInFlight = false
            }
        }
    }

    private fun fetchJson(urlStr: String): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "entinyGram/${BuildConfig.BUILD_VERSION_STRING}")
            connectTimeout = 8_000
            readTimeout = 8_000
        }
        return try {
            val code = conn.responseCode
            if (code != 200) throw Exception("gift list HTTP $code")
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun applyDeletedGiftsList(json: JSONObject) {
        val giftsArray: JSONArray = json.optJSONArray("gifts") ?: return
        val list = ArrayList<DeletedGift>(giftsArray.length())
        for (i in 0 until giftsArray.length()) {
            val o = giftsArray.optJSONObject(i) ?: continue
            list.add(
                DeletedGift(
                    id = o.optLong("id"),
                    price = o.optLong("price"),
                    stickerNumber = o.optInt("sticker_number", 0),
                    debugName = o.optString("debug_name", ""),
                )
            )
        }
        if (list.isNotEmpty()) {
            val wasEmpty = deletedGifts.isEmpty()
            deletedGifts = list
            stickerPackName = json.optString("stickerpack", DEFAULT_STICKER_PACK)
            if (wasEmpty) {
                // the very first patchStarGifts() call always runs before this async fetch
                // finishes, so it injects nothing -- and StarsController.loadStarGifts() then
                // caches that unpatched result for 60s, meaning the catalog would otherwise stay
                // stripped of deleted gifts until the user happens to reopen it a minute later.
                // Force one immediate reload now that the list is actually ready. Gated on
                // wasEmpty so later refreshes (injectDeletedGifts() now retries on every
                // catalog fetch, not just the first) don't reinvalidate on every call --
                // invalidateStarGifts() -> loadStarGifts() -> patchStarGifts() would otherwise
                // call back into refreshDeletedGiftsList() forever.
                org.telegram.messenger.AndroidUtilities.runOnUIThread {
                    org.telegram.ui.Stars.StarsController.getInstance(UserConfig.selectedAccount).invalidateStarGifts()
                }
            }
        }
    }

    private fun fetchFallbackFromChannel() {
        val account = UserConfig.selectedAccount
        val resolveReq = TLRPC.TL_contacts_resolveUsername()
        resolveReq.username = GIFTS_CHANNEL_USERNAME
        ConnectionsManager.getInstance(account).sendRequest(resolveReq) { resolveRes, resolveErr ->
            if (resolveErr != null || resolveRes !is TLRPC.TL_contacts_resolvedPeer) {
                android.util.Log.d("StarGiftsHelper", "Fallback channel resolve failed")
                return@sendRequest
            }
            val channelId = (resolveRes.peer as? TLRPC.TL_peerChannel)?.channel_id
            if (channelId == null || channelId != GIFTS_CHANNEL_ID) {
                android.util.Log.d("StarGiftsHelper", "Fallback channel id mismatch, refusing to trust it")
                return@sendRequest
            }
            val chat = resolveRes.chats?.firstOrNull { it.id == channelId } ?: return@sendRequest
            val inputPeer = org.telegram.messenger.MessagesController.getInstance(account).getInputPeer(chat.id.unaryMinus())
                ?: return@sendRequest

            val historyReq = TLRPC.TL_messages_getHistory()
            historyReq.peer = inputPeer
            historyReq.offset_id = 0
            historyReq.offset_date = 0
            historyReq.add_offset = 0
            historyReq.limit = 10
            historyReq.max_id = 0
            historyReq.min_id = 0
            historyReq.hash = 0

            ConnectionsManager.getInstance(account).sendRequest(historyReq) { historyRes, historyErr ->
                if (historyErr != null || historyRes !is TLRPC.messages_Messages) return@sendRequest
                for (message in historyRes.messages) {
                    val text = message?.message?.trim().orEmpty()
                    if (text.isEmpty()) continue
                    try {
                        applyDeletedGiftsList(JSONObject(text))
                        android.util.Log.d("StarGiftsHelper", "Gift list loaded from fallback channel")
                        return@sendRequest
                    } catch (_: Exception) {
                        continue
                    }
                }
            }
        }
    }

    @JvmStatic
    fun loadStickerPack() {
        if (stickerFetchInFlight || stickerPackDocs.isNotEmpty() || !InuConfig.HIDDEN_STAR_GIFTS.value) return
        val account = UserConfig.selectedAccount

        val cached = MediaDataController.getInstance(account).getStickerSetByName(stickerPackName)
        if (cached != null && cached.documents != null && cached.documents.isNotEmpty()) {
            stickerPackDocs = ArrayList(cached.documents)
            return
        }

        stickerFetchInFlight = true
        val req = TLRPC.TL_messages_getStickerSet()
        val inputSet = TLRPC.TL_inputStickerSetShortName()
        inputSet.short_name = stickerPackName
        req.stickerset = inputSet
        req.hash = 0
        ConnectionsManager.getInstance(account).sendRequest(req) { res, _ ->
            stickerFetchInFlight = false
            if (res is TLRPC.TL_messages_stickerSet && res.documents != null && res.documents.isNotEmpty()) {
                stickerPackDocs = ArrayList(res.documents)
            }
        }
    }
}
