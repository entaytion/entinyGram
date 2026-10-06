package desu.inugram.helpers.chat

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_bots
import org.telegram.tgnet.tl.TL_iv
import org.telegram.ui.ChatActivity
import org.telegram.ui.PhotoViewer
import java.io.File

object BotDescriptionMediaHelper {
    @JvmStatic
    fun openMedia(fragment: ChatActivity, botInfo: TL_bots.BotInfo?, provider: PhotoViewer.PhotoViewerProvider) {
        if (botInfo == null) return
        val media: TLObject = botInfo.description_document ?: botInfo.description_photo ?: return
        val block = when (media) {
            is TLRPC.Document -> TL_iv.pageBlockVideo().also { it.video_id = media.id }
            else -> TL_iv.pageBlockPhoto().also { it.photo_id = (media as TLRPC.Photo).id }
        }
        val attach = if (media is TLRPC.Photo) FileLoader.getClosestPhotoSizeWithSize(media.sizes, AndroidUtilities.getPhotoSize()) else media
        val thumb = attach as? TLRPC.PhotoSize ?: FileLoader.getClosestPhotoSizeWithSize((media as TLRPC.Document).thumbs, 320)
        val adapter = object : PhotoViewer.PageBlocksAdapter {
            override fun getItemsCount() = 1
            override fun get(index: Int) = block
            override fun getAll() = listOf(block)
            override fun isVideo(index: Int) = media is TLRPC.Document
            override fun getMedia(index: Int) = media
            override fun getFile(index: Int): File? = FileLoader.getInstance(fragment.currentAccount).getPathToAttach(attach, true)
            override fun getFileName(index: Int): String = FileLoader.getAttachFileName(attach)
            override fun getCaption(index: Int): CharSequence? = null
            override fun getFileLocation(media: TLObject?, size: IntArray): TLRPC.PhotoSize? {
                size[0] = thumb?.size?.takeIf { it != 0 } ?: -1
                return thumb
            }
            override fun updateSlideshowCell(currentPageBlock: TL_iv.PageBlock?) {}
            override fun getParentObject() = botInfo
            override fun isHardwarePlayer(index: Int) = false
        }
        val viewer = PhotoViewer.getInstance()
        viewer.setParentActivity(fragment)
        viewer.openPhoto(0, adapter, provider)
    }
}
