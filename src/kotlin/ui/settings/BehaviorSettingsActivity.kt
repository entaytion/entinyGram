package desu.inugram.ui.settings

import android.os.Build
import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.ProxyVpnHelper
import desu.inugram.helpers.ShortcutHelper
import desu.inugram.helpers.chat.WebPreviewHelper
import desu.inugram.helpers.maps.MapsHelper
import desu.inugram.helpers.search.UserIdOpenHelper
import desu.inugram.ui.profile.DeleteProfilePhotosSheet
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class BehaviorSettingsActivity : SettingsPageActivity() {

    private val deleteForBothGroup = ExpandableBoolGroup(
        LocaleController.getString(R.string.InuDeleteForBoth),
        listOf(
            ExpandableBoolGroup.Option(R.string.InuDeleteForBothMessages, InuConfig.DELETE_FOR_BOTH_MESSAGES),
            ExpandableBoolGroup.Option(R.string.InuDeleteForBothDms, InuConfig.DELETE_FOR_BOTH_DMS),
            ExpandableBoolGroup.Option(R.string.InuDeleteForBothGroups, InuConfig.DELETE_FOR_BOTH_GROUPS),
        ),
        sectionId = SECTION_DELETE_FOR_BOTH,
    )

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuCategoryBehavior)

    override fun onResume() {
        super.onResume()
        listView?.adapter?.update(true)
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        // User Profile (Merged into Behavior & Profile)
        items.add(UItem.asHeader(LocaleController.getString(R.string.InuUserProfile)))
        items.add(
            UItem.asCheck(
                TOGGLE_PROFILE_PHOTO_GRADIENT_FADE,
                LocaleController.getString(R.string.InuProfilePhotoGradientFade),
            ).setChecked(InuConfig.PROFILE_PHOTO_GRADIENT_FADE.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_REDUCE_PROFILE_MOTION,
                LocaleController.getString(R.string.InuReduceProfileMotion),
            ).setChecked(InuConfig.REDUCE_PROFILE_MOTION.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_PROFILE_SCROLL_SNAP,
                LocaleController.getString(R.string.InuDisableProfileScrollSnap),
            ).setChecked(InuConfig.DISABLE_PROFILE_SCROLL_SNAP.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_PROFILE_PREFER_MEDIA_TAB,
                LocaleController.getString(R.string.InuProfilePreferMediaTab),
            ).setChecked(InuConfig.PROFILE_PREFER_MEDIA_TAB.value)
        )
        items.add(
            UItem.asButton(
                BUTTON_PROFILE_ID_MODE,
                LocaleController.getString(R.string.InuProfileIdMode),
                when (InuConfig.PROFILE_ID_MODE.value) {
                    InuConfig.ProfileIdModeItem.TELEGRAM_ID -> LocaleController.getString(R.string.InuProfileIdModeTelegram)
                    InuConfig.ProfileIdModeItem.BOT_API_ID -> LocaleController.getString(R.string.InuProfileIdModeBotApi)
                    else -> LocaleController.getString(R.string.InuProfileIdModeOff)
                }
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_SHOW_PROFILE_REG_DATE,
                LocaleController.getString(R.string.InuShowProfileRegDate),
            ).setChecked(InuConfig.SHOW_PROFILE_REG_DATE.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_CHAT_TITLE_PHONE,
                LocaleController.getString(R.string.InuDisableChatTitlePhone),
            ).setChecked(InuConfig.DISABLE_CHAT_TITLE_PHONE.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_OPEN_BY_USER_ID,
                R.string.InuOpenByUserId,
                R.string.InuOpenByUserIdInfo,
                InuConfig.OPEN_BY_USER_ID.value,
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_OPEN_BY_ID,
                R.drawable.inu_tabler_id,
                LocaleController.getString(R.string.InuOpenById),
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_DELETE_PROFILE_PHOTOS,
                R.drawable.inu_tabler_photo_x,
                LocaleController.getString(R.string.InuDeleteProfilePhotos),
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuFormatting)))
        items.add(
            UItem.asCheck(
                TOGGLE_SHOW_SECONDS,
                LocaleController.getString(R.string.InuShowSeconds)
            ).setChecked(InuConfig.SHOW_SECONDS.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_ROUNDING,
                R.string.InuDisableRounding,
                R.string.InuDisableRoundingInfo,
                InuConfig.DISABLE_ROUNDING.value
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuChatActions)))
        items.add(
            UItem.asCheck(
                TOGGLE_CALL_CONFIRMATION,
                LocaleController.getString(R.string.InuCallConfirmation),
            ).setChecked(InuConfig.CALL_CONFIRMATION.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HD_BLUETOOTH_CALL_AUDIO,
                R.string.InuHdBluetoothCallAudio,
                R.string.InuHdBluetoothCallAudioInfo,
                InuConfig.HD_BLUETOOTH_CALL_AUDIO.value,
            )
        )
        deleteForBothGroup.addTo(items) { listView.adapter.update(true) }
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_CHAT_BUBBLES,
                LocaleController.getString(R.string.InuDisableChatBubbles),
            ).setChecked(InuConfig.DISABLE_CHAT_BUBBLES.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_GIF_SEEKBAR,
                R.string.InuGifSeekbar,
                R.string.InuGifSeekbarInfo,
                InuConfig.GIF_SEEKBAR.value,
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_SEND_MP4_DOCUMENT_AS_VIDEO,
                R.string.InuSendMp4DocumentAsVideo,
                R.string.InuSendMp4DocumentAsVideoInfo,
                InuConfig.SEND_MP4_DOCUMENT_AS_VIDEO.value,
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_DOWNLOAD_DIRECTORY,
                LocaleController.getString(R.string.InuDownloadDirectory),
                InuConfig.DOWNLOAD_DIRECTORY.value,
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuLinksAndBrowser)))
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_CONFIRM_INTERNAL_LINKS,
                R.string.InuConfirmInternalLinks,
                R.string.InuConfirmInternalLinksInfo,
                InuConfig.CONFIRM_INTERNAL_LINKS.value,
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_BROWSER_SWIPE_COLLAPSE,
                LocaleController.getString(R.string.InuDisableBrowserSwipeCollapse),
            ).setChecked(InuConfig.DISABLE_BROWSER_SWIPE_COLLAPSE.value)
        )
        items.add(
            UItem.asButton(
                BUTTON_WEB_PREVIEW_REPLACEMENTS,
                LocaleController.getString(R.string.InuWebPreviewReplacements),
                if (InuConfig.WEB_PREVIEW_REPLACEMENTS_ENABLED.value)
                    WebPreviewHelper.load().size.toString()
                else
                    LocaleController.getString(R.string.SlowmodeOff),
            )
        )
        items.add(UItem.asShadow(null))

        items.add(
            UItem.asHeader(addExperimentalSpan(LocaleController.getString(R.string.InuNetwork)))
        )
        items.add(
            UItem.asCheck(
                TOGGLE_AUTO_DISABLE_PROXY_ON_VPN,
                LocaleController.getString(R.string.InuAutoDisableProxyOnVpn),
            ).setChecked(InuConfig.AUTO_DISABLE_PROXY_ON_VPN.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_FASTER_DOWNLOADS,
                LocaleController.getString(R.string.InuFasterDownloads),
            ).setChecked(InuConfig.FASTER_DOWNLOADS.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_FASTER_UPLOADS,
                LocaleController.getString(R.string.InuFasterUploads),
            ).setChecked(InuConfig.FASTER_UPLOADS.value)
        )
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuFasterTransfersInfo)))

        items.add(
            mkTwoLineCheckItem(
                TOGGLE_KEEP_DOWNLOADS_IN_BACKGROUND,
                R.string.InuKeepDownloadsInBackground,
                R.string.InuKeepDownloadsInBackgroundInfo,
                InuConfig.KEEP_DOWNLOADS_IN_BACKGROUND.value
            )
        )
        if (InuConfig.KEEP_DOWNLOADS_IN_BACKGROUND.value) {
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_BLOCK_SLEEP_WHILE_DOWNLOADING,
                    R.string.InuBlockSleepWhileDownloading,
                    R.string.InuBlockSleepWhileDownloadingInfo,
                    InuConfig.BLOCK_SLEEP_WHILE_DOWNLOADING.value
                )
            )
        }
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuMapsHeader)))
        if (MapsHelper.hasMapLibre) {
            items.add(
                UItem.asButton(
                    BUTTON_MAP_PROVIDER,
                    LocaleController.getString(R.string.InuMapProvider),
                    when (InuConfig.MAP_PROVIDER.value) {
                        InuConfig.MapProviderItem.OSM -> LocaleController.getString(R.string.InuMapProviderOsm)
                        else -> LocaleController.getString(R.string.InuMapProviderGoogle)
                    }
                )
            )
        }
        items.add(
            UItem.asButton(
                BUTTON_MAP_PREVIEW_PROVIDER,
                LocaleController.getString(R.string.InuMapPreviewProvider),
                when (InuConfig.MAP_PREVIEW_PROVIDER.value) {
                    InuConfig.MapPreviewProviderItem.TELEGRAM -> LocaleController.getString(R.string.InuMapPreviewProviderTelegram)
                    InuConfig.MapPreviewProviderItem.GOOGLE -> LocaleController.getString(R.string.InuMapPreviewProviderGoogle)
                    InuConfig.MapPreviewProviderItem.YANDEX -> LocaleController.getString(R.string.InuMapPreviewProviderYandex)
                    InuConfig.MapPreviewProviderItem.DISABLED -> LocaleController.getString(R.string.Disable)
                    else -> LocaleController.getString(R.string.Default)
                }
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuMiscellaneous)))
        items.add(
            UItem.asButton(
                BUTTON_PERFORMANCE_CLASS,
                LocaleController.getString(R.string.InuPerformanceClass),
                performanceClassLabel(SharedConfig.getDevicePerformanceClass()),
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            items.add(
                UItem.asButton(
                    BUTTON_TEXT_CLASSIFIER_MODE,
                    LocaleController.getString(R.string.InuTextClassifierMode),
                    textClassifierModeLabel(InuConfig.TEXT_CLASSIFIER_MODE.value),
                )
            )
        }
        if (UserConfig.getActivatedAccountsCount() > 1) {
            items.add(
                mkTwoLineCheckItem(
                    TOGGLE_ACCOUNT_SWITCH_SHORTCUT,
                    R.string.InuAccountSwitchShortcut,
                    R.string.InuAccountSwitchShortcutInfo,
                    InuConfig.ACCOUNT_SWITCH_SHORTCUT.value,
                )
            )
        }
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (deleteForBothGroup.handleClick(item, view) { listView.adapter.update(true) }) return
        when (item.id) {
            TOGGLE_LOCAL_PREMIUM -> {
                val new = InuConfig.LOCAL_PREMIUM.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                if (new) {
                    val userConfig = org.telegram.messenger.UserConfig.getInstance(currentAccount)
                    desu.inugram.helpers.LocalPremiumHelper.applyToSelfUser(userConfig.getCurrentUser(), currentAccount)
                }
                // Runtime refresh — no restart needed (AyuGram approach)
                org.telegram.messenger.NotificationCenter.getInstance(currentAccount)
                    .postNotificationName(org.telegram.messenger.NotificationCenter.currentUserPremiumStatusChanged)
                org.telegram.messenger.NotificationCenter.getGlobalInstance()
                    .postNotificationName(org.telegram.messenger.NotificationCenter.premiumStatusChangedGlobal)
                org.telegram.messenger.NotificationCenter.getInstance(currentAccount)
                    .postNotificationName(org.telegram.messenger.NotificationCenter.updateInterfaces, 0)
                org.telegram.messenger.NotificationCenter.getInstance(currentAccount)
                    .postNotificationName(org.telegram.messenger.NotificationCenter.mainUserInfoChanged)
                val mdc = org.telegram.messenger.MediaDataController.getInstance(currentAccount)
                mdc.loadPremiumPromo(false)
                mdc.loadReactions(false, null)
            }

            TOGGLE_PROFILE_PHOTO_GRADIENT_FADE -> {
                val new = InuConfig.PROFILE_PHOTO_GRADIENT_FADE.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_REDUCE_PROFILE_MOTION -> {
                val new = InuConfig.REDUCE_PROFILE_MOTION.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_PROFILE_SCROLL_SNAP -> {
                val new = InuConfig.DISABLE_PROFILE_SCROLL_SNAP.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_PROFILE_PREFER_MEDIA_TAB -> {
                val new = InuConfig.PROFILE_PREFER_MEDIA_TAB.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            BUTTON_PROFILE_ID_MODE -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuProfileIdModeOff),
                    LocaleController.getString(R.string.InuProfileIdModeTelegram),
                    LocaleController.getString(R.string.InuProfileIdModeBotApi),
                ),
                InuConfig.PROFILE_ID_MODE.value,
            ) { which ->
                InuConfig.PROFILE_ID_MODE.value = which
            }

            TOGGLE_SHOW_PROFILE_REG_DATE -> {
                val new = InuConfig.SHOW_PROFILE_REG_DATE.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_CHAT_TITLE_PHONE -> {
                val new = InuConfig.DISABLE_CHAT_TITLE_PHONE.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_OPEN_BY_USER_ID -> {
                val new = InuConfig.OPEN_BY_USER_ID.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            BUTTON_OPEN_BY_ID -> {
                val ctx = context ?: return
                UserIdOpenHelper.showOpenByIdDialog(ctx, this, currentAccount)
            }

            BUTTON_DELETE_PROFILE_PHOTOS -> {
                val activity = parentActivity ?: return
                DeleteProfilePhotosSheet(activity, currentAccount).show()
            }

            TOGGLE_DISABLE_CHAT_BUBBLES -> {
                val new = InuConfig.DISABLE_CHAT_BUBBLES.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            BUTTON_DOWNLOAD_DIRECTORY -> RadioItemOptions.show(
                this, view,
                DOWNLOAD_DIRECTORIES,
                DOWNLOAD_DIRECTORIES.indexOf(InuConfig.DOWNLOAD_DIRECTORY.value),
            ) { which ->
                InuConfig.DOWNLOAD_DIRECTORY.value = DOWNLOAD_DIRECTORIES[which]
                BulletinFactory.of(this)
                    .createSimpleBulletin(
                        R.raw.chats_infotip,
                        LocaleController.getString(R.string.InuDownloadDirectoryApplied),
                    )
                    .show()
            }

            BUTTON_PERFORMANCE_CLASS -> showPerformanceClassSelector()
            BUTTON_TEXT_CLASSIFIER_MODE -> showTextClassifierModeSelector()
            BUTTON_WEB_PREVIEW_REPLACEMENTS -> presentFragment(WebPreviewReplacementsActivity())

            TOGGLE_CALL_CONFIRMATION -> {
                val new = InuConfig.CALL_CONFIRMATION.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HD_BLUETOOTH_CALL_AUDIO -> {
                val new = InuConfig.HD_BLUETOOTH_CALL_AUDIO.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_CONFIRM_INTERNAL_LINKS -> {
                val new = InuConfig.CONFIRM_INTERNAL_LINKS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_BROWSER_SWIPE_COLLAPSE -> {
                val new = InuConfig.DISABLE_BROWSER_SWIPE_COLLAPSE.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_GIF_SEEKBAR -> {
                val new = InuConfig.GIF_SEEKBAR.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_SEND_MP4_DOCUMENT_AS_VIDEO -> {
                val new = InuConfig.SEND_MP4_DOCUMENT_AS_VIDEO.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_AUTO_DISABLE_PROXY_ON_VPN -> {
                val new = InuConfig.AUTO_DISABLE_PROXY_ON_VPN.toggle()
                (view as? TextCheckCell)?.isChecked = new
                ProxyVpnHelper.reconcile()
            }

            TOGGLE_FASTER_DOWNLOADS -> {
                val new = InuConfig.FASTER_DOWNLOADS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_FASTER_UPLOADS -> {
                val new = InuConfig.FASTER_UPLOADS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_KEEP_DOWNLOADS_IN_BACKGROUND -> {
                val new = InuConfig.KEEP_DOWNLOADS_IN_BACKGROUND.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                listView.adapter.update(true)
            }

            TOGGLE_BLOCK_SLEEP_WHILE_DOWNLOADING -> {
                val new = InuConfig.BLOCK_SLEEP_WHILE_DOWNLOADING.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_SHOW_SECONDS -> {
                val new = InuConfig.SHOW_SECONDS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_ACCOUNT_SWITCH_SHORTCUT -> {
                val new = InuConfig.ACCOUNT_SWITCH_SHORTCUT.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                parentActivity?.let { ShortcutHelper.sync(it) }
            }

            TOGGLE_DISABLE_ROUNDING -> {
                val new = InuConfig.DISABLE_ROUNDING.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            BUTTON_MAP_PROVIDER -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.InuMapProviderGoogle),
                    LocaleController.getString(R.string.InuMapProviderOsm),
                ),
                InuConfig.MAP_PROVIDER.value,
            ) { which ->
                InuConfig.MAP_PROVIDER.value = which
                showRestartBulletin()
            }

            BUTTON_MAP_PREVIEW_PROVIDER -> RadioItemOptions.show(
                this, view,
                listOf(
                    LocaleController.getString(R.string.Default),
                    LocaleController.getString(R.string.InuMapPreviewProviderTelegram),
                    LocaleController.getString(R.string.InuMapPreviewProviderGoogle),
                    LocaleController.getString(R.string.InuMapPreviewProviderYandex),
                    LocaleController.getString(R.string.Disable),
                ),
                InuConfig.MAP_PREVIEW_PROVIDER.value,
            ) { which ->
                InuConfig.MAP_PREVIEW_PROVIDER.value = which
                MapsHelper.syncMapProvider(messagesController)
            }
        }
    }

    private fun showPerformanceClassSelector() {
        val context = context ?: return
        val measuredClass = SharedConfig.measureDevicePerformanceClass()
        val values = intArrayOf(
            SharedConfig.PERFORMANCE_CLASS_HIGH,
            SharedConfig.PERFORMANCE_CLASS_AVERAGE,
            SharedConfig.PERFORMANCE_CLASS_LOW,
        )
        showDialog(
            RadioDialogBuilder(context, getResourceProvider())
                .setTitle(LocaleController.getString(R.string.InuPerformanceClass))
                .setSubtitle(LocaleController.getString(R.string.InuPerformanceClassInfo))
                .setItems(
                    values.map {
                        RadioDialogBuilder.Item(
                            performanceClassLabel(it),
                            if (it == measuredClass) LocaleController.getString(R.string.InuPerformanceClassMeasured) else null,
                        )
                    },
                    values.indexOf(SharedConfig.getDevicePerformanceClass()),
                ) { _, which ->
                    val newClass = values[which]
                    SharedConfig.overrideDevicePerformanceClass(if (newClass == measuredClass) -1 else newClass)
                    listView.adapter.update(true)
                }.create()
        )
    }

    private fun showTextClassifierModeSelector() {
        val context = context ?: return
        val values = intArrayOf(
            InuConfig.TextClassifierModeItem.NATIVE,
            InuConfig.TextClassifierModeItem.IMPROVED,
            InuConfig.TextClassifierModeItem.OFF,
        )
        val items = listOf(
            RadioDialogBuilder.Item(LocaleController.getString(R.string.InuTextClassifierModeNative)),
            RadioDialogBuilder.Item(
                LocaleController.getString(R.string.InuTextClassifierModeImproved),
                LocaleController.getString(R.string.InuTextClassifierModeImprovedInfo),
            ),
            RadioDialogBuilder.Item(
                LocaleController.getString(R.string.InuTextClassifierModeOff),
                LocaleController.getString(R.string.InuTextClassifierModeOffInfo),
            ),
        )
        showDialog(
            RadioDialogBuilder(context, getResourceProvider())
                .setTitle(LocaleController.getString(R.string.InuTextClassifierMode))
                .setSubtitle(LocaleController.getString(R.string.InuTextClassifierModeInfo))
                .setItems(items, values.indexOf(InuConfig.TEXT_CLASSIFIER_MODE.value).coerceAtLeast(0)) { _, which ->
                    val newValue = values[which]
                    if (InuConfig.TEXT_CLASSIFIER_MODE.value == newValue) return@setItems
                    InuConfig.TEXT_CLASSIFIER_MODE.value = newValue
                    listView.adapter.update(true)
                    showRestartBulletin()
                }.create()
        )
    }

    companion object {
        private val TOGGLE_PROFILE_PHOTO_GRADIENT_FADE = InuUtils.generateId()
        private val TOGGLE_REDUCE_PROFILE_MOTION = InuUtils.generateId()
        private val TOGGLE_DISABLE_PROFILE_SCROLL_SNAP = InuUtils.generateId()
        private val TOGGLE_PROFILE_PREFER_MEDIA_TAB = InuUtils.generateId()
        private val BUTTON_PROFILE_ID_MODE = InuUtils.generateId()
        private val TOGGLE_LOCAL_PREMIUM = InuUtils.generateId()
        private val TOGGLE_SHOW_PROFILE_REG_DATE = InuUtils.generateId()
        private val TOGGLE_DISABLE_CHAT_TITLE_PHONE = InuUtils.generateId()
        private val TOGGLE_OPEN_BY_USER_ID = InuUtils.generateId()
        private val BUTTON_OPEN_BY_ID = InuUtils.generateId()
        private val BUTTON_DELETE_PROFILE_PHOTOS = InuUtils.generateId()
        private val TOGGLE_DISABLE_CHAT_BUBBLES = InuUtils.generateId()
        private val BUTTON_PERFORMANCE_CLASS = InuUtils.generateId()
        private val BUTTON_DOWNLOAD_DIRECTORY = InuUtils.generateId()

        private val DOWNLOAD_DIRECTORIES = listOf("Inugram", "Telegram")
        private val BUTTON_TEXT_CLASSIFIER_MODE = InuUtils.generateId()
        private val TOGGLE_CALL_CONFIRMATION = InuUtils.generateId()
        private val TOGGLE_HD_BLUETOOTH_CALL_AUDIO = InuUtils.generateId()
        private val TOGGLE_CONFIRM_INTERNAL_LINKS = InuUtils.generateId()
        private val TOGGLE_DISABLE_BROWSER_SWIPE_COLLAPSE = InuUtils.generateId()
        private val TOGGLE_GIF_SEEKBAR = InuUtils.generateId()
        private val TOGGLE_SEND_MP4_DOCUMENT_AS_VIDEO = InuUtils.generateId()
        private val BUTTON_WEB_PREVIEW_REPLACEMENTS = InuUtils.generateId()
        private val TOGGLE_AUTO_DISABLE_PROXY_ON_VPN = InuUtils.generateId()
        private val TOGGLE_FASTER_DOWNLOADS = InuUtils.generateId()
        private val TOGGLE_FASTER_UPLOADS = InuUtils.generateId()
        private val TOGGLE_KEEP_DOWNLOADS_IN_BACKGROUND = InuUtils.generateId()
        private val TOGGLE_BLOCK_SLEEP_WHILE_DOWNLOADING = InuUtils.generateId()
        private val SECTION_DELETE_FOR_BOTH = InuUtils.generateId()
        private val BUTTON_MAP_PROVIDER = InuUtils.generateId()
        private val BUTTON_MAP_PREVIEW_PROVIDER = InuUtils.generateId()
        private val TOGGLE_SHOW_SECONDS = InuUtils.generateId()
        private val TOGGLE_DISABLE_ROUNDING = InuUtils.generateId()
        private val TOGGLE_ACCOUNT_SWITCH_SHORTCUT = InuUtils.generateId()

        private fun performanceClassLabel(value: Int): String = when (value) {
            SharedConfig.PERFORMANCE_CLASS_HIGH -> LocaleController.getString(R.string.InuPerformanceClassHigh)
            SharedConfig.PERFORMANCE_CLASS_AVERAGE -> LocaleController.getString(R.string.InuPerformanceClassAverage)
            else -> LocaleController.getString(R.string.InuPerformanceClassLow)
        }

        private fun textClassifierModeLabel(value: Int): String = when (value) {
            InuConfig.TextClassifierModeItem.NATIVE -> LocaleController.getString(R.string.InuTextClassifierModeNative)
            InuConfig.TextClassifierModeItem.OFF -> LocaleController.getString(R.string.InuTextClassifierModeOff)
            else -> LocaleController.getString(R.string.InuTextClassifierModeImproved)
        }

        @JvmField val PAGE = SearchRegistry.Page(
            slug = "behavior",
            titleRes = R.string.InuCategoryBehavior,
            iconRes = R.drawable.inu_tabler_adjustments_horizontal,
            factory = ::BehaviorSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("profile-photo-gradient-fade", R.string.InuProfilePhotoGradientFade, TOGGLE_PROFILE_PHOTO_GRADIENT_FADE),
                SearchRegistry.Entry("reduce-profile-motion", R.string.InuReduceProfileMotion, TOGGLE_REDUCE_PROFILE_MOTION),
                SearchRegistry.Entry("disable-profile-scroll-snap", R.string.InuDisableProfileScrollSnap, TOGGLE_DISABLE_PROFILE_SCROLL_SNAP),
                SearchRegistry.Entry("profile-prefer-media-tab", R.string.InuProfilePreferMediaTab, TOGGLE_PROFILE_PREFER_MEDIA_TAB),
                SearchRegistry.Entry("profile-id-mode", R.string.InuProfileIdMode, BUTTON_PROFILE_ID_MODE),
                SearchRegistry.Entry("show-profile-reg-date", R.string.InuShowProfileRegDate, TOGGLE_SHOW_PROFILE_REG_DATE),
                SearchRegistry.Entry("disable-chat-title-phone", R.string.InuDisableChatTitlePhone, TOGGLE_DISABLE_CHAT_TITLE_PHONE),
                SearchRegistry.Entry("open-by-user-id", R.string.InuOpenByUserId, TOGGLE_OPEN_BY_USER_ID),
                SearchRegistry.Entry("open-by-id", R.string.InuOpenById, BUTTON_OPEN_BY_ID),
                SearchRegistry.Entry("delete-profile-photos", R.string.InuDeleteProfilePhotos, BUTTON_DELETE_PROFILE_PHOTOS),
                SearchRegistry.Entry("disable-chat-bubbles", R.string.InuDisableChatBubbles, TOGGLE_DISABLE_CHAT_BUBBLES),
                SearchRegistry.Entry("performance-class", R.string.InuPerformanceClass, BUTTON_PERFORMANCE_CLASS),
                SearchRegistry.Entry("text-classifier-mode", R.string.InuTextClassifierMode, BUTTON_TEXT_CLASSIFIER_MODE),
                SearchRegistry.Entry("call-confirmation", R.string.InuCallConfirmation, TOGGLE_CALL_CONFIRMATION),
                SearchRegistry.Entry("hd-bluetooth-call-audio", R.string.InuHdBluetoothCallAudio, TOGGLE_HD_BLUETOOTH_CALL_AUDIO),
                SearchRegistry.Entry("confirm-internal-links", R.string.InuConfirmInternalLinks, TOGGLE_CONFIRM_INTERNAL_LINKS),
                SearchRegistry.Entry("disable-browser-swipe-collapse", R.string.InuDisableBrowserSwipeCollapse, TOGGLE_DISABLE_BROWSER_SWIPE_COLLAPSE),
                SearchRegistry.Entry("gif-seekbar", R.string.InuGifSeekbar, TOGGLE_GIF_SEEKBAR),
                SearchRegistry.Entry("send-mp4-document-as-video", R.string.InuSendMp4DocumentAsVideo, TOGGLE_SEND_MP4_DOCUMENT_AS_VIDEO),
                SearchRegistry.Entry("download-directory", R.string.InuDownloadDirectory, BUTTON_DOWNLOAD_DIRECTORY),
                SearchRegistry.Entry("web-preview-replacements", R.string.InuWebPreviewReplacements, BUTTON_WEB_PREVIEW_REPLACEMENTS),
                SearchRegistry.Entry("auto-disable-proxy-on-vpn", R.string.InuAutoDisableProxyOnVpn, TOGGLE_AUTO_DISABLE_PROXY_ON_VPN),
                SearchRegistry.Entry("faster-downloads", R.string.InuFasterDownloads, TOGGLE_FASTER_DOWNLOADS),
                SearchRegistry.Entry("faster-uploads", R.string.InuFasterUploads, TOGGLE_FASTER_UPLOADS),
                SearchRegistry.Entry("keep-downloads-in-background", R.string.InuKeepDownloadsInBackground, TOGGLE_KEEP_DOWNLOADS_IN_BACKGROUND),
                SearchRegistry.Entry("block-sleep-while-downloading", R.string.InuBlockSleepWhileDownloading, TOGGLE_BLOCK_SLEEP_WHILE_DOWNLOADING),
                SearchRegistry.Entry("delete-for-both", R.string.InuDeleteForBoth, SECTION_DELETE_FOR_BOTH),
                SearchRegistry.Entry("map-provider", R.string.InuMapProvider, BUTTON_MAP_PROVIDER),
                SearchRegistry.Entry("map-preview-provider", R.string.InuMapPreviewProvider, BUTTON_MAP_PREVIEW_PROVIDER),
                SearchRegistry.Entry("show-seconds", R.string.InuShowSeconds, TOGGLE_SHOW_SECONDS),
                SearchRegistry.Entry("disable-rounding", R.string.InuDisableRounding, TOGGLE_DISABLE_ROUNDING),
                SearchRegistry.Entry("account-switch-shortcut", R.string.InuAccountSwitchShortcut, TOGGLE_ACCOUNT_SWITCH_SHORTCUT),
            ),
        )
    }
}
