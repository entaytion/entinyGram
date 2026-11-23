package desu.inugram.helpers.security

import android.accounts.AccountManager
import desu.inugram.InuConfig
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.ContactsController
import org.telegram.messenger.UserConfig

object SystemAccountHelper {
    private const val ACCOUNT_TYPE = "ua.entaytion.entinygram"

    @JvmStatic
    fun isHidden(): Boolean = InuConfig.HIDE_SYSTEM_ACCOUNT.getValue()

    // entiny: call on the global queue, removeAccount is async and checkAppAccount already runs there
    @JvmStatic
    fun purgeIfHidden(): Boolean {
        if (!isHidden()) return false
        try {
            val am = AccountManager.get(ApplicationLoader.applicationContext)
            for (account in am.getAccountsByType(ACCOUNT_TYPE)) {
                try {
                    am.removeAccount(account, null, null)
                } catch (_: Exception) {
                }
            }
        } catch (_: Throwable) {
        }
        return true
    }

    @JvmStatic
    fun onToggled() {
        for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (UserConfig.getInstance(account).isClientActivated) {
                ContactsController.getInstance(account).checkAppAccount()
            }
        }
    }
}
