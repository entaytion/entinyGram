package desu.inugram.helpers.chat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import desu.inugram.InuConfig
import desu.inugram.InuConfig.AttachCameraModeItem.Companion.FAB
import desu.inugram.InuConfig.AttachCameraModeItem.Companion.INSTANT
import desu.inugram.InuConfig.AttachCameraModeItem.Companion.STATIC
import desu.inugram.InuConfig.AttachCameraModeItem.Companion.TAB
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.ChatAttachAlert
import org.telegram.ui.Components.ChatAttachAlertPhotoLayout
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.Locale

object AttachCameraHelper {
    private var pendingOpen: WeakReference<ChatAttachAlert>? = null

    @JvmStatic
    fun shouldMirrorFront(isFrontface: Boolean): Boolean =
        isFrontface && InuConfig.ATTACH_CAMERA_MIRROR_FRONT.value

    @JvmStatic
    fun mirrorPhotoFile(file: File): Boolean {
        try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return false
            val matrix = Matrix().apply { postScale(-1f, 1f) }
            val mirrored = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (mirrored !== bitmap) bitmap.recycle()
            FileOutputStream(file).use { out ->
                mirrored.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
                out.fd.sync()
            }
            val size = (AndroidUtilities.getPhotoSize() / AndroidUtilities.density).toInt()
            val key = String.format(Locale.US, "%s@%d_%d", Utilities.MD5(file.absolutePath), size, size)
            ImageLoader.getInstance().putImageToCache(BitmapDrawable(mirrored), key, false)
            return true
        } catch (t: Throwable) {
            Log.d("InuMirror", "mirrorPhotoFile failed", t)
            return false
        }
    }

    @JvmStatic
    fun isInstant(): Boolean = InuConfig.ATTACH_CAMERA_MODE.value == INSTANT

    @JvmStatic
    fun isSquareTile(): Boolean = InuConfig.ATTACH_CAMERA_SQUARE.value

    @JvmStatic
    fun cameraTileHeight(itemSize: Int, gap: Int): Int =
        if (isSquareTile()) itemSize else itemSize * 2 + gap

    fun isFab(): Boolean = InuConfig.ATTACH_CAMERA_MODE.value == FAB

    fun isTab(): Boolean = InuConfig.ATTACH_CAMERA_MODE.value == TAB

    @JvmStatic
    fun hasCameraOutsideGrid(alert: ChatAttachAlert): Boolean {
        val mode = InuConfig.ATTACH_CAMERA_MODE.value
        if (mode == INSTANT || mode == STATIC) return false
        val chatActivity = alert.baseFragment as? ChatActivity ?: return false
        return chatActivity.chatActivityEnterView != null
    }

    @JvmStatic
    fun hasCameraTab(alert: ChatAttachAlert): Boolean =
        isTab() && alert.photoLayout?.inu_cameraOutsideGrid == true

    @JvmStatic
    fun addFab(alert: ChatAttachAlert, container: FrameLayout, resourcesProvider: Theme.ResourcesProvider?) {
        if (!isFab() || alert.photoLayout?.inu_cameraOutsideGrid != true) return

        val fab = ImageView(container.context).apply {
            setImageResource(R.drawable.camera)
            colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = LocaleController.getString(R.string.InuAttachCamera)
            AttachFabHelper.applyFabStyle(this, resourcesProvider)
            setOnClickListener { openCamera(alert) }
            setOnLongClickListener { openSystemCamera(alert); true }
        }

        AttachFabHelper.install(alert, container, fab, AttachFabHelper.createLayoutParams())
    }

    @JvmStatic
    fun openCamera(alert: ChatAttachAlert) {
        val layout = alert.photoLayout ?: return
        if (alert.currentAttachLayout !== layout) {
            pendingOpen = WeakReference(alert)
            alert.showLayout(layout)
            return
        }
        openCameraNow(alert, layout)
    }

    @JvmStatic
    fun onLayoutShown(layout: ChatAttachAlertPhotoLayout) {
        val alert = pendingOpen?.get() ?: return
        if (alert.photoLayout !== layout) return
        pendingOpen = null
        openCameraNow(alert, layout)
    }

    @JvmStatic
    fun onLongClickTab(alert: ChatAttachAlert, view: View): Boolean {
        if (view.tag != ChatAttachAlert.inu_TAG_CAMERA) return false
        openSystemCamera(alert)
        return true
    }

    @JvmStatic
    fun openSystemCamera(alert: ChatAttachAlert) {
        alert.delegate?.didPressedButton(0, false, true, 0, 0, 0L, alert.isCaptionAbove, false, 0L)
    }

    private fun openCameraNow(alert: ChatAttachAlert, layout: ChatAttachAlertPhotoLayout) {
        if (ContextCompat.checkSelfPermission(layout.context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            openSystemCamera(alert)
            return
        }
        layout.openCameraByClick()
    }
}
