package desu.inugram.ui.profile

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.CheckBox2
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.RadialProgressView
import org.telegram.ui.Components.RecyclerListView

class DeleteProfilePhotosSheet(
    context: Context,
    private val currentAccount: Int,
) : BottomSheet(context, false) {

    private val clientUserId = UserConfig.getInstance(currentAccount).clientUserId
    private val photosList = ArrayList<TLRPC.Photo>()
    private val selectedIds = HashSet<Long>()

    private val titleView: TextView
    private val subtitleView: TextView
    private val selectAllBtn: TextView
    private val progressView: RadialProgressView
    private val emptyView: TextView
    private val recyclerView: RecyclerListView
    private val adapter: PhotosAdapter
    private val deleteSelectedBtn: TextView
    private val deleteAllBtn: TextView

    init {
        setApplyBottomPadding(false)
        setApplyTopPadding(false)
        fixNavigationBar(getThemedColor(Theme.key_windowBackgroundWhite))

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(8f))
        }

        // Header bar
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(AndroidUtilities.dp(18f), AndroidUtilities.dp(10f), AndroidUtilities.dp(16f), AndroidUtilities.dp(4f))
        }

        val titleContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        titleView = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19f)
            typeface = AndroidUtilities.bold()
            text = LocaleController.getString(R.string.InuDeleteProfilePhotos)
        }
        titleContainer.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        subtitleView = TextView(context).apply {
            setTextColor(Theme.getColor(Theme.key_dialogTextGray2))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            text = LocaleController.getString(R.string.InuDeleteProfilePhotosInfo)
        }
        titleContainer.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 2f, 0f, 0f))

        headerLayout.addView(titleContainer, LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1f))

        selectAllBtn = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            typeface = AndroidUtilities.bold()
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4))
            text = LocaleController.getString(R.string.InuSelectAll)
            background = Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(6f), 0, Theme.getColor(Theme.key_dialogButtonSelector)
            )
            setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(4f), AndroidUtilities.dp(8f), AndroidUtilities.dp(4f))
            setOnClickListener { toggleSelectAll() }
        }
        headerLayout.addView(selectAllBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT))

        rootLayout.addView(headerLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        // Content Area (Grid / Progress / Empty)
        val contentFrame = FrameLayout(context)

        progressView = RadialProgressView(context).apply {
            setSize(AndroidUtilities.dp(36f))
            setProgressColor(Theme.getColor(Theme.key_progressCircle))
        }
        contentFrame.addView(progressView, LayoutHelper.createFrame(48, 48, Gravity.CENTER))

        emptyView = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Theme.getColor(Theme.key_dialogTextGray2))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            text = LocaleController.getString(R.string.InuNoProfilePhotos)
            visibility = View.GONE
        }
        contentFrame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat(), Gravity.CENTER, 24f, 24f, 24f, 24f))

        adapter = PhotosAdapter()
        recyclerView = RecyclerListView(context).apply {
            layoutManager = GridLayoutManager(context, 3)
            this.adapter = this@DeleteProfilePhotosSheet.adapter
            setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(8f), AndroidUtilities.dp(14f), AndroidUtilities.dp(8f))
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.GONE
            setOnItemClickListener { view, position ->
                if (position in photosList.indices) {
                    val photo = photosList[position]
                    if (selectedIds.contains(photo.id)) {
                        selectedIds.remove(photo.id)
                    } else {
                        selectedIds.add(photo.id)
                    }
                    (view as? PhotoCell)?.setChecked(selectedIds.contains(photo.id), true)
                    updateHeaderAndButtons()
                }
            }
        }
        contentFrame.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 300, Gravity.CENTER))

        rootLayout.addView(contentFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 300, 0f, 4f, 0f, 4f))

        // Bottom Action Buttons
        val buttonsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(4f), AndroidUtilities.dp(16f), AndroidUtilities.dp(8f))
        }

        deleteSelectedBtn = makeButton(
            context = context,
            text = LocaleController.formatString(R.string.InuDeleteSelectedProfilePhotos, 0),
            background = Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(10f),
                Theme.getColor(Theme.key_text_RedBold),
                ColorUtils.setAlphaComponent(Color.WHITE, 60),
            ),
            textColor = 0xffffffff.toInt(),
            bold = true,
        ) {
            confirmDeleteSelected()
        }
        buttonsLayout.addView(deleteSelectedBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, 0f, 0f, 0f, 6f))

        deleteAllBtn = makeButton(
            context = context,
            text = LocaleController.getString(R.string.InuDeleteAllProfilePhotos),
            background = Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(10f),
                0,
                Theme.getColor(Theme.key_dialogButtonSelector),
            ),
            textColor = Theme.getColor(Theme.key_text_RedRegular),
            bold = false,
        ) {
            confirmDeleteAll()
        }
        buttonsLayout.addView(deleteAllBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42))

        rootLayout.addView(buttonsLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT))

        setCustomView(rootLayout)
        updateHeaderAndButtons()
        loadPhotos()
    }

    private fun updateHeaderAndButtons() {
        val count = photosList.size
        val selected = selectedIds.size

        if (count > 0) {
            subtitleView.text = LocaleController.formatString(R.string.InuProfilePhotosSelected, selected)
            selectAllBtn.visibility = View.VISIBLE
            selectAllBtn.text = LocaleController.getString(
                if (selected == count) R.string.InuDeselectAll else R.string.InuSelectAll
            )
            deleteAllBtn.visibility = View.VISIBLE
            deleteSelectedBtn.visibility = View.VISIBLE
            deleteSelectedBtn.text = LocaleController.formatString(R.string.InuDeleteSelectedProfilePhotos, selected)
            deleteSelectedBtn.isEnabled = selected > 0
            deleteSelectedBtn.alpha = if (selected > 0) 1f else 0.4f
        } else {
            subtitleView.text = LocaleController.getString(R.string.InuDeleteProfilePhotosInfo)
            selectAllBtn.visibility = View.GONE
            deleteAllBtn.visibility = View.GONE
            deleteSelectedBtn.visibility = View.GONE
        }
    }

    private fun toggleSelectAll() {
        if (selectedIds.size == photosList.size) {
            selectedIds.clear()
        } else {
            selectedIds.clear()
            for (p in photosList) {
                selectedIds.add(p.id)
            }
        }
        adapter.notifyDataSetChanged()
        updateHeaderAndButtons()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadPhotos() {
        val user = MessagesController.getInstance(currentAccount).getUser(clientUserId)
        if (user == null) {
            progressView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
            return
        }

        // Check local cache first
        val cached = MessagesController.getInstance(currentAccount).getDialogPhotos(clientUserId)
        if (cached != null && cached.photos.isNotEmpty()) {
            val list = cached.photos.filterNotNull()
            if (list.isNotEmpty()) {
                photosList.clear()
                photosList.addAll(list)
                progressView.visibility = View.GONE
                emptyView.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                adapter.notifyDataSetChanged()
                updateHeaderAndButtons()
            }
        }

        // Fetch full fresh list from MTProto
        val req = TLRPC.TL_photos_getUserPhotos().apply {
            this.user_id = MessagesController.getInstance(currentAccount).getInputUser(user)
            this.offset = 0
            this.limit = 100
            this.max_id = 0
        }

        ConnectionsManager.getInstance(currentAccount).sendRequest(req) { response, error ->
            AndroidUtilities.runOnUIThread {
                progressView.visibility = View.GONE
                if (error == null && response is TLRPC.photos_Photos) {
                    photosList.clear()
                    photosList.addAll(response.photos.filterNotNull())
                    if (photosList.isEmpty()) {
                        emptyView.visibility = View.VISIBLE
                        recyclerView.visibility = View.GONE
                    } else {
                        emptyView.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                        adapter.notifyDataSetChanged()
                    }
                    updateHeaderAndButtons()
                } else if (photosList.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                    updateHeaderAndButtons()
                }
            }
        }
    }

    private fun confirmDeleteSelected() {
        if (selectedIds.isEmpty()) return
        val count = selectedIds.size
        val builder = AlertDialog.Builder(context).apply {
            setTitle(LocaleController.getString(R.string.InuDeleteProfilePhotos))
            setMessage(LocaleController.formatString(R.string.InuDeleteSelectedProfilePhotosConfirm, count))
            setPositiveButton(LocaleController.getString(R.string.Delete)) { _, _ ->
                val toDelete = photosList.filter { selectedIds.contains(it.id) }
                performDelete(toDelete, isAll = toDelete.size == photosList.size)
            }
            setNegativeButton(LocaleController.getString(R.string.Cancel), null)
        }
        val dialog = builder.create()
        dialog.show()
        dialog.setItemColor(AlertDialog.BUTTON_POSITIVE, Theme.getColor(Theme.key_text_RedBold), Theme.getColor(Theme.key_text_RedRegular))
    }

    private fun confirmDeleteAll() {
        if (photosList.isEmpty()) return
        val builder = AlertDialog.Builder(context).apply {
            setTitle(LocaleController.getString(R.string.InuDeleteAllProfilePhotos))
            setMessage(LocaleController.getString(R.string.InuDeleteAllProfilePhotosConfirm))
            setPositiveButton(LocaleController.getString(R.string.Delete)) { _, _ ->
                performDelete(ArrayList(photosList), isAll = true)
            }
            setNegativeButton(LocaleController.getString(R.string.Cancel), null)
        }
        val dialog = builder.create()
        dialog.show()
        dialog.setItemColor(AlertDialog.BUTTON_POSITIVE, Theme.getColor(Theme.key_text_RedBold), Theme.getColor(Theme.key_text_RedRegular))
    }

    private fun performDelete(toDelete: List<TLRPC.Photo>, isAll: Boolean) {
        if (toDelete.isEmpty()) return

        val progressDialog = AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER).apply {
            setCanCancel(false)
            show()
        }

        val controller = MessagesController.getInstance(currentAccount)
        val storage = MessagesStorage.getInstance(currentAccount)
        val chunks = toDelete.chunked(100)
        var pendingRequests = chunks.size

        for (chunk in chunks) {
            val req = TLRPC.TL_photos_deletePhotos().apply {
                for (p in chunk) {
                    val input = TLRPC.TL_inputPhoto().apply {
                        this.id = p.id
                        this.access_hash = p.access_hash
                        this.file_reference = p.file_reference ?: ByteArray(0)
                    }
                    this.id.add(input)
                }
            }

            ConnectionsManager.getInstance(currentAccount).sendRequest(req) { _, error ->
                AndroidUtilities.runOnUIThread {
                    if (error != null) {
                        FileLog.e("DeleteProfilePhotosSheet: failed to delete batch: ${error.text}")
                    }
                    pendingRequests--
                    if (pendingRequests <= 0) {
                        runCatching { progressDialog.dismiss() }

                        val deletedIds = toDelete.map { it.id }.toSet()

                        if (isAll || toDelete.size == photosList.size) {
                            val user = controller.getUser(clientUserId)
                            if (user != null) {
                                user.photo = TLRPC.TL_userProfilePhotoEmpty()
                            }
                            UserConfig.getInstance(currentAccount).currentUser?.photo = TLRPC.TL_userProfilePhotoEmpty()
                            UserConfig.getInstance(currentAccount).saveConfig(true)

                            val fullUser = controller.getUserFull(clientUserId)
                            if (fullUser != null) {
                                fullUser.profile_photo = TLRPC.TL_photoEmpty()
                                storage.updateUserInfo(fullUser, false)
                            }
                            storage.clearUserPhotos(clientUserId)
                            controller.getDialogPhotos(clientUserId).reset()
                        } else {
                            val dialogPhotos = controller.getDialogPhotos(clientUserId)
                            for (p in toDelete) {
                                storage.clearUserPhoto(clientUserId, p.id)
                                dialogPhotos.removePhoto(p.id)
                            }
                            val activePhoto = photosList.firstOrNull()
                            if (activePhoto != null && deletedIds.contains(activePhoto.id)) {
                                controller.deleteUserPhoto(null)
                            }
                        }

                        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.mainUserInfoChanged)
                        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL)
                        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_AVATAR)
                        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.reloadDialogPhotos)

                        dismiss()

                        val toastMsg = if (isAll) {
                            LocaleController.getString(R.string.InuProfilePhotosDeleted)
                        } else {
                            LocaleController.formatString(R.string.InuProfilePhotosDeletedCount, toDelete.size)
                        }
                        BulletinFactory.global().createSimpleBulletin(R.raw.ic_delete, toastMsg).show()
                    }
                }
            }
        }
    }

    private fun makeButton(
        context: Context,
        text: CharSequence,
        background: Drawable,
        textColor: Int,
        bold: Boolean,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        this.text = text
        isAllCaps = false
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        setTextColor(textColor)
        if (bold) typeface = AndroidUtilities.bold()
        this.background = background
        setOnClickListener { onClick() }
    }

    private inner class PhotosAdapter : RecyclerListView.SelectionAdapter() {
        override fun isEnabled(holder: RecyclerView.ViewHolder): Boolean = true

        override fun getItemCount(): Int = photosList.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return RecyclerListView.Holder(PhotoCell(context))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val cell = holder.itemView as PhotoCell
            val photo = photosList[position]
            cell.setPhoto(photo, isMain = position == 0, isSelected = selectedIds.contains(photo.id))
        }
    }

    private class PhotoCell(context: Context) : FrameLayout(context) {
        private val imageView = BackupImageView(context).apply {
            setRoundRadius(AndroidUtilities.dp(8f))
        }
        private val dimView = View(context).apply {
            background = Theme.createRoundRectDrawable(AndroidUtilities.dp(8f), 0x55000000.toInt())
            visibility = View.GONE
        }
        private val checkBox = CheckBox2(context, 20).apply {
            setColor(Theme.key_checkbox, Theme.key_checkboxDisabled, Theme.key_checkboxCheck)
            setDrawUnchecked(true)
            isClickable = false
        }
        private val mainBadge = TextView(context).apply {
            text = LocaleController.getString(R.string.InuMainPhoto)
            setTextColor(0xffffffff.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10f)
            typeface = AndroidUtilities.bold()
            background = GradientDrawable().apply {
                setColor(0x99000000.toInt())
                cornerRadius = AndroidUtilities.dpf2(4f)
            }
            setPadding(AndroidUtilities.dp(4f), AndroidUtilities.dp(1f), AndroidUtilities.dp(4f), AndroidUtilities.dp(1f))
            visibility = View.GONE
        }

        init {
            setPadding(AndroidUtilities.dp(3f), AndroidUtilities.dp(3f), AndroidUtilities.dp(3f), AndroidUtilities.dp(3f))
            addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
            addView(dimView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
            addView(checkBox, LayoutHelper.createFrame(22, 22f, Gravity.TOP or Gravity.RIGHT, 0f, 4f, 4f, 0f))
            addView(mainBadge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.BOTTOM or Gravity.LEFT, 6f, 0f, 0f, 6f))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        }

        fun setPhoto(photo: TLRPC.Photo, isMain: Boolean, isSelected: Boolean) {
            val size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 150)
            if (size != null) {
                imageView.setImage(ImageLocation.getForPhoto(size, photo), "150_150", null, null, photo)
            } else {
                imageView.setImageDrawable(null)
            }
            mainBadge.visibility = if (isMain) View.VISIBLE else View.GONE
            setChecked(isSelected, false)
        }

        fun setChecked(checked: Boolean, animated: Boolean) {
            checkBox.setChecked(checked, animated)
            dimView.visibility = if (checked) View.VISIBLE else View.GONE
        }
    }
}
