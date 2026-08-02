package desu.inugram.ui.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.collection.LongSparseArray
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.cloud.SettingsBackupHelper
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ShareAlert
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InuSettingsActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuSettings)

    private var topHeaderView: View? = null

    private fun createHeaderView(): View {
        val context = context ?: return View(org.telegram.messenger.ApplicationLoader.applicationContext)
        val linear = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, AndroidUtilities.dp(24f), 0, AndroidUtilities.dp(16f))
        }

        val imageView = android.widget.ImageView(context).apply {
            setImageResource(R.drawable.icon_settings_inu)
        }
        linear.addView(
            imageView,
            org.telegram.ui.Components.LayoutHelper.createLinear(
                72,
                72,
                android.view.Gravity.CENTER_HORIZONTAL,
                0,
                0,
                0,
                10
            )
        )

        val titleView = android.widget.TextView(context).apply {
            text = "entinyGram"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 22f)
            setTypeface(AndroidUtilities.bold())
            setTextColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteBlackText))
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        linear.addView(
            titleView,
            org.telegram.ui.Components.LayoutHelper.createLinear(
                org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT,
                org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT,
                android.view.Gravity.CENTER_HORIZONTAL,
                0,
                0,
                0,
                4
            )
        )

        val subtitleView = android.widget.TextView(context).apply {
            text = "${desu.inugram.helpers.update.UpdateHelper.stockVersionName} (${org.telegram.messenger.BuildConfig.STOCK_VERSION_CODE})"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteGrayText2))
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        linear.addView(
            subtitleView,
            org.telegram.ui.Components.LayoutHelper.createLinear(
                org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT,
                org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT,
                android.view.Gravity.CENTER_HORIZONTAL
            )
        )

        return linear
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        val headerView = topHeaderView ?: createHeaderView().also { topHeaderView = it }
        items.add(UItem.asCustom(headerView))
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuCategories)))
        items.add(
            UItem.asButton(
                BUTTON_GENERAL,
                R.drawable.msg_palette,
                LocaleController.getString(R.string.InuLookAndFeel)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_CHATS,
                R.drawable.msg_discussion,
                LocaleController.getString(R.string.MainTabsChats)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_MESSAGES,
                R.drawable.msg_discuss,
                LocaleController.getString(R.string.InuMessages)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_DIALOGS,
                R.drawable.msg_viewchats,
                LocaleController.getString(R.string.InuMainPage)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_USER_PROFILE,
                R.drawable.msg_openprofile,
                LocaleController.getString(R.string.InuUserProfile)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_ANNOYANCES,
                R.drawable.menu_hide_gift,
                LocaleController.getString(R.string.InuAnnoyances)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_BEHAVIOR,
                R.drawable.avd_speed,
                LocaleController.getString(R.string.InuBehavior)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_TRANSLATOR,
                R.drawable.msg_translate,
                LocaleController.getString(R.string.InuTranslator)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_PRIVACY,
                R.drawable.msg_permissions,
                LocaleController.getString(R.string.InuPrivacySecurity)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_TOS,
                R.drawable.msg_autodelete,
                LocaleController.getString(R.string.InuTOS)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_AI,
                R.drawable.input_ai_star,
                LocaleController.getString(R.string.InuAiCompose)
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuDataBackup)))
        items.add(
            UItem.asButton(
                BUTTON_EXPORT,
                R.drawable.msg_shareout,
                LocaleController.getString(R.string.InuBackupExport)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_IMPORT,
                R.drawable.msg_download,
                LocaleController.getString(R.string.InuBackupImport)
            )
        )
        items.add(
            UItem.asButton(
                BUTTON_CLOUD_SYNC,
                R.drawable.inu_tabler_cloud,
                LocaleController.getString(R.string.InuCloudSync)
            )
        )
        items.add(UItem.asShadow(null))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuLinks)))
        items.add(
            UItem.asButton(
                BUTTON_ABOUT,
                R.drawable.msg_info,
                LocaleController.getString(R.string.InuAbout)
            )
        )
        items.add(UItem.asShadow(null))
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            BUTTON_GENERAL -> presentFragment(AppearanceSettingsActivity())
            BUTTON_CHATS -> presentFragment(ChatsSettingsActivity())
            BUTTON_MESSAGES -> presentFragment(MessagesSettingsActivity())
            BUTTON_DIALOGS -> presentFragment(DialogsSettingsActivity())
            BUTTON_USER_PROFILE -> presentFragment(UserProfileSettingsActivity())
            BUTTON_ANNOYANCES -> presentFragment(AnnoyancesSettingsActivity())
            BUTTON_BEHAVIOR -> presentFragment(BehaviorSettingsActivity())
            BUTTON_TRANSLATOR -> presentFragment(TranslatorSettingsActivity())
            BUTTON_PRIVACY -> presentFragment(PrivacySecurityActivity())
            BUTTON_TOS -> presentFragment(TosSettingsActivity())
            BUTTON_AI -> presentFragment(AiSettingsActivity())
            BUTTON_ABOUT -> presentFragment(AboutActivity())
            BUTTON_EXPORT -> launchExport()
            BUTTON_IMPORT -> launchImport()
            BUTTON_CLOUD_SYNC -> presentFragment(CloudSyncActivity())
        }
    }

    private fun launchExport() {
        Utilities.globalQueue.postRunnable {
            val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(AndroidUtilities.getCacheDir(), "$date.inu-settings.json")
            val err: String? = try {
                file.parentFile?.mkdirs()
                file.writeText(SettingsBackupHelper.export(), Charsets.UTF_8)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            AndroidUtilities.runOnUIThread {
                if (err != null) {
                    BulletinFactory.of(this).createErrorBulletin(
                        LocaleController.formatString(R.string.InuBackupExportError, err)
                    ).show()
                    return@runOnUIThread
                }
                openSharePicker(file)
            }
        }
    }

    private fun openSharePicker(file: File) {
        val ctx = parentActivity ?: return
        val account = accountInstance
        val sheet = object : ShareAlert(ctx, null, null, false, null, false) {
            override fun onSend(
                dids: LongSparseArray<TLRPC.Dialog>,
                count: Int,
                topic: TLRPC.TL_forumTopic?,
                showToast: Boolean
            ) {
                for (i in 0 until dids.size()) {
                    val did = dids.keyAt(i)
                    SendMessagesHelper.prepareSendingDocument(
                        account, file.absolutePath, file.absolutePath, null, null,
                        "application/json", did,
                        null, null, null, null, null,
                        true, 0, null, null, 0, false,
                    )
                }
                if (dids.size() == 1) openChat(dids.keyAt(0))
            }
        }
        showDialog(sheet)
    }

    private fun openChat(did: Long) {
        val args = Bundle().apply {
            putBoolean("scrollToTopOnResume", true)
            when {
                DialogObject.isEncryptedDialog(did) -> putInt("enc_id", DialogObject.getEncryptedChatId(did))
                DialogObject.isUserDialog(did) -> putLong("user_id", did)
                else -> putLong("chat_id", -did)
            }
        }
        if (messagesController.checkCanOpenChat(args, this)) {
            presentFragment(ChatActivity(args))
        }
    }

    private fun launchImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "*/*"))
        }
        try {
            startActivityForResult(intent, REQ_IMPORT)
        } catch (e: Exception) {
            BulletinFactory.of(this).createErrorBulletin(e.message ?: "").show()
        }
    }

    override fun onActivityResultFragment(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val ctx = context ?: parentActivity ?: return
        if (requestCode == REQ_IMPORT) {
            Utilities.globalQueue.postRunnable {
                val text = try {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (_: Exception) {
                    null
                }
                AndroidUtilities.runOnUIThread {
                    if (text == null) {
                        BulletinFactory.of(this).createErrorBulletin(
                            LocaleController.getString(R.string.InuBackupImportBadFormat)
                        ).show()
                        return@runOnUIThread
                    }
                    SettingsBackupHelper.showImportConfirm(this, text)
                }
            }
        }
    }

    companion object {
        private val BUTTON_GENERAL = InuUtils.generateId()
        private val BUTTON_CHATS = InuUtils.generateId()
        private val BUTTON_MESSAGES = InuUtils.generateId()
        private val BUTTON_DIALOGS = InuUtils.generateId()
        private val BUTTON_USER_PROFILE = InuUtils.generateId()
        private val BUTTON_ANNOYANCES = InuUtils.generateId()
        private val BUTTON_BEHAVIOR = InuUtils.generateId()
        private val BUTTON_TRANSLATOR = InuUtils.generateId()
        private val BUTTON_PRIVACY = InuUtils.generateId()
        private val BUTTON_TOS = InuUtils.generateId()
        private val BUTTON_AI = InuUtils.generateId()
        private val BUTTON_ABOUT = InuUtils.generateId()
        private val BUTTON_EXPORT = InuUtils.generateId()
        private val BUTTON_IMPORT = InuUtils.generateId()
        private val BUTTON_CLOUD_SYNC = InuUtils.generateId()

        private const val REQ_IMPORT = 31002

        @JvmField
        val PAGE = SearchRegistry.Page(
            slug = "root",
            titleRes = R.string.InuSettings,
            iconRes = R.drawable.icon_settings_inu,
            factory = ::InuSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("about", R.string.InuAbout, BUTTON_ABOUT),
                SearchRegistry.Entry("backup-export", R.string.InuBackupExport, BUTTON_EXPORT),
                SearchRegistry.Entry("backup-import", R.string.InuBackupImport, BUTTON_IMPORT),
                SearchRegistry.Entry("cloud-sync", R.string.InuCloudSync, BUTTON_CLOUD_SYNC),
            ),
        )
    }
}
