package desu.inugram.helpers.security

import android.content.Context
import desu.inugram.InuConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.tl.TL_stories
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Stories.StoriesController
import org.telegram.ui.Stories.StoryViewer

object StoryGhostAlert {
    private var bypass = false

    @JvmStatic
    fun intercept(
        viewer: StoryViewer,
        account: Int,
        context: Context,
        storyItem: TL_stories.StoryItem?,
        peerIds: ArrayList<Long>?,
        position: Int,
        storiesList: StoriesController.StoriesList?,
        userStories: TL_stories.PeerStories?,
        placeProvider: StoryViewer.PlaceProvider?,
        reversed: Boolean,
    ): Boolean {
        if (bypass || !InuConfig.GHOST_STORY_ALERT.value) return false
        val dialogId = peerIds?.getOrNull(position) ?: return false
        if (dialogId == UserConfig.getInstance(account).clientUserId) return false
        if (GhostHelper.shouldSuppress(dialogId, SuppressKind.STORY_READ)) return false
        if (!MessagesController.getInstance(account).storiesController.hasUnreadStories(dialogId)) return false

        fun reopen() {
            bypass = true
            try {
                viewer.open(account, context, storyItem, peerIds, position, storiesList, userStories, placeProvider, reversed)
            } finally {
                bypass = false
            }
        }

        AlertDialog.Builder(context)
            .setTitle(LocaleController.getString(R.string.InuStoryGhostAlertTitle))
            .setMessage(LocaleController.getString(R.string.InuStoryGhostAlertMessage))
            .setPositiveButton(LocaleController.getString(R.string.InuStoryGhostAlertEnable)) { _, _ ->
                InuConfig.GHOST_HIDE_STORY_READ.value = true
                GhostHelper.setGhostMode(true)
                reopen()
            }
            .setNeutralButton(LocaleController.getString(R.string.InuStoryGhostAlertWatch)) { _, _ -> reopen() }
            .setNegativeButton(LocaleController.getString(R.string.InuStoryGhostAlertNever)) { _, _ ->
                InuConfig.GHOST_STORY_ALERT.value = false
                reopen()
            }
            .show()
        return true
    }
}
