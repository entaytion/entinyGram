@file:Suppress("DEPRECATION")

package desu.inugram.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import desu.inugram.helpers.ShortcutHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.SharedConfig
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AlertsCreator
import org.telegram.ui.LaunchActivity

// entiny: standalone activity keeps mainInterfacePaused active so accounts cannot report online before selection
class AccountPickerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ApplicationLoader.postInitApplication()
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        setContentView(View(this))

        AndroidUtilities.fillStatusBarHeight(this, false)
        Theme.createDialogsResources(this)

        if (SharedConfig.passcodeHash.isNotEmpty()) {
            openApp(Intent(this, LaunchActivity::class.java).setAction(ShortcutHelper.SWITCH_ACCOUNT_ACTION))
            return
        }

        if (ShortcutHelper.countSelectableAccounts() < 2) {
            openApp(Intent(this, LaunchActivity::class.java))
            return
        }

        val dialog = AlertsCreator.createAccountSelectDialog(this) { account ->
            openApp(Intent(this, LaunchActivity::class.java).putExtra("currentAccount", account))
        }
        if (dialog == null) {
            openApp(Intent(this, LaunchActivity::class.java))
            return
        }
        dialog.setOnDismissListener { finish() }
        dialog.show()
    }

    private fun openApp(intent: Intent) {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
