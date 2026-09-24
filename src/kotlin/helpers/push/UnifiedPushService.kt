package desu.inugram.helpers.push

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.Utilities
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

// entiny: receiver the distributor talks to; registered in the manifest by the unified-push patch
class UnifiedPushService : PushService() {

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        AndroidUtilities.runOnUIThread {
            ApplicationLoader.postInitApplication()
            Utilities.globalQueue.postRunnable { UnifiedPushHelper.onEndpoint(endpoint.url) }
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        AndroidUtilities.runOnUIThread {
            ApplicationLoader.postInitApplication()
            Utilities.stageQueue.postRunnable { UnifiedPushHelper.onWake() }
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Utilities.globalQueue.postRunnable {
            UnifiedPushHelper.onLost(retry = reason == FailedReason.NETWORK || reason == FailedReason.INTERNAL_ERROR)
        }
    }

    override fun onUnregistered(instance: String) {
        Utilities.globalQueue.postRunnable { UnifiedPushHelper.onLost(retry = true) }
    }
}
