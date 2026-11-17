package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.archive.ArchiveQuota
import desu.inugram.helpers.archive.ArchiveScope
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class ArchiveLimitsActivity : SettingsPageActivity() {

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuArchiveAttachments)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val options = ArchiveScope.mediaLimitOptions()
        val labels = Array(options.size) { index ->
            if (options[index] == 0) LocaleController.getString(R.string.InuArchiveLimitDefault)
            else AndroidUtilities.formatFileSize(options[index] * 1024L * 1024L)
        }
        for ((header, config) in listOf(
            R.string.InuArchiveLimitWifi to InuConfig.ARCHIVE_MEDIA_LIMIT_WIFI,
            R.string.InuArchiveLimitMobile to InuConfig.ARCHIVE_MEDIA_LIMIT_MOBILE,
        )) {
            items.add(UItem.asHeader(LocaleController.getString(header)))
            val chosen = options.indexOf(config.value).coerceAtLeast(0)
            items.add(UItem.asSlideView(labels, chosen) { index -> config.value = options[index] })
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuArchiveLimitInfo)))
        val quotas = ArchiveQuota.options()
        val quotaLabels = Array(quotas.size) { index ->
            if (quotas[index] == 0) LocaleController.getString(R.string.InuArchiveQuotaOff)
            else AndroidUtilities.formatFileSize(quotas[index] * 1024L * 1024L * 1024L)
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuArchiveQuota)))
        items.add(UItem.asSlideView(quotaLabels, quotas.indexOf(InuConfig.ARCHIVE_MEDIA_QUOTA_GB.value).coerceAtLeast(0)) { index ->
            InuConfig.ARCHIVE_MEDIA_QUOTA_GB.value = quotas[index]
            for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
                if (UserConfig.getInstance(account).isClientActivated) ArchiveQuota.schedule(account)
            }
        })
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuArchiveQuotaInfo)))
        items.add(mkTwoLineCheckItem(TOGGLE_PUBLIC, R.string.InuArchiveMediaPublic, R.string.InuArchiveMediaPublicInfo, InuConfig.ARCHIVE_MEDIA_PUBLIC.value))
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (item.id == TOGGLE_PUBLIC) {
            InuConfig.ARCHIVE_MEDIA_PUBLIC.toggle()
            listView?.adapter?.update(true)
        }
    }

    companion object {
        private val TOGGLE_PUBLIC = InuUtils.generateId()
    }
}
