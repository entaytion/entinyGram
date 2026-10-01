package desu.inugram.ui.spoof

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.spoof.DeviceIdentity
import desu.inugram.helpers.spoof.DeviceIdentityProvider
import desu.inugram.helpers.spoof.DeviceIdentityValidator
import desu.inugram.helpers.spoof.DevicePresets
import desu.inugram.ui.settings.RadioItemOptions
import desu.inugram.ui.settings.SettingsPageActivity
import desu.inugram.ui.showInputDialog
import android.os.Build
import org.telegram.messenger.AndroidUtilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.tl.TL_account
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class DeviceSpoofSettingsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = getString(R.string.InuDeviceSpoof)

    private val isCustom: Boolean get() = DevicePresets.find(InuConfig.DEVICE_SPOOF_PRESET.value) == null

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(mkTwoLineCheckItem(TOGGLE_SPOOF, R.string.InuDeviceSpoof, R.string.InuDeviceSpoofInfo, InuConfig.DEVICE_SPOOF.value))
        if (!InuConfig.DEVICE_SPOOF.value) {
            items.add(UItem.asShadow(null))
            return
        }
        items.add(UItem.asButton(BUTTON_PRESET, getString(R.string.InuDeviceSpoofPreset), presetLabel()))
        if (isCustom) {
            items.add(UItem.asButton(BUTTON_MODEL, getString(R.string.InuDeviceSpoofModel), valueOrNotSet(InuConfig.DEVICE_SPOOF_MODEL.value)))
            items.add(UItem.asButton(BUTTON_SYSTEM, getString(R.string.InuDeviceSpoofSystem), valueOrNotSet(InuConfig.DEVICE_SPOOF_SYSTEM.value)))
        }
        items.add(UItem.asButton(BUTTON_APP, getString(R.string.InuDeviceSpoofApp), valueOrNotSet(InuConfig.DEVICE_SPOOF_APP.value)))
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(getString(R.string.InuDeviceSpoofExtras)))
        items.add(mkTwoLineCheckItem(TOGGLE_HIDE_TZ, R.string.InuDeviceSpoofHideTz, R.string.InuDeviceSpoofHideTzInfo, InuConfig.DEVICE_SPOOF_HIDE_TZ.value))
        items.add(mkTwoLineCheckItem(TOGGLE_HIDE_INSTALLER, R.string.InuDeviceSpoofHideInstaller, R.string.InuDeviceSpoofHideInstallerInfo, InuConfig.DEVICE_SPOOF_HIDE_INSTALLER.value))
        // entiny: a lone header over a shadow renders as a stray card, so the preview is one captioned shadow instead
        items.add(UItem.asShadow(getString(R.string.InuDeviceSpoofPreview) + ":\n" + previewText()))

        items.add(UItem.asButton(BUTTON_APPLY, R.drawable.msg_retry, getString(R.string.InuDeviceSpoofApply)))
        items.add(UItem.asButton(BUTTON_VERIFY, R.drawable.msg_info, getString(R.string.InuDeviceSpoofVerify)))
        items.add(UItem.asShadow(getString(R.string.InuDeviceSpoofWarning)))
    }

    private fun presetLabel(): String =
        DevicePresets.find(InuConfig.DEVICE_SPOOF_PRESET.value)?.name ?: getString(R.string.InuDeviceSpoofPresetCustom)

    private fun valueOrNotSet(value: String): String =
        value.ifBlank { getString(R.string.InuDeviceSpoofNotSet) }

    private fun previewText(): String {
        val real = DeviceIdentity(Build.MANUFACTURER + Build.MODEL, "SDK " + Build.VERSION.SDK_INT, null)
        val effective = DeviceIdentityProvider.resolve(real)
        return listOfNotNull(effective.deviceModel, effective.systemVersion, effective.appVersion).joinToString(", ")
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            TOGGLE_SPOOF -> {
                val new = InuConfig.DEVICE_SPOOF.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                listView?.adapter?.update(true)
            }

            BUTTON_PRESET -> {
                val presets = DevicePresets.all
                val labels = presets.map { it.name } + getString(R.string.InuDeviceSpoofPresetCustom) + getString(R.string.InuDeviceSpoofPresetReal)
                val current = presets.indexOfFirst { it.id == InuConfig.DEVICE_SPOOF_PRESET.value }.let { if (it < 0) presets.size else it }
                RadioItemOptions.show(this, view, labels, current) { which ->
                    when {
                        which < presets.size -> InuConfig.DEVICE_SPOOF_PRESET.value = presets[which].id
                        which == presets.size -> InuConfig.DEVICE_SPOOF_PRESET.value = DevicePresets.CUSTOM_ID
                        else -> InuConfig.DEVICE_SPOOF.value = false
                    }
                    listView?.adapter?.update(true)
                }
            }

            BUTTON_MODEL -> edit(R.string.InuDeviceSpoofModel, InuConfig.DEVICE_SPOOF_MODEL, DeviceIdentityValidator.MODEL_MAX, false, DeviceIdentityValidator::model)

            BUTTON_SYSTEM -> edit(R.string.InuDeviceSpoofSystem, InuConfig.DEVICE_SPOOF_SYSTEM, DeviceIdentityValidator.SYSTEM_MAX, false, DeviceIdentityValidator::system)

            BUTTON_APP -> edit(R.string.InuDeviceSpoofApp, InuConfig.DEVICE_SPOOF_APP, DeviceIdentityValidator.APP_MAX, true, DeviceIdentityValidator::app)

            BUTTON_APPLY -> parentActivity?.let { InuUtils.restartApp(it) }

            BUTTON_VERIFY -> verify()

            TOGGLE_HIDE_TZ -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.DEVICE_SPOOF_HIDE_TZ.toggle()
            }

            TOGGLE_HIDE_INSTALLER -> {
                (view as? NotificationsCheckCell)?.isChecked = InuConfig.DEVICE_SPOOF_HIDE_INSTALLER.toggle()
            }
        }
    }

    private fun verify() {
        val ctx = parentActivity ?: return
        ConnectionsManager.getInstance(currentAccount).sendRequest(TL_account.getAuthorizations()) { response, error ->
            AndroidUtilities.runOnUIThread {
                val current = (response as? TL_account.authorizations)?.authorizations?.firstOrNull { it.current }
                if (error != null || current == null) {
                    BulletinFactory.of(this).createErrorBulletin(getString(R.string.InuDeviceSpoofVerifyFailed)).show()
                    return@runOnUIThread
                }
                val real = DeviceIdentity(Build.MANUFACTURER + Build.MODEL, "SDK " + Build.VERSION.SDK_INT, null)
                val expected = DeviceIdentityProvider.resolve(real)
                val matches = current.device_model == expected.deviceModel && current.system_version == expected.systemVersion
                val verdict = getString(if (matches) R.string.InuDeviceSpoofVerifyMatch else R.string.InuDeviceSpoofVerifyMismatch)
                val message = LocaleController.formatString(
                    R.string.InuDeviceSpoofVerifyResult,
                    current.device_model, current.system_version, current.app_name, current.app_version,
                    expected.deviceModel, expected.systemVersion, verdict,
                )
                AlertDialog.Builder(ctx, resourceProvider)
                    .setTitle(getString(R.string.InuDeviceSpoofVerify))
                    .setMessage(message)
                    .setPositiveButton(getString(R.string.OK), null)
                    .show()
            }
        }
    }

    private fun edit(titleRes: Int, item: InuConfig.StringItem, max: Int, allowEmpty: Boolean, validate: (String) -> String?) {
        showInputDialog(this, getString(titleRes), initialText = item.value, selectAll = true) { text ->
            val trimmed = text.trim()
            val cleaned = if (trimmed.isEmpty() && allowEmpty) "" else validate(trimmed)
            if (cleaned == null) {
                BulletinFactory.of(this).createErrorBulletin(LocaleController.formatString(R.string.InuDeviceSpoofInvalid, max)).show()
                false
            } else {
                item.value = cleaned
                listView?.adapter?.update(true)
                true
            }
        }
    }

    companion object {
        private val TOGGLE_SPOOF = InuUtils.generateId()
        private val BUTTON_PRESET = InuUtils.generateId()
        private val BUTTON_MODEL = InuUtils.generateId()
        private val BUTTON_SYSTEM = InuUtils.generateId()
        private val BUTTON_APP = InuUtils.generateId()
        private val BUTTON_APPLY = InuUtils.generateId()
        private val BUTTON_VERIFY = InuUtils.generateId()
        private val TOGGLE_HIDE_TZ = InuUtils.generateId()
        private val TOGGLE_HIDE_INSTALLER = InuUtils.generateId()

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "device-spoof",
            titleRes = R.string.InuDeviceSpoof,
            iconRes = R.drawable.phosphor_device_mobile,
            factory = ::DeviceSpoofSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("device-spoof-toggle", R.string.InuDeviceSpoof, TOGGLE_SPOOF),
                SearchRegistry.Entry("device-spoof-preset", R.string.InuDeviceSpoofPreset, BUTTON_PRESET),
                SearchRegistry.Entry("device-spoof-app", R.string.InuDeviceSpoofApp, BUTTON_APP),
            ),
        )
    }
}
