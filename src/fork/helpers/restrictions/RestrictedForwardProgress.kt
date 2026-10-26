package desu.inugram.helpers.restrictions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R

// entiny: progress notification with cancel for downloading protected media before a copy-forward, idea from NagramXTurbo (@temporaryna)
internal class RestrictedForwardProgress(private val totalBytes: Long, private val fileCount: Int) : BroadcastReceiver() {
    @Volatile
    var canceled = false
        private set

    private val loaded = ConcurrentHashMap<String, Long>()
    private var lastNotifyAt = 0L
    private val context get() = ApplicationLoader.applicationContext
    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val action = "$CANCEL_ACTION.$notificationId"

    fun start() {
        ContextCompat.registerReceiver(context, this, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        notifyProgress(force = true)
    }

    fun update(key: String, bytes: Long) {
        loaded[key] = bytes
        notifyProgress(force = false)
    }

    fun finish() {
        runCatching { context.unregisterReceiver(this) }
        manager.cancel(notificationId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        canceled = true
        finish()
    }

    private fun notifyProgress(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastNotifyAt < THROTTLE_MS) return
        lastNotifyAt = now
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, LocaleController.getString(R.string.InuForwardChannel), NotificationManager.IMPORTANCE_LOW)
                )
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val cancel = PendingIntent.getBroadcast(context, notificationId, Intent(action).setPackage(context.packageName), flags)
            val done = loaded.values.sum().coerceAtMost(totalBytes)
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(LocaleController.getString(R.string.InuForwardProgressTitle))
                .setContentText(
                    LocaleController.formatString(
                        R.string.InuForwardProgressText, fileCount,
                        AndroidUtilities.formatFileSize(done), AndroidUtilities.formatFileSize(totalBytes),
                    )
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, LocaleController.getString(R.string.Cancel), cancel)
            if (totalBytes > 0) builder.setProgress(100, (done * 100 / totalBytes).toInt(), false) else builder.setProgress(0, 0, true)
            manager.notify(notificationId, builder.build())
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val CHANNEL_ID = "inu_restricted_forward"
        private const val CANCEL_ACTION = "desu.inugram.RESTRICTED_FORWARD_CANCEL"
        private const val THROTTLE_MS = 700L
        private const val notificationId = 0x1e80
    }
}
