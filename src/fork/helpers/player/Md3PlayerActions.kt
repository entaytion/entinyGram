package desu.inugram.helpers.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.widget.FrameLayout
import androidx.core.content.FileProvider
import desu.inugram.InuConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.DialogObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileRefController
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.Forum.ForumUtilities
import org.telegram.ui.DialogsActivity
import org.telegram.ui.LaunchActivity
import org.telegram.ui.TopicsFragment
import java.io.File

object Md3PlayerActions {
    private val pendingProfileSaves = HashSet<Long>()

    fun noForwards(messageObject: MessageObject): Boolean {
        val account = messageObject.currentAccount
        return MessagesController.getInstance(account).isPeerNoForwards(messageObject.dialogId) ||
            (messageObject.messageOwner != null && messageObject.messageOwner.noforwards && !InuConfig.ALLOW_FORWARD_RESTRICTED.value)
    }

    fun isProfileSavePending(messageObject: MessageObject): Boolean {
        val document = messageObject.document ?: return false
        return pendingProfileSaves.contains(document.id)
    }

    fun isSavedToProfile(messageObject: MessageObject): Boolean {
        val document = messageObject.document ?: return false
        val ids = MessagesController.getInstance(messageObject.currentAccount).savedMusicIds ?: return false
        return ids.ids.contains(document.id)
    }

    private fun ensureAccount(activity: LaunchActivity, account: Int) {
        if (UserConfig.selectedAccount != account) activity.switchToAccount(account, true)
    }

