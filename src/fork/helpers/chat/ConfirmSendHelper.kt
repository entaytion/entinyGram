package desu.inugram.helpers.chat

import android.app.Activity
import android.content.Context
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AlertsCreator
import org.telegram.ui.ContentPreviewViewer

object ConfirmSendHelper {

    @JvmStatic
    fun openPreview(
        activity: Activity?,
        delegate: ContentPreviewViewer.ContentPreviewViewerDelegate?,
        media: Any?,
        parent: Any?,
        resourcesProvider: Theme.ResourcesProvider?,
    ): Boolean {
        val viewer = ContentPreviewViewer.getInstance()
        if (viewer.inu_isSending) return false
        if (activity == null || delegate == null) return false
        if (viewer.isVisible && !viewer.inu_confirmMode) return false
        val inlineResult = media as? TLRPC.BotInlineResult
        val document = media as? TLRPC.Document ?: inlineResult?.document
        if (inlineResult != null && document == null && inlineResult.content == null) return false
        val isGif = inlineResult != null || document?.let { MessageObject.isGifDocument(it) } == true
        if (document == null && inlineResult == null) return false
        if (!isGif && document?.attributes?.none { it is TLRPC.TL_documentAttributeSticker && it.stickerset != null } != false) return false
        val emoji = document?.attributes?.firstNotNullOfOrNull { (it as? TLRPC.TL_documentAttributeSticker)?.alt }
        viewer.inu_confirmMode = true
        if (viewer.isVisible) viewer.inu_resetMenu()
        viewer.setParentActivity(activity)
        viewer.setDelegate(delegate)
        viewer.open(
            document, null, emoji, null, if (document != null) null else inlineResult,
            if (isGif) ContentPreviewViewer.CONTENT_TYPE_GIF else ContentPreviewViewer.CONTENT_TYPE_STICKER,
            false, parent, resourcesProvider, 1
        )
        return true
    }

    @JvmStatic
    fun confirmRecorded(
        context: Context,
        resourcesProvider: Theme.ResourcesProvider?,
        currentAccount: Int,
        dialogId: Long,
        onConfirm: Utilities.Callback<Long>,
    ) {
        if (AlertsCreator.needsPaidMessageAlert(currentAccount, dialogId)) {
            AlertsCreator.ensurePaidMessageConfirmation(currentAccount, dialogId, 1, onConfirm)
        }
    }
}
