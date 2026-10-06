package desu.inugram.helpers.media

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import desu.inugram.helpers.DebugLogUtils
import org.telegram.messenger.FileLog
import org.telegram.messenger.MediaController
import org.telegram.ui.Components.Crop.CropView
import org.telegram.ui.Components.PhotoCropView

object CropDebugHelper {
    @JvmStatic
    fun isEnabled(): Boolean = DebugLogUtils.isEnabled()

    private fun log(message: String) {
        FileLog.d("InuCrop $message from ${DebugLogUtils.getCaller()}")
    }

    private fun describe(cropView: CropView?): String {
        if (cropView == null) return "cropView=null"
        val area = cropView.areaView
        val rect = RectF().also(area::getCropRect)
        val state = cropView.state?.let {
            "${it.width}x${it.height} scale=${it.scale} min=${it.minimumScale} rot=${it.rotation} orient=${it.orientation}"
        } ?: "null"
        return "attached=${cropView.isAttachedToWindow} view=${cropView.measuredWidth}x${cropView.measuredHeight}" +
            " area=${area.measuredWidth}x${area.measuredHeight} rect=${rect.toShortString()} aspect=${area.aspectRatio}" +
            " current=${cropView.currentWidth}x${cropView.currentHeight} dismissing=${cropView.inu_dismissing} state=$state"
    }

    private fun describe(cropState: MediaController.CropState?): String {
        if (cropState == null) return "null"
        return "pw=${cropState.cropPw} ph=${cropState.cropPh} px=${cropState.cropPx} py=${cropState.cropPy}" +
            " scale=${cropState.cropScale} rotate=${cropState.cropRotate} tr=${cropState.transformRotation}" +
            " lock=${cropState.lockedAspectRatio} freeform=${cropState.freeform}"
    }

    private fun describe(photoCropView: PhotoCropView?, containerView: View?): String {
        if (photoCropView == null) return "photoCropView=null"
        return "inContainer=${photoCropView.parent != null && photoCropView.parent === containerView}" +
            " visibility=${photoCropView.visibility} ${describe(photoCropView.cropView)}"
    }

    @JvmStatic
    fun onReparent(from: Activity?, to: Activity, photoCropView: PhotoCropView?, containerView: View?) {
        if (!isEnabled()) return
        log("reparent from=$from to=$to ${describe(photoCropView, containerView)}")
    }

    @JvmStatic
    fun onCreateCropView(photoCropView: PhotoCropView?, containerView: View?) {
        if (!isEnabled()) return
        log("createCropView reused=${photoCropView != null} ${describe(photoCropView, containerView)}")
    }

    @JvmStatic
    fun onCropOpen(photoCropView: PhotoCropView?, containerView: View?, bitmap: Bitmap?, orientation: Int, cropState: MediaController.CropState?) {
        if (!isEnabled()) return
        log("open bitmap=${bitmap?.let { "${it.width}x${it.height} recycled=${it.isRecycled}" }} orientation=$orientation" +
            " restore=[${describe(cropState)}] ${describe(photoCropView, containerView)}")
    }

    @JvmStatic
    fun onImageSetInCrop(bitmap: Bitmap?, orientation: Int, cropView: CropView?) {
        if (!isEnabled()) return
        log("image set in crop bitmap=${bitmap?.let { "${it.width}x${it.height}" }} orientation=$orientation ${describe(cropView)}")
    }

    @JvmStatic
    fun onSetBitmap(cropView: CropView, same: Boolean, restoreState: MediaController.CropState?) {
        if (!isEnabled()) return
        log("setBitmap same=$same restore=[${describe(restoreState)}] ${describe(cropView)}")
    }

    @JvmStatic
    fun onPreDrawReset(cropView: CropView) {
        if (!isEnabled()) return
        log("preDraw reset ${describe(cropView)}")
    }

    @JvmStatic
    fun onApplyToCropState(cropView: CropView, cropState: MediaController.CropState, width: Int, height: Int, sc: Float) {
        if (!isEnabled()) return
        log("applyToCropState out=${width}x$height sc=$sc result=[${describe(cropState)}] ${describe(cropView)}")
    }

    @JvmStatic
    fun onReloadCurrentPhoto(editMode: Int, switchingToMode: Int, reloadImage: Boolean) {
        if (!isEnabled()) return
        log("gallery reload editMode=$editMode switchingToMode=$switchingToMode reloadImage=$reloadImage")
    }
}
