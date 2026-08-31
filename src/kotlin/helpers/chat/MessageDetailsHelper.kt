package desu.inugram.helpers.chat

import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.FileProvider
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.ChatObject
import org.telegram.messenger.ContactsController
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.Vector
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.ActionBar.ActionBarPopupWindow
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.PopupSwipeBackLayout
import org.telegram.ui.ProfileActivity
import java.io.File
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object MessageDetailsHelper {

    data class DetailItem(
        val title: CharSequence,
        var subtitle: CharSequence? = null,
        val iconRes: Int,
        var realValue: String? = null,
        val itemId: Int = -1, // 0 = owner, 1 = file_path
        val ownerId: Long = 0L,
        val mimeType: String? = null,
        val inputStickerSet: TLRPC.InputStickerSet? = null,
        val doc: TLRPC.Document? = null,
    )

    fun openDetailsSubmenu(
        activity: ChatActivity,
        popupLayout: ActionBarPopupWindow.ActionBarPopupWindowLayout,
        anchorCell: View,
        message: MessageObject,
        group: MessageObject.GroupedMessages?
    ): Boolean {
        val swipeBack = popupLayout.swipeBack ?: return false
        val rp = activity.resourceProvider
        val context = popupLayout.context

        val menuWidthPx = popupLayout.measuredWidth - popupLayout.paddingLeft - popupLayout.paddingRight
        val minWidthDp = (menuWidthPx / AndroidUtilities.density).roundToInt().coerceAtLeast(230)

        val swb = ItemOptions.swipeback(popupLayout, rp)
        swb.setMinWidth(minWidthDp)

        val (headerItems, datesItems, middleItems, dcItem) = buildOrderedDetailItems(activity, message, group)

        if (headerItems.isNotEmpty()) {
            for (item in headerItems) {
                addItemView(context, activity, swb.linearLayout, swipeBack, item, rp, minWidthDp)
            }
            addGap(context, swb.linearLayout, rp)
        }

        if (datesItems.isNotEmpty()) {
            for (item in datesItems) {
                addItemView(context, activity, swb.linearLayout, swipeBack, item, rp, minWidthDp)
            }
            if (middleItems.isNotEmpty() || dcItem != null) {
                addGap(context, swb.linearLayout, rp)
            }
        }

        if (middleItems.isNotEmpty()) {
            for (item in middleItems) {
                addItemView(context, activity, swb.linearLayout, swipeBack, item, rp, minWidthDp)
            }
        }

        if (dcItem != null) {
            if (middleItems.isNotEmpty()) {
                addGap(context, swb.linearLayout, rp)
            }
            addItemView(context, activity, swb.linearLayout, swipeBack, dcItem, rp, minWidthDp)
        }

        // Root container for swipeback page with fixed header & scrolling body
        val rootLayout = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxH = (popupLayout.measuredHeight - popupLayout.paddingTop - popupLayout.paddingBottom).takeIf { it > 0 } ?: AndroidUtilities.dp(400f)
                val hSize = MeasureSpec.getSize(heightMeasureSpec)
                val hSpec = MeasureSpec.makeMeasureSpec(Math.min(if (hSize > 0) hSize else maxH, maxH), MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, hSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = AndroidUtilities.dp(minWidthDp.toFloat())
        }

        // Fixed Back button at top
        val backItem = ActionBarMenuSubItem(context, false, false, rp)
        backItem.setTextAndIcon(LocaleController.getString(R.string.Back), R.drawable.ic_ab_back)
        backItem.setMinimumWidth(AndroidUtilities.dp(minWidthDp.toFloat()))
        backItem.setItemHeight(48)
        backItem.setOnClickListener {
            swipeBack.closeForeground()
        }
        rootLayout.addView(backItem, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48))

        val headerGap = ActionBarPopupWindow.GapView(context, rp).apply {
            background = null
        }
        rootLayout.addView(headerGap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8))

        // ScrollView for the detail items with dynamic max height constraint
        val scrollView = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val currentMaxH = (popupLayout.measuredHeight - popupLayout.paddingTop - popupLayout.paddingBottom - AndroidUtilities.dp(56f)).coerceAtLeast(AndroidUtilities.dp(100f))
                val hSize = MeasureSpec.getSize(heightMeasureSpec)
                val hSpec = MeasureSpec.makeMeasureSpec(Math.min(if (hSize > 0) hSize else currentMaxH, currentMaxH), MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, hSpec)
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(swb.linearLayout, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }

        rootLayout.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        val foregroundIndex = popupLayout.addViewToSwipeBack(rootLayout)
        (rootLayout.layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.TOP
        swipeBack.inu_pinnedScrimForegroundIndex = foregroundIndex

        swipeBack.inu_setForegroundOffsetY(foregroundIndex, 0)
        swipeBack.openForeground(foregroundIndex)
        return true
    }

    private fun addGap(context: Context, layout: LinearLayout, rp: Theme.ResourcesProvider?) {
        val gap = ActionBarPopupWindow.GapView(context, rp).apply {
            background = null
        }
        layout.addView(gap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8))
    }

    private fun addItemView(
        context: Context,
        activity: ChatActivity,
        layout: LinearLayout,
        swipeBack: PopupSwipeBackLayout,
        item: DetailItem,
        rp: Theme.ResourcesProvider?,
        minWidthDp: Int
    ) {
        val subItem = ActionBarMenuSubItem(context, false, false, rp)
        subItem.setTextAndIcon(item.title, item.iconRes)
        subItem.setMinimumWidth(AndroidUtilities.dp(minWidthDp.toFloat()))

        val height: Int
        if (!item.subtitle.isNullOrEmpty()) {
            subItem.setSubtext(item.subtitle)
            subItem.subtextView?.apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
            subItem.setItemHeight(56)
            height = 56
        } else {
            subItem.setItemHeight(48)
            height = 48
        }

        // Async resolution of cached owner username/name
        if (item.itemId == 0 && item.ownerId > 0) {
            resolveCachedOwnerAsync(activity.currentAccount, item.ownerId, item, subItem)
        }

        // Async resolution of sticker set pack name/index
        if (item.inputStickerSet != null) {
            fetchStickerSetAsync(activity.currentAccount, item.inputStickerSet, item.doc, item, subItem)
        }

        subItem.setOnClickListener {
            onItemClicked(activity, swipeBack, item, close = true)
        }

        subItem.setOnLongClickListener {
            onItemClicked(activity, swipeBack, item, close = false)
            true
        }

        layout.addView(subItem, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, height))
    }

    private fun onItemClicked(
        activity: ChatActivity,
        swipeBack: PopupSwipeBackLayout,
        item: DetailItem,
        close: Boolean
    ) {
        val valToCopy = item.realValue ?: item.subtitle?.toString() ?: item.title.toString()

        if (close) {
            if (item.itemId == 1 && !item.realValue.isNullOrEmpty()) {
                swipeBack.closeForeground()
                activity.scrimPopupWindow?.dismiss()
                openFile(activity, item.realValue!!, item.mimeType)
                return
            }

            if (item.itemId == 0 && item.ownerId > 0) {
                // the sender of a message already visible in this chat is virtually always in
                // MessagesController's in-memory cache; avoid a synchronous DB hit on the UI thread
                // for the rare miss instead of blocking the click on storage.getUserSync.
                val mc = MessagesController.getInstance(activity.currentAccount)
                val user = mc?.getUser(item.ownerId)

                val isValidUser = user != null && (user.access_hash != 0L || !user.username.isNullOrEmpty() || user.contact || user.id == UserConfig.getInstance(activity.currentAccount).clientUserId)
                if (isValidUser) {
                    swipeBack.closeForeground()
                    activity.scrimPopupWindow?.dismiss()
                    val bundle = Bundle().apply {
                        putLong("user_id", item.ownerId)
                        if (item.ownerId == UserConfig.getInstance(activity.currentAccount).clientUserId) {
                            putBoolean("my_profile", true)
                        }
                    }
                    activity.presentFragment(ProfileActivity(bundle))
                    return
                }
            }

            if (item.inputStickerSet != null) {
                swipeBack.closeForeground()
                activity.scrimPopupWindow?.dismiss()
                val parentAct = activity.parentActivity
                if (parentAct != null) {
                    val alert = org.telegram.ui.Components.StickersAlert(
                        parentAct,
                        activity,
                        item.inputStickerSet,
                        null,
                        activity.chatActivityEnterView,
                        activity.resourceProvider,
                        false
                    )
                    activity.showDialog(alert)
                }
                return
            }

            swipeBack.closeForeground()
            activity.scrimPopupWindow?.dismiss()
        }

        try {
            AndroidUtilities.addToClipboard(valToCopy)
            BulletinFactory.of(activity).createCopyBulletin(LocaleController.formatString(R.string.TextCopied)).show()
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    private fun openFile(activity: ChatActivity, filePath: String, mimeType: String?) {
        val file = File(filePath)
        if (!file.exists()) return
        val act = activity.parentActivity ?: return
        try {
            val uri = FileProvider.getUriForFile(
                act,
                ApplicationLoader.getApplicationId() + ".provider",
                file
            )
            val mime = mimeType ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            if (act.packageManager.queryIntentActivities(intent, 0).isNotEmpty()) {
                act.startActivity(intent)
            } else {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                act.startActivity(Intent.createChooser(sendIntent, LocaleController.getString(R.string.InuMsgDetailOpenFile)))
            }
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    private fun resolveCachedOwnerAsync(
        account: Int,
        ownerId: Long,
        item: DetailItem,
        subItem: ActionBarMenuSubItem
    ) {
        val mc = MessagesController.getInstance(account) ?: return
        val user = mc.getUser(ownerId)
        if (user != null) {
            val pubName = user.username
            if (!pubName.isNullOrEmpty()) {
                item.subtitle = "@$pubName ($ownerId)"
                item.realValue = "@$pubName"
            } else {
                val fname = ContactsController.formatName(user.first_name, user.last_name)
                if (fname.isNotEmpty()) {
                    item.subtitle = "$fname ($ownerId)"
                }
            }
            subItem.setSubtext(item.subtitle)
            return
        }

        val storage = MessagesStorage.getInstance(account) ?: return
        storage.storageQueue.postRunnable {
            val loaded = storage.getUser(ownerId)
            if (loaded != null) {
                AndroidUtilities.runOnUIThread {
                    mc.putUser(loaded, true)
                    val pubName = loaded.username
                    if (!pubName.isNullOrEmpty()) {
                        item.subtitle = "@$pubName ($ownerId)"
                        item.realValue = "@$pubName"
                    } else {
                        val fname = ContactsController.formatName(loaded.first_name, loaded.last_name)
                        if (fname.isNotEmpty()) {
                            item.subtitle = "$fname ($ownerId)"
                        }
                    }
                    subItem.setSubtext(item.subtitle)
                }
            }
        }
    }

    private fun fetchStickerSetAsync(
        account: Int,
        inputStickerSet: TLRPC.InputStickerSet,
        doc: TLRPC.Document?,
        item: DetailItem,
        subItem: ActionBarMenuSubItem
    ) {
        val mdc = MediaDataController.getInstance(account) ?: return
        val setObj = mdc.getStickerSet(inputStickerSet, null, false)
        if (setObj?.set != null) {
            val shortName = setObj.set.short_name.orEmpty()
            var index = -1
            if (doc != null && setObj.documents != null) {
                val idx = setObj.documents.indexOfFirst { it.id == doc.id }
                if (idx >= 0) index = idx
            }
            val packStr = when {
                shortName.isNotEmpty() && index >= 0 -> "$shortName/$index"
                shortName.isNotEmpty() -> shortName
                index >= 0 -> index.toString()
                else -> setObj.set.id.toString()
            }
            item.subtitle = packStr
            item.realValue = packStr
            subItem.setSubtext(packStr)
        }
    }

    fun buildOrderedDetailItems(
        activity: ChatActivity,
        messageObject: MessageObject,
        messageGroup: MessageObject.GroupedMessages?
    ): Tuple4<List<DetailItem>, List<DetailItem>, List<DetailItem>, DetailItem?> {
        val owner = messageObject.messageOwner ?: return Tuple4(emptyList(), emptyList(), emptyList(), null)
        val headerItems = mutableListOf<DetailItem>()
        val datesItems = mutableListOf<DetailItem>()
        val middleItems = mutableListOf<DetailItem>()
        var dcItem: DetailItem? = null

        // 1. Header Section: Views, Shares
        if (owner.views > 0) {
            headerItems.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailViews), owner.views.toString(), R.drawable.msg_view_file))
        }
        if (owner.forwards > 0) {
            headerItems.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailForwards), owner.forwards.toString(), R.drawable.msg_forward))
        }

        // 2. Dates & IDs Section
        val idVal = if (messageObject.currentEvent != null) messageObject.currentEvent.id.toString() else owner.id.toString()
        datesItems.add(DetailItem("ID", idVal, R.drawable.msg_info))

        val media = MessageObject.getMedia(owner)
        val doc = media?.webpage?.document ?: media?.document
        var stickersetId = 0L
        var inputStickerSet: TLRPC.InputStickerSet? = null
        var associatedEmoji: String? = null
        var isAdaptiveTextColor = false

        if (doc != null) {
            for (attr in doc.attributes) {
                when (attr) {
                    is TLRPC.TL_documentAttributeSticker -> {
                        associatedEmoji = attr.alt
                        if (attr.stickerset != null) {
                            stickersetId = attr.stickerset.id
                            inputStickerSet = attr.stickerset
                        }
                    }
                    is TLRPC.TL_documentAttributeCustomEmoji -> {
                        associatedEmoji = attr.alt
                        isAdaptiveTextColor = attr.text_color
                        if (attr.stickerset != null) {
                            stickersetId = attr.stickerset.id
                            inputStickerSet = attr.stickerset
                        }
                    }
                    else -> {}
                }
            }
        }

        if (stickersetId > 0) {
            datesItems.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailStickerSetId), stickersetId.toString(), R.drawable.msg_sticker))
            val ownerId = extractStickerSetOwnerId(stickersetId)
            if (ownerId > 0) {
                val isEmoji = doc?.attributes?.any { it is TLRPC.TL_documentAttributeCustomEmoji } == true
                val ownerTitleRes = if (isEmoji) R.string.InuMsgDetailEmojiPackCreators else R.string.InuMsgDetailStickerPackCreator
                datesItems.add(DetailItem(LocaleController.getString(ownerTitleRes), ownerId.toString(), R.drawable.msg_contacts_name, itemId = 0, ownerId = ownerId))
            }
            if (inputStickerSet != null) {
                datesItems.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailStickerPack), stickersetId.toString(), R.drawable.msg_sticker, inputStickerSet = inputStickerSet, doc = doc))
            }
        }

        if (owner.date > 0) {
            val dateStr = if (owner.date == 0x7ffffffe) {
                LocaleController.getString(R.string.InuMsgDetailWhenOnline)
            } else {
                formatDate(owner.date)
            }
            datesItems.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailDate), dateStr, R.drawable.msg_calendar2))
        }

        val savedDeleted = SavedMessagesHelper.getDeletedDate(activity.currentAccount, messageObject.dialogId, messageObject.id)
        val deleteTs = when {
            savedDeleted > 0 -> (savedDeleted / 1000).toInt()
            owner.destroyTime != 0 -> owner.destroyTime
            else -> 0
        }
        if (deleteTs > 0) {
            datesItems.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailDeleteDate), formatDate(deleteTs), R.drawable.msg_delete))
        }

        val readDateField = readField(owner, "read_date") as? Int ?: 0
        if (readDateField > 0) {
            datesItems.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailReadDate), formatDate(readDateField), R.drawable.msg_seen))
        }

        val fwd = owner.fwd_from
        if (fwd != null && fwd.date > 0 && fwd.date != owner.date) {
            datesItems.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailForwardDate), formatDate(fwd.date), R.drawable.msg_recent))
        }

        // Only show edited if message was legitimately marked as edited or has saved edit history
        val isEdited = messageObject.isEdited || SavedMessagesHelper.hasEditHistory(activity.currentAccount, messageObject.dialogId, messageObject.id)
        if (isEdited && owner.edit_date > 0) {
            datesItems.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailEdited), formatDate(owner.edit_date), R.drawable.msg_edit))
        }

        val autoDeleteAt = if (owner.destroyTime != 0) owner.destroyTime else if (owner.ttl_period != 0 && owner.date != 0) owner.date + owner.ttl_period else 0
        if (autoDeleteAt > 0) {
            datesItems.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailAutoDelete), formatDate(autoDeleteAt), R.drawable.msg_autodelete))
        }

        // 3. Middle Section: Media specifics
        var filePath: String? = null
        var docSize: Long = if (doc != null) doc.size else messageObject.size
        var streamSize: Long = 0L

        val localFile = FileLoader.getInstance(activity.currentAccount).getPathToMessage(owner)
        if (localFile != null && localFile.exists()) {
            filePath = localFile.absolutePath
            streamSize = localFile.length()
            if (docSize <= 0) docSize = streamSize
        }

        if (docSize > 0) {
            middleItems.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailFileSize), AndroidUtilities.formatFileSize(docSize), R.drawable.msg_sendfile))
        }

        val mime = messageObject.mimeType?.lowercase(Locale.US) ?: doc?.mime_type?.lowercase(Locale.US) ?: ""

        val photo = media?.webpage?.photo ?: media?.photo
        val action = owner.action

        when {
            messageObject.isVoice || messageObject.isMusic -> {
                extractAudioMiddleItems(middleItems, messageObject, owner, doc, filePath, streamSize, docSize, mime)
            }
            messageObject.isVideo || messageObject.isRoundVideo || messageObject.isGif -> {
                extractVideoMiddleItems(middleItems, messageObject, owner, doc, filePath, mime)
            }
            messageObject.isSticker || (doc != null && hasStickerAttr(doc)) -> {
                extractStickerMiddleItems(middleItems, messageObject, owner, doc, inputStickerSet, associatedEmoji, isAdaptiveTextColor, filePath, mime)
            }
            messageObject.isPhoto || photo != null -> {
                extractPhotoMiddleItems(middleItems, messageObject, photo, filePath, mime)
            }
            media is TLRPC.TL_messageMediaPoll -> {
                extractPollMiddleItems(middleItems, media)
            }
            action != null -> {
                extractServiceMiddleItems(activity, middleItems, action)
            }
            else -> {
                if (mime.isNotEmpty()) {
                    middleItems.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailMimeType), mime, R.drawable.msg_media))
                }
                if (!filePath.isNullOrEmpty()) {
                    middleItems.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailFilePath), LocaleController.getString(R.string.InuMsgDetailOpenFile), R.drawable.msg_map, realValue = filePath, itemId = 1, mimeType = mime))
                }
            }
        }

        // 4. Bottom Datacenter
        val dcId = extractDatacenterId(owner)
        if (dcId > 0) {
            val dcLoc = getDcLocation(dcId)
            dcItem = DetailItem("DC", dcLoc, R.drawable.msg_satellite)
        }

        return Tuple4(headerItems, datesItems, middleItems, dcItem)
    }

    private fun extractAudioMiddleItems(
        items: MutableList<DetailItem>,
        msgObj: MessageObject,
        owner: TLRPC.Message,
        doc: TLRPC.Document?,
        filePath: String?,
        streamSize: Long,
        totalSize: Long,
        mime: String
    ) {
        val isVoice = msgObj.isVoice
        val typeStr = if (isVoice) LocaleController.getString(R.string.InuMsgDetailVoiceNote) else LocaleController.getString(R.string.InuMsgDetailMusicTrack)
        items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailAudioType), typeStr, R.drawable.msg_tone_on))

        if (mime.isNotEmpty()) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailMimeType), mime, R.drawable.msg_media))
        }

        if (!filePath.isNullOrEmpty()) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailFilePath), LocaleController.getString(R.string.InuMsgDetailOpenFile), R.drawable.msg_map, realValue = filePath, itemId = 1, mimeType = mime))
        }

        var attrTitle: String? = null
        var attrPerformer: String? = null
        var waveformLen = 0

        if (doc != null) {
            for (attr in doc.attributes) {
                if (attr is TLRPC.TL_documentAttributeAudio) {
                    attrTitle = attr.title
                    attrPerformer = attr.performer
                    if (attr.waveform != null) waveformLen = attr.waveform.size
                    break
                }
            }
        }

        var sampleRate = 0
        var audioChannels = 0
        var bitDepth = 0
        var audioBitrate = 0L

        if (!filePath.isNullOrEmpty() && File(filePath).exists()) {
            try {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(filePath)
                    if (attrPerformer.isNullOrEmpty()) {
                        attrPerformer = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    }
                    if (attrTitle.isNullOrEmpty()) {
                        attrTitle = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    }
                    val brStr = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    if (brStr != null) {
                        audioBitrate = Utilities.parseLong(brStr).coerceAtLeast(0L)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()?.let { sampleRate = it }
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()?.let { bitDepth = it }
                    }
                }
            } catch (_: Exception) {}

            try {
                val extractor = MediaExtractor()
                extractor.setDataSource(filePath)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val trkMime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (trkMime.startsWith("audio/")) {
                        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) audioChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) audioBitrate = format.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                        if (format.containsKey("bits-per-sample")) bitDepth = format.getInteger("bits-per-sample")
                        break
                    }
                }
                extractor.release()
            } catch (_: Exception) {}
        }

        if (!attrPerformer.isNullOrEmpty()) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailArtists), attrPerformer!!, R.drawable.msg_contacts))
        }
        if (!attrTitle.isNullOrEmpty()) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailTrackTitle), attrTitle!!, R.drawable.msg_contacts_name))
        }

        val duration = msgObj.duration.toInt()
        if (duration > 0) {
            formatDuration(duration)?.let { items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailDuration), it, R.drawable.msg2_animations)) }
        }

        if (audioBitrate <= 0L && totalSize > 0 && duration > 0) {
            audioBitrate = (totalSize * 8 / duration)
        }
        if (audioBitrate > 0) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailBitrate), "${(audioBitrate / 1000.0).roundToInt()} Kbps", R.drawable.msg_noise_on))
        }

        if (sampleRate > 0) {
            val srStr = if (sampleRate % 1000 == 0) "${sampleRate / 1000} kHz" else String.format(Locale.US, "%.1f kHz", sampleRate / 1000.0)
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailSampleRate), srStr, R.drawable.msg_photo_curve))
        }

        if (bitDepth > 0) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailBitDepth), "$bitDepth bit", R.drawable.msg_customize))
        }

        val isLossless = mime.contains("flac") || mime.contains("wav") || mime.contains("alac") || mime.contains("ape") || mime.contains("pcm") || mime.contains("aiff")
        val compStr = if (isLossless) LocaleController.getString(R.string.InuMsgDetailLossless) else LocaleController.getString(R.string.InuMsgDetailLossy)
        items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailCompression), compStr, R.drawable.msg_archive))

        if (streamSize > 0 && totalSize > 0) {
            val percent = ((streamSize.toDouble() / totalSize.toDouble()) * 100.0).toInt().coerceIn(0, 100)
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailStreamSize), "${AndroidUtilities.formatFileSize(streamSize)} ($percent%)", R.drawable.msg_filehq))
        }

        if (audioChannels > 0) {
            val chStr = when (audioChannels) {
                1 -> LocaleController.getString(R.string.InuMsgDetailMono)
                2 -> LocaleController.getString(R.string.InuMsgDetailStereo)
                else -> "$audioChannels ch"
            }
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailChannels), chStr, R.drawable.msg_call_speaker))
        }

        if (waveformLen > 0) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailWaveformLength), waveformLen.toString(), R.drawable.filled_chatlist_poll))
        }
    }

    private fun extractVideoMiddleItems(
        items: MutableList<DetailItem>,
        msgObj: MessageObject,
        owner: TLRPC.Message,
        doc: TLRPC.Document?,
        filePath: String?,
        mime: String
    ) {
        val vtype = when {
            msgObj.isRoundVideo -> LocaleController.getString(R.string.InuMsgDetailVideoRound)
            msgObj.isGif -> LocaleController.getString(R.string.InuMsgDetailVideoGif)
            else -> LocaleController.getString(R.string.InuMsgDetailVideoStandard)
        }
        items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailVideoType), vtype, R.drawable.msg_video))

        if (mime.isNotEmpty()) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailMimeType), mime, R.drawable.msg_media))
        }

        if (!filePath.isNullOrEmpty()) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailFilePath), LocaleController.getString(R.string.InuMsgDetailOpenFile), R.drawable.msg_map, realValue = filePath, itemId = 1, mimeType = mime))
        }

        var width = 0
        var height = 0
        var videoCodec: String? = null
        var supportsStreaming = false

        if (doc != null) {
            for (attr in doc.attributes) {
                if (attr is TLRPC.TL_documentAttributeVideo) {
                    width = attr.w
                    height = attr.h
                    videoCodec = attr.video_codec
                    supportsStreaming = attr.supports_streaming
                    break
                }
            }
        }

        var frameRate = 0f
        var videoBitrate = 0L
        var audioBitrate = 0L
        var audioChannels = 0

        if (!filePath.isNullOrEmpty() && File(filePath).exists()) {
            try {
                val extractor = MediaExtractor()
                extractor.setDataSource(filePath)
                var isVideo = false
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val trkMime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (trkMime.startsWith("video/")) {
                        isVideo = true
                        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            frameRate = try { format.getFloat(MediaFormat.KEY_FRAME_RATE) } catch (_: Exception) { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                        }
                        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) videoBitrate = format.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                        if (videoCodec.isNullOrEmpty()) videoCodec = trkMime.removePrefix("video/")
                    } else if (trkMime.startsWith("audio/")) {
                        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) audioBitrate = format.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) audioChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
                extractor.release()

                if (isVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    MediaMetadataRetriever().use { r ->
                        r.setDataSource(filePath)
                        val fc = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
                        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        if (fc > 0 && dur > 0) frameRate = (fc / (dur / 1000.0)).toFloat()
                    }
                }
            } catch (_: Exception) {}
        }

        if (width > 0 && height > 0) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailResolution), "${width}x${height}", R.drawable.media_crop))
        }

        val duration = msgObj.duration.toInt()
        if (duration > 0) {
            formatDuration(duration)?.let { items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailDuration), it, R.drawable.msg2_animations)) }
        }

        val totalSize = if (doc != null) doc.size else msgObj.size
        if (videoBitrate <= 0L && totalSize > 0 && duration > 0) {
            val totalBr = (totalSize * 8 / duration)
            videoBitrate = if (audioBitrate in 1 until totalBr) totalBr - audioBitrate else totalBr
        }

        if (videoBitrate > 0) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailBitrate), "${(videoBitrate / 1000.0).roundToInt()} Kbps", R.drawable.msg_noise_on))
        }
        if (audioBitrate > 0) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailAudioBitrate), "${(audioBitrate / 1000.0).roundToInt()} Kbps", R.drawable.msg_tone_on))
        }
        if (frameRate > 0) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailFps), String.format(Locale.US, "%.0f FPS", frameRate), R.drawable.msg_stats))
        }
        if (!videoCodec.isNullOrEmpty()) {
            val displayCodec = when {
                videoCodec!!.contains("avc", true) || videoCodec!!.contains("h264", true) -> "H.264 (AVC)"
                videoCodec!!.contains("hevc", true) || videoCodec!!.contains("h265", true) -> "H.265 (HEVC)"
                videoCodec!!.contains("vp9", true) -> "VP9"
                videoCodec!!.contains("av01", true) || videoCodec!!.contains("av1", true) -> "AV1"
                else -> videoCodec!!
            }
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailVideoCodec), displayCodec, R.drawable.msg_video))
        }
        if (audioChannels > 0) {
            val chStr = when (audioChannels) {
                1 -> LocaleController.getString(R.string.InuMsgDetailMono)
                2 -> LocaleController.getString(R.string.InuMsgDetailStereo)
                else -> "$audioChannels ch"
            }
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailChannels), chStr, R.drawable.msg_call_speaker))
        }
        if (supportsStreaming) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailSupportsStreaming), LocaleController.getString(R.string.InuYes), R.drawable.msg_media))
        }
    }

    private fun extractStickerMiddleItems(
        items: MutableList<DetailItem>,
        msgObj: MessageObject,
        owner: TLRPC.Message,
        doc: TLRPC.Document?,
        inputStickerSet: TLRPC.InputStickerSet?,
        associatedEmoji: String?,
        isAdaptiveTextColor: Boolean,
        filePath: String?,
        mime: String
    ) {
        val isCustom = doc?.attributes?.any { it is TLRPC.TL_documentAttributeCustomEmoji } == true
        val stkType = when {
            isCustom -> LocaleController.getString(R.string.InuMsgDetailCustomEmoji)
            mime.contains("webm") || msgObj.isVideoSticker -> LocaleController.getString(R.string.InuMsgDetailStickerVideo)
            mime.contains("tgsticker") || mime.contains("tgs") || msgObj.isAnimatedSticker -> LocaleController.getString(R.string.InuMsgDetailStickerAnimated)
            else -> LocaleController.getString(R.string.InuMsgDetailStickerStatic)
        }
        items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailStickerType), stkType, R.drawable.msg_sticker))

        if (mime.isNotEmpty()) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailMimeType), mime, R.drawable.msg_media))
        }

        var fileName: String? = null
        var width = 0
        var height = 0
        var videoCodec: String? = null

        if (doc != null) {
            for (attr in doc.attributes) {
                when (attr) {
                    is TLRPC.TL_documentAttributeFilename -> fileName = attr.file_name
                    is TLRPC.TL_documentAttributeImageSize -> { width = attr.w; height = attr.h }
                    is TLRPC.TL_documentAttributeVideo -> { width = attr.w; height = attr.h; videoCodec = attr.video_codec }
                    else -> {}
                }
            }
        }

        if (!fileName.isNullOrEmpty()) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailFileName), fileName, R.drawable.msg_log))
        }

        if (!filePath.isNullOrEmpty()) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailFilePath), LocaleController.getString(R.string.InuMsgDetailOpenFile), R.drawable.msg_map, realValue = filePath, itemId = 1, mimeType = mime))
        }

        if (!associatedEmoji.isNullOrEmpty()) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailAssociatedEmoji), associatedEmoji, R.drawable.msg_emoji_smiles))
        }

        if (isAdaptiveTextColor) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailAdaptiveTextColor), LocaleController.getString(R.string.InuYes), R.drawable.msg_palette))
        }

        if (width > 0 && height > 0) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailResolution), "${width}x${height}", R.drawable.media_crop))
        }

        val duration = msgObj.duration.toInt()
        if (duration > 0) {
            formatDuration(duration)?.let { items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailDuration), it, R.drawable.msg2_animations)) }
        }

        val totalSize = if (doc != null) doc.size else msgObj.size
        if (totalSize > 0 && duration > 0) {
            val br = (totalSize * 8 / duration)
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailBitrate), "${(br / 1000.0).roundToInt()} Kbps", R.drawable.msg_noise_on))
        }

        if (!videoCodec.isNullOrEmpty()) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailVideoCodec), videoCodec, R.drawable.msg_video))
        }
    }

    private fun extractPhotoMiddleItems(
        items: MutableList<DetailItem>,
        msgObj: MessageObject,
        photo: TLRPC.Photo?,
        filePath: String?,
        mime: String
    ) {
        if (mime.isNotEmpty()) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailMimeType), mime, R.drawable.msg_media))
        }

        if (!filePath.isNullOrEmpty()) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailFilePath), LocaleController.getString(R.string.InuMsgDetailOpenFile), R.drawable.msg_map, realValue = filePath, itemId = 1, mimeType = mime))
        }

        var width = 0
        var height = 0
        if (photo != null) {
            val sz = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, Int.MAX_VALUE)
            if (sz != null) { width = sz.w; height = sz.h }
        }

        if (width > 0 && height > 0) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailResolution), "${width}x${height}", R.drawable.media_crop))
        }
    }

    private fun extractPollMiddleItems(items: MutableList<DetailItem>, media: TLRPC.TL_messageMediaPoll) {
        val poll = media.poll ?: return
        val question = poll.question?.text ?: ""
        if (question.isNotEmpty()) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailPoll), question, R.drawable.msg_emoji_question))
        }

        val desc = media.results?.solution ?: ""
        if (desc.isNotEmpty()) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailPollDescription), desc, R.drawable.msg_log))
        }

        items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailPollVoterNames), boolStr(poll.public_voters), R.drawable.msg_view_file))
        items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailPollMultipleChoice), boolStr(poll.multiple_choice), R.drawable.msg_select))
        items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailPollAddOptions), boolStr(poll.open_answers), R.drawable.msg_addbot))
        items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailPollAllowRevoting), boolStr(!poll.revoting_disabled), R.drawable.msg_replace))
        items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailPollShuffle), boolStr(poll.shuffle_answers), R.drawable.msg_forward_replace))
        items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailPollQuiz), boolStr(poll.quiz), R.drawable.floating_check))

        if (poll.close_period > 0) {
            val dur = formatDuration(poll.close_period) ?: "${poll.close_period}s"
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailPollDuration), dur, R.drawable.msg_stories_timer))
        }

        if (poll.close_date > 0) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailPollAutoCloseDate), formatDate(poll.close_date), R.drawable.msg_autodelete))
        }

        if (poll.hide_results_until_close) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailPollHideResults), LocaleController.getString(R.string.InuYes), R.drawable.msg_stories_stealth))
        }

        val statusStr = if (poll.closed) LocaleController.getString(R.string.InuMsgDetailPollClosed) else LocaleController.getString(R.string.InuMsgDetailPollOpen)
        items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailPollStatus), statusStr, R.drawable.filled_chatlist_poll))

        val results = media.results
        if (results != null && results.total_voters > 0) {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailPollTotalVoters), results.total_voters.toString(), R.drawable.msg_stats))
        }
    }

    private fun extractServiceMiddleItems(activity: ChatActivity, items: MutableList<DetailItem>, action: TLRPC.MessageAction) {
        items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailServiceAction), action.javaClass.simpleName, R.drawable.msg_settings))
        readField(action, "title")?.toString()?.takeIf { it.isNotEmpty() }?.let {
            items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailServiceTitle), it, R.drawable.msg_edit))
        }
        val users = readField(action, "users") ?: readField(action, "user_id")
        if (users != null) {
            val mc = MessagesController.getInstance(activity.currentAccount)
            val userStr = when (users) {
                is List<*> -> users.filterNotNull().joinToString(", ") { formatUserRef(mc, it) }
                is ArrayList<*> -> users.filterNotNull().joinToString(", ") { formatUserRef(mc, it) }
                else -> formatUserRef(mc, users)
            }
            if (userStr.isNotEmpty()) {
                items.add(DetailItem(LocaleController.getString(R.string.InuMsgDetailTargetUsers), userStr, R.drawable.msg_groups))
            }
        }
    }

    private fun formatUserRef(mc: MessagesController, userObj: Any): String {
        val id = when (userObj) {
            is Long -> userObj
            is Int -> userObj.toLong()
            else -> userObj.toString().toLongOrNull() ?: return userObj.toString()
        }
        val user = mc.getUser(id) ?: return id.toString()
        return ContactsController.formatName(user.first_name, user.last_name) + (user.username?.let { " (@$it)" } ?: "")
    }

    private fun hasStickerAttr(doc: TLRPC.Document): Boolean =
        doc.attributes.any { it is TLRPC.TL_documentAttributeSticker || it is TLRPC.TL_documentAttributeCustomEmoji }

    private fun extractDatacenterId(owner: TLRPC.Message): Int {
        val media = MessageObject.getMedia(owner) ?: return 0
        val photo = media.webpage?.photo ?: media.photo
        if (photo != null && photo.dc_id > 0) return photo.dc_id
        val doc = media.webpage?.document ?: media.document
        if (doc != null && doc.dc_id > 0) return doc.dc_id
        return 0
    }

    private fun getDcLocation(dcId: Int): String {
        return when (dcId) {
            1 -> "DC1, Miami (US)"
            2 -> "DC2, Amsterdam (NL)"
            3 -> "DC3, Miami (US)"
            4 -> "DC4, Amsterdam (NL)"
            5 -> "DC5, Singapore (SG)"
            else -> "DC$dcId"
        }
    }

    private fun formatDate(timestamp: Int): String {
        val date = Date(timestamp.toLong() * 1000L)
        val locale = LocaleController.getInstance()
        return LocaleController.formatString(
            R.string.formatDateAtTime,
            locale.formatterYear.format(date),
            locale.formatterDay.format(date)
        )
    }

    private fun formatDuration(seconds: Int): String? {
        if (seconds <= 0) return null
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainder = seconds % 60
        return when {
            hours > 0 -> "%d:%02d:%02d".format(Locale.US, hours, minutes, remainder)
            minutes > 0 -> "%d:%02d".format(Locale.US, minutes, remainder)
            else -> "0:%02d".format(Locale.US, remainder)
        }
    }

    private fun boolStr(value: Boolean): String =
        LocaleController.getString(if (value) R.string.InuYes else R.string.InuNo)

    private fun extractStickerSetOwnerId(setId: Long): Long {
        var ownerId = setId ushr 32
        val extByte = (setId shr 24) and 0xff
        val sepByte = (setId shr 16) and 0xff
        if (sepByte == 0x3fL) ownerId = ownerId or 0x80000000L
        if (extByte != 0L) ownerId += 0x100000000L
        return ownerId
    }

    private fun readField(value: Any?, name: String): Any? = try {
        value?.javaClass?.getField(name)?.get(value)
    } catch (_: Exception) {
        null
    }

    data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