    fun showInChat(activity: LaunchActivity, messageObject: MessageObject) {
        val account = messageObject.currentAccount
        ensureAccount(activity, account)
        val args = Bundle()
        var did = messageObject.dialogId
        if (DialogObject.isEncryptedDialog(did)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(did))
        } else if (DialogObject.isUserDialog(did)) {
            args.putLong("user_id", did)
        } else {
            val chat = MessagesController.getInstance(account).getChat(-did)
            if (chat?.migrated_to != null) {
                args.putLong("migrated_to", did)
                did = -chat.migrated_to.channel_id
            }
            args.putLong("chat_id", -did)
        }
        args.putInt("message_id", messageObject.id)
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.closeChats)
        activity.presentFragment(ChatActivity(args), false, false)
    }

    private fun localFile(messageObject: MessageObject): File {
        val attach = messageObject.messageOwner.attachPath
        if (!TextUtils.isEmpty(attach)) {
            val f = File(attach)
            if (f.exists()) return f
        }
        return FileLoader.getInstance(messageObject.currentAccount).getPathToMessage(messageObject.messageOwner)
    }

    fun share(activity: LaunchActivity, messageObject: MessageObject) {
        try {
            val f = localFile(messageObject)
            if (f.exists()) {
                val intent = Intent(Intent.ACTION_SEND)
                intent.type = messageObject.mimeType
                try {
                    intent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(ApplicationLoader.applicationContext, ApplicationLoader.getApplicationId() + ".provider", f))
                    intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                } catch (ignore: Exception) {
                    intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f))
                }
                activity.startActivityForResult(Intent.createChooser(intent, LocaleController.getString(R.string.ShareFile)), 500)
            } else {
                AlertDialog.Builder(activity)
                    .setTitle(LocaleController.getString(R.string.AppName))
                    .setPositiveButton(LocaleController.getString(R.string.OK), null)
                    .setMessage(LocaleController.getString(R.string.PleaseDownload))
                    .show()
            }
        } catch (e: Exception) {
            android.util.Log.d("Md3Player", "share failed", e)
        }
    }

    fun saveToMusic(activity: LaunchActivity, messageObject: MessageObject, host: FrameLayout, resourcesProvider: Theme.ResourcesProvider?) {
        if ((Build.VERSION.SDK_INT <= 28 || BuildVars.NO_SCOPED_STORAGE) && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 4)
            return
        }
        var fileName = FileLoader.getDocumentFileName(messageObject.document)
        if (TextUtils.isEmpty(fileName)) fileName = messageObject.fileName
        val path = localFile(messageObject).toString()
        val mime = messageObject.document?.mime_type ?: ""
        MediaController.saveFile(path, activity, 3, fileName, mime) {
            BulletinFactory.of(host, resourcesProvider).createDownloadBulletin(BulletinFactory.FileType.AUDIO).show()
        }
    }

    fun saveToSavedMessages(messageObject: MessageObject) {
        if (messageObject.id <= 0) return
        val account = messageObject.currentAccount
        val self = UserConfig.getInstance(account).getClientUserId()
        val messages = ArrayList<MessageObject>()
        messages.add(messageObject)
        SendMessagesHelper.getInstance(account).sendMessage(messages, self, false, false, true, 0, 0L)
        LaunchActivity.getLastFragment()?.let {
            BulletinFactory.of(it).createSimpleBulletin(R.raw.forward, AndroidUtilities.replaceTags(LocaleController.getString(R.string.FwdMessageToSavedMessages))).show()
        }
    }

    fun forward(activity: LaunchActivity, messageObject: MessageObject) {
        val account = messageObject.currentAccount
        val messages: ArrayList<MessageObject>?
        val document: TLRPC.TL_document?
        if (messageObject.id < 0) {
            document = messageObject.document as? TLRPC.TL_document ?: return
            messages = null
        } else {
            document = null
            messages = arrayListOf(messageObject)
        }
        ensureAccount(activity, account)
        val savedMusicList = MediaController.getInstance().currentSavedMusicList
        val args = Bundle()
        args.putBoolean("onlySelect", true)
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD)
        args.putBoolean("canSelectTopics", true)
        val fragment = DialogsActivity(args)
        fragment.setDelegate(object : DialogsActivity.DialogsActivityDelegate {
            override fun didSelectDialogs(
                fragment1: DialogsActivity,
                dids: ArrayList<MessagesStorage.TopicKey>,
                message: CharSequence?,
                param: Boolean,
                notify: Boolean,
                scheduleDate: Int,
                scheduleRepeatPeriod: Int,
                topicsFragment: TopicsFragment?,
            ): Boolean {
                val self = UserConfig.getInstance(account).getClientUserId()
                if (dids.size > 1 || dids[0].dialogId == self || message != null || messages == null) {
                    val helper = SendMessagesHelper.getInstance(account)
                    for (topicKey in dids) {
                        val did = topicKey.dialogId
                        if (message != null) {
                            helper.sendMessage(SendMessagesHelper.SendMessageParams.of(message.toString(), did, null, null, null, true, null, null, null, true, 0, 0, null, false))
                        }
                        if (messages != null) {
                            helper.sendMessage(messages, did, false, false, true, 0, 0L)
                        } else {
                            helper.sendMessage(SendMessagesHelper.SendMessageParams.of(document, null, messageObject.messageOwner.attachPath, did, null, null, null, null, null, null, notify, scheduleDate, 0, 0, savedMusicList, null, false, false))
                        }
                    }
                    fragment1.finishFragment()
                    showForwardBulletin(account, dids)
                } else {
                    val topicKey = dids[0]
                    val did = topicKey.dialogId
                    val chatArgs = Bundle()
                    chatArgs.putBoolean("scrollToTopOnResume", true)
                    if (DialogObject.isEncryptedDialog(did)) {
                        chatArgs.putInt("enc_id", DialogObject.getEncryptedChatId(did))
                    } else if (DialogObject.isUserDialog(did)) {
                        chatArgs.putLong("user_id", did)
                    } else {
                        chatArgs.putLong("chat_id", -did)
                    }
                    val chatActivity = ChatActivity(chatArgs)
                    if (topicKey.topicId != 0L) ForumUtilities.applyTopic(chatActivity, topicKey)
                    if (activity.presentFragment(chatActivity, true, false)) {
                        chatActivity.showFieldPanelForForward(true, messages)
                        if (topicKey.topicId != 0L) fragment1.removeSelfFromStack()
                    } else {
                        fragment1.finishFragment()
                    }
                }
                return true
            }
        })
        activity.presentFragment(fragment)
    }

    private fun showForwardBulletin(account: Int, dids: ArrayList<MessagesStorage.TopicKey>) {
        val last = LaunchActivity.getLastFragment() ?: return
        val self = UserConfig.getInstance(account).getClientUserId()
        val did = dids[0].dialogId
        val text = when {
            dids.size == 1 && did == self -> LocaleController.getString(R.string.FwdMessageToSavedMessages)
            dids.size == 1 && did > 0 -> LocaleController.formatString(R.string.FwdMessageToUser, DialogObject.getShortName(account, did))
            dids.size == 1 -> LocaleController.formatString(R.string.FwdMessageToGroup, DialogObject.getShortName(account, did))
            else -> LocaleController.formatPluralStringComma("FwdMessageToManyChats", dids.size)
        }
        BulletinFactory.of(last).createSimpleBulletin(R.raw.forward, AndroidUtilities.replaceTags(text)).show()
    }

    fun saveToProfile(messageObject: MessageObject, save: Boolean, callback: (TLRPC.TL_error?) -> Unit) {
        val document = messageObject.document ?: return
        if (!pendingProfileSaves.add(document.id)) return
        val documentId = document.id
        saveToProfile(messageObject, save, false) { error ->
            pendingProfileSaves.remove(documentId)
            callback(error)
        }
    }

    private fun saveToProfile(messageObject: MessageObject, save: Boolean, triedFileRef: Boolean, callback: (TLRPC.TL_error?) -> Unit) {
        val account = messageObject.currentAccount
        val document = messageObject.document ?: return
        val documentId = document.id
        val req = TLRPC.TL_account_saveMusic()
        req.unsave = !save
        req.id = TLRPC.TL_inputDocument()
        req.id.id = documentId
        req.id.access_hash = document.access_hash
        req.id.file_reference = document.file_reference ?: ByteArray(0)
        ConnectionsManager.getInstance(account).sendRequest(req) { _, err ->
            if (err != null && FileRefController.isFileRefError(err.text) && !triedFileRef && messageObject.id > 0) {
                refetch(messageObject) { fresh ->
                    if (fresh != null) {
                        saveToProfile(fresh, save, true, callback)
                    } else {
                        AndroidUtilities.runOnUIThread { callback(err) }
                    }
                }
                return@sendRequest
            }
            if (err != null) {
                AndroidUtilities.runOnUIThread { callback(err) }
                return@sendRequest
            }
            AndroidUtilities.runOnUIThread {
                MessagesController.getInstance(account).savedMusicIds.update(documentId, save)
                val selfId = UserConfig.getInstance(account).getClientUserId()
                val userInfo = MessagesController.getInstance(account).getUserFull(selfId)
                if (userInfo != null) {
                    if (save) {
                        userInfo.flags2 = userInfo.flags2 or TLObject.FLAG_21
                        userInfo.saved_music = document
                    } else if (userInfo.saved_music != null && userInfo.saved_music.id == documentId) {
                        userInfo.flags2 = userInfo.flags2 and TLObject.FLAG_21.inv()
                        userInfo.saved_music = null
                    }
                    MessagesStorage.getInstance(account).updateUserInfo(userInfo, true)
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.profileMusicUpdated, selfId)
                }
                callback(null)
            }
        }
    }

    private fun refetch(messageObject: MessageObject, callback: (MessageObject?) -> Unit) {
        val account = messageObject.currentAccount
        val msgId = messageObject.id
        val request: TLObject = if (messageObject.dialogId >= 0) {
            TLRPC.TL_messages_getMessages().apply { id.add(msgId) }
        } else {
            TLRPC.TL_channels_getMessages().apply {
                channel = MessagesController.getInstance(account).getInputChannel(-messageObject.dialogId)
                id.add(msgId)
            }
        }
        ConnectionsManager.getInstance(account).sendRequest(request) { res, _ ->
            var fresh: MessageObject? = null
            if (res is TLRPC.messages_Messages) {
                for (m in res.messages) {
                    if (m.id == msgId) {
                        fresh = MessageObject(account, m, false, true)
                        break
                    }
                }
            }
            callback(fresh)
        }
    }
}
