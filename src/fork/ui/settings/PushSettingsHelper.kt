package desu.inugram.ui.settings

import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import desu.inugram.InuConfig
import desu.inugram.helpers.push.UnifiedPushHelper
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.EditTextBoldCursor

object PushSettingsHelper {
    fun toggleBackgroundService(page: SchemaPageActivity, view: View) {
        if (InuConfig.FOREGROUND_PUSH_SERVICE.value) {
            InuConfig.FOREGROUND_PUSH_SERVICE.value = false
            (view as? NotificationsCheckCell)?.isChecked = false
            restartPushService()
            return
        }
        val ctx = page.parentActivity ?: return
        AlertDialog.Builder(ctx, page.resourceProvider)
            .setTitle(LocaleController.getString(R.string.InuBackgroundService))
            .setMessage(LocaleController.getString(R.string.InuBackgroundServiceAlert))
            .setPositiveButton(LocaleController.getString(R.string.InuBackgroundServiceEnable)) { _, _ ->
                InuConfig.FOREGROUND_PUSH_SERVICE.value = true
                (view as? NotificationsCheckCell)?.isChecked = true
                restartPushService()
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show()
    }

    fun openServiceChannelSettings(page: SchemaPageActivity) {
        val ctx = page.parentActivity ?: return
        val intent = android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, "push_service_channel")
        runCatching { ctx.startActivity(intent) }
    }

    fun restartPushService() {
        val app = ApplicationLoader.applicationContext
        app.stopService(android.content.Intent(app, org.telegram.messenger.NotificationsService::class.java))
        ApplicationLoader.startPushService()
    }

    fun toggleUnifiedPush(page: SchemaPageActivity) {
        val enable = !UnifiedPushHelper.isEnabled()
        if (enable && UnifiedPushHelper.distributors().isEmpty()) {
            BulletinFactory.of(page).createErrorBulletin(LocaleController.getString(R.string.InuUnifiedPushNoDistributorInfo)).show()
            return
        }
        UnifiedPushHelper.setEnabled(enable)
        page.refresh()
    }

    fun pickDistributor(page: SchemaPageActivity, anchor: View) {
        val all = UnifiedPushHelper.distributors()
        if (all.isEmpty()) {
            BulletinFactory.of(page).createErrorBulletin(LocaleController.getString(R.string.InuUnifiedPushNoDistributorInfo)).show()
            return
        }
        val current = UnifiedPushHelper.currentDistributor()
        RadioItemOptions.show(page, anchor, all.map { appLabel(it) }, all.indexOf(current).coerceAtLeast(0)) { which ->
            val picked = all.getOrNull(which) ?: return@show
            if (picked != current) {
                UnifiedPushHelper.setEnabled(true, picked)
                page.refresh()
            }
        }
    }

    fun editGateway(page: SchemaPageActivity) {
        val ctx = page.context ?: return
        val current = UnifiedPushHelper.getGateway()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(8f), dp(24f), 0)
        }
        val input = EditTextBoldCursor(ctx).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint))
            setCursorColor(Theme.getColor(Theme.key_dialogTextBlack))
            setCursorSize(dp(20f))
            setCursorWidth(1.5f)
            hint = "https://p2p.belloworld.it/"
            setText(current)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            textSize = 16f
        }
        container.addView(input, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8f)
        })
        val infoText = TextView(ctx).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextGray2))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            text = LocaleController.getString(R.string.InuUnifiedPushGatewayInfo)
        }
        container.addView(infoText, LinearLayout.LayoutParams(-1, -2))

        val builder = AlertDialog.Builder(ctx, page.resourceProvider)
            .setTitle(LocaleController.getString(R.string.InuUnifiedPushGateway))
            .setView(container)
            .setPositiveButton(LocaleController.getString(R.string.OK)) { _, _ ->
                val entered = input.text.toString().trim()
                UnifiedPushHelper.setGateway(entered)
                page.refresh()
            }
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)

        if (current.isNotEmpty()) {
            builder.setNeutralButton(LocaleController.getString(R.string.Reset)) { _, _ ->
                UnifiedPushHelper.setGateway("")
                page.refresh()
            }
        } else {
            builder.setNeutralButton(LocaleController.getString(R.string.InuUnifiedPushGatewayDefault)) { _, _ ->
                UnifiedPushHelper.setGateway("https://p2p.belloworld.it/")
                page.refresh()
            }
        }
        page.showDialog(builder.create())
    }

    fun appLabel(packageName: String): CharSequence = try {
        val pm = ApplicationLoader.applicationContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0))
    } catch (_: Exception) {
        packageName
    }
}
