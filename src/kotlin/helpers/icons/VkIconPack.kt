package desu.inugram.helpers.icons

import org.telegram.messenger.R

/*
 * VKUI icon pack, ported from Catogram (ua.itaysonlab.catogram.vkui)
 * Icons: https://github.com/VKCOM/icons (MIT)
 */
object VkIconPack : IconPack() {
    private val vkIcons = mapOf(
        // Settings
        R.drawable.msg_settings to R.drawable.vkui_settings_outline_28,
        R.drawable.msg2_language to R.drawable.vkui_globe_outline_28,
        R.drawable.msg2_secret to R.drawable.vkui_lock_outline_28,
        R.drawable.msg2_data to R.drawable.vkui_services_outline_28,
        R.drawable.msg2_discussion to R.drawable.vkui_messages_outline_28,
        R.drawable.msg2_folder to R.drawable.vkui_cube_box_outline_28,
        R.drawable.msg2_ask_question to R.drawable.vkui_help_outline_28,
        R.drawable.msg2_help to R.drawable.vkui_help_outline_28,
        R.drawable.msg2_policy to R.drawable.vkui_check_shield_outline_28,
        R.drawable.msg2_notifications to R.drawable.vkui_notifications_28,
        R.drawable.msg2_devices to R.drawable.vkui_laptop_outline_28,
        R.drawable.msg_theme to R.drawable.vkui_palette_outline_28,
        R.drawable.msg_log to R.drawable.vkui_grid_square_outline_28,
        R.drawable.msg_payment_card to R.drawable.vkui_payment_card_outline_28,
        R.drawable.msg_clearcache to R.drawable.vkui_clear_data_outline_28,

        // Chat input
        R.drawable.input_attach to R.drawable.vkui_attach_outline_28,
        R.drawable.input_mic to R.drawable.vkui_voice_outline_28,
        R.drawable.input_video to R.drawable.vkui_videocam_outline_28,
        R.drawable.input_schedule to R.drawable.vkui_calendar_outline_28,
        R.drawable.input_keyboard to R.drawable.vkui_keyboard_outline_28,
        R.drawable.input_bot1 to R.drawable.vkui_chevron_right_circle_outline_28,
        R.drawable.input_bot2 to R.drawable.vkui_keyboard_bots_outline_28,
        R.drawable.input_smile to R.drawable.vkui_smile_outline_28,
        R.drawable.input_notify_off to R.drawable.vkui_notification_disable_outline_28,
        R.drawable.input_notify_on to R.drawable.vkui_notifications_28,
        R.drawable.smiles_tab_stickers to R.drawable.vkui_sticker_outline_28,
        R.drawable.smiles_tab_gif to R.drawable.vkui_picture_outline_28,

        // Drawer
        R.drawable.msg_archive to R.drawable.vkui_archive_outline_28,
        R.drawable.msg_contacts to R.drawable.vkui_user_outline_28,
        R.drawable.msg_calls to R.drawable.vkui_phone_outline_28,
        R.drawable.msg_saved to R.drawable.vkui_bookmark_outline_28,
        R.drawable.msg_addcontact to R.drawable.vkui_user_add_outline_24,

        // Message context menu
        R.drawable.menu_reply to R.drawable.vkui_reply_outline_28,
        R.drawable.msg_link to R.drawable.vkui_link_circle_outline_28,
        R.drawable.msg_viewreplies to R.drawable.vkui_message_reply_outline_28,
        R.drawable.msg_pin to R.drawable.vkui_pin_outline_28,
        R.drawable.msg_gallery to R.drawable.vkui_picture_outline_28,
        R.drawable.msg_edit to R.drawable.vkui_edit_outline_28,
        R.drawable.msg_delete to R.drawable.vkui_delete_outline_android_28,
        R.drawable.msg_forward to R.drawable.vkui_share_outline_28,
        R.drawable.msg_gif to R.drawable.vkui_airplay_video_outline_28,
        R.drawable.msg_report to R.drawable.vkui_report_outline_28,
        R.drawable.msg_copy to R.drawable.vkui_copy_outline_28,
        R.drawable.msg_fave to R.drawable.vkui_favorite_outline_28,
        R.drawable.msg_shareout to R.drawable.vkui_share_external_outline_28,
        R.drawable.msg_download to R.drawable.vkui_download_outline_28,
    )

    override fun map(original: Int): Int = vkIcons[original] ?: original
}
