# Icons

App vector icons use [Material Symbols](https://fonts.google.com/icons) **Rounded**, weight 400, grade 0,
optical size 24, FILL 0 by default. Resource names did not change, so Java/Kotlin code is untouched.

- Source: `google/material-design-icons`, `symbols/android/<name>/materialsymbolsrounded/<name>[_fill1]_24px.xml`
  (Apache-2.0, see [THIRDPARTY.md](THIRDPARTY.md)).
- Map: `scripts/icons/mapping.tsv`; converter: `python3 scripts/icons/convert.py` (re-runnable, keeps each
  file's `android:width/height`, `android:tint`, `android:autoMirrored` and fill colour; TGX tints and
  mirrors icons in code, so the symbol's own `tint`/`autoMirrored` are not copied).
- FILL 1 only for: the selected counterpart of an outline icon (navigation bar, emoji panel sections),
  files whose name says "filled", media transport glyphs (play/pause/stop/skip/fast-forward), the solid
  verified badge and "on" status markers (rating star, favourite marker over media thumbnails).
- Symbols renamed or redrew some Material Icons: e.g. `phone` -> `call`, `insert_drive_file` -> `draft`,
  `push_pin` -> `keep`, and `whatshot` is now a trend arrow, so fire glyphs use `local_fire_department`.
- Box: inline glyphs whose size/position code relies on (message ticks and clock, chat-list mute, secret-chat
  lock, verified badge, inline-button link arrow, muted video, Instant View) are scaled into the old glyph's
  bounds with a `<group>` so layout offsets in code (`Icons.TICKS_SHIFT_*`, `CLOCK_SHIFT_*`...) still fit.
- Kept as is: brand/logo glyphs (Telegram, Premium star, stores, browsers, OS logos), layered drawables that
  code animates part by part, composites with no equivalent. `vg_star*` (Telegram Stars), multi-colour and
  animated drawables, PNGs and `intro_*` art are outside the converted prefixes.

Totals: 506 files, 458 replaced (31 with FILL 1, 12 boxed), 48 kept.

| File | Material Symbol | Fill | Box | Note |
|---|---|---|---|---|
| `baseline_access_time_24` | `schedule` | 0 | – | emoji panel Recent, inactive |
| `baseline_account_balance_wallet_20` | `account_balance_wallet` | 0 | – |  |
| `baseline_account_balance_wallet_24` | `account_balance_wallet` | 0 | – |  |
| `baseline_account_circle_16` | `account_circle` | 0 | – |  |
| `baseline_account_circle_24` | `account_circle` | 0 | – |  |
| `baseline_add_24` | `add` | 0 | – |  |
| `baseline_add_a_photo_56` | `add_a_photo` | 0 | – |  |
| `baseline_add_comment_24` | `add_comment` | 0 | – |  |
| `baseline_add_comment_40` | `add_comment` | 0 | – |  |
| `baseline_add_link_24` | `add_link` | 0 | – |  |
| `baseline_adjust_24` | `adjust` | 0 | – |  |
| `baseline_airplane_24` | `flight` | 0 | – | folder icon "Travel" |
| `baseline_alternate_email_12` | `alternate_email` | 0 | – |  |
| `baseline_alternate_email_14` | `alternate_email` | 0 | – |  |
| `baseline_alternate_email_24` | `alternate_email` | 0 | – |  |
| `baseline_amazon_24` | kept | – | – | kept: brand: Amazon Appstore logo |
| `baseline_android_24` | `android` | 0 | – |  |
| `baseline_apps_24` | `apps` | 0 | – |  |
| `baseline_archive_16` | `archive` | 0 | – |  |
| `baseline_archive_24` | `archive` | 0 | – |  |
| `baseline_archive_96` | `archive` | 0 | – |  |
| `baseline_arrow_back_24` | `arrow_back` | 0 | – |  |
| `baseline_arrow_downward_24` | `arrow_downward` | 0 | – |  |
| `baseline_arrow_forward_24` | `arrow_forward` | 0 | – |  |
| `baseline_arrow_upward_18` | `arrow_upward` | 0 | – |  |
| `baseline_arrow_upward_24` | `arrow_upward` | 0 | – |  |
| `baseline_assignment_24` | `assignment` | 0 | – |  |
| `baseline_at_16` | `alternate_email` | 0 | – | mention badge |
| `baseline_auto_delete_24` | `auto_delete` | 0 | – |  |
| `baseline_backspace_24` | `backspace` | 0 | – |  |
| `baseline_ballot_16` | `ballot` | 0 | – |  |
| `baseline_bar_chart_24` | `bar_chart` | 0 | – |  |
| `baseline_block_18` | `block` | 0 | – |  |
| `baseline_block_24` | `block` | 0 | – |  |
| `baseline_block_96` | `block` | 0 | – |  |
| `baseline_bluetooth_24` | `bluetooth` | 0 | – |  |
| `baseline_bolt_16` | `bolt` | 0 | – |  |
| `baseline_book_24` | `book` | 0 | – |  |
| `baseline_bookmark_16` | `bookmark` | 0 | – |  |
| `baseline_bookmark_24` | `bookmark` | 0 | – |  |
| `baseline_brightness_2_24` | `brightness_2` | 0 | – |  |
| `baseline_brightness_5_24` | `brightness_5` | 0 | – |  |
| `baseline_brush_24` | `brush` | 0 | – |  |
| `baseline_bubble_chart_24` | `bubble_chart` | 0 | – |  |
| `baseline_bug_report_24` | `bug_report` | 0 | – |  |
| `baseline_build_24` | `build` | 0 | – |  |
| `baseline_bullhorn_16` | `campaign` | 0 | – | channel |
| `baseline_bullhorn_24` | `campaign` | 0 | – | channel |
| `baseline_bullhorn_56` | `campaign` | 0 | – | channel |
| `baseline_cake_variant_14` | `cake` | 0 | – |  |
| `baseline_cake_variant_24` | `cake` | 0 | – | birthdate |
| `baseline_call_16` | `call` | 0 | – |  |
| `baseline_call_24` | `call` | 0 | – |  |
| `baseline_call_end_16` | `call_end` | 0 | – |  |
| `baseline_call_end_24` | `call_end` | 0 | – |  |
| `baseline_call_made_18` | `call_made` | 0 | – |  |
| `baseline_call_missed_18` | `call_missed` | 0 | – |  |
| `baseline_call_received_18` | `call_received` | 0 | – |  |
| `baseline_camera_alt_16` | `photo_camera` | 0 | – |  |
| `baseline_camera_alt_24` | `photo_camera` | 0 | – |  |
| `baseline_camera_alt_96` | `photo_camera` | 0 | – |  |
| `baseline_camera_enhance_24` | `camera_enhance` | 0 | – |  |
| `baseline_camera_front_24` | `camera_front` | 0 | – |  |
| `baseline_camera_rear_24` | `camera_rear` | 0 | – |  |
| `baseline_cancel_24` | `cancel` | 0 | – |  |
| `baseline_casino_16` | `casino` | 0 | – |  |
| `baseline_casino_24` | `casino` | 0 | – |  |
| `baseline_chat_bubble_16` | `chat_bubble` | 0 | – |  |
| `baseline_chat_bubble_24` | `chat_bubble` | 0 | – |  |
| `baseline_check_18` | `check` | 0 | – |  |
| `baseline_check_24` | `check` | 0 | – |  |
| `baseline_check_box_outline_blank_24` | `check_box_outline_blank` | 0 | – |  |
| `baseline_check_circle_16` | `check_circle` | 0 | – |  |
| `baseline_check_circle_24` | `check_circle` | 0 | – |  |
| `baseline_checklist_16` | `checklist` | 0 | – |  |
| `baseline_checklist_24` | `checklist` | 0 | – |  |
| `baseline_clear_all_24` | `clear_all` | 0 | – |  |
| `baseline_close_18` | `close` | 0 | – |  |
| `baseline_close_20` | `close` | 0 | – |  |
| `baseline_close_24` | `close` | 0 | – |  |
| `baseline_close_36` | `close` | 0 | – |  |
| `baseline_cloud_download_16` | `cloud_download` | 0 | – |  |
| `baseline_cloud_download_24` | `cloud_download` | 0 | – |  |
| `baseline_cloud_pause_24` | `pause_circle` | 0 | – | pause cloud download |
| `baseline_code_24` | `code` | 0 | – |  |
| `baseline_collections_16` | `photo_library` | 0 | – |  |
| `baseline_contact_phone_24` | `contact_phone` | 0 | – |  |
| `baseline_content_copy_18` | `content_copy` | 0 | – |  |
| `baseline_content_copy_24` | `content_copy` | 0 | – |  |
| `baseline_content_cut_24` | `content_cut` | 0 | – |  |
| `baseline_content_paste_18` | `content_paste` | 0 | – |  |
| `baseline_content_paste_24` | `content_paste` | 0 | – |  |
| `baseline_create_24` | `edit` | 0 | – |  |
| `baseline_create_new_folder_24` | `create_new_folder` | 0 | – |  |
| `baseline_crop_16_9_24` | `crop_16_9` | 0 | – |  |
| `baseline_crop_3_2_24` | `crop_3_2` | 0 | – |  |
| `baseline_crop_5_4_24` | `crop_5_4` | 0 | – |  |
| `baseline_crop_free_24` | `crop_free` | 0 | – |  |
| `baseline_crop_original_24` | `image` | 0 | – |  |
| `baseline_crop_rotate_24` | `crop_rotate` | 0 | – |  |
| `baseline_crop_square_24` | `crop_square` | 0 | – |  |
| `baseline_crown_24` | `crown` | 0 | – |  |
| `baseline_crown_circle_24` | `crown` | 0 | – | unused in code |
| `baseline_currency_bitcoin_24` | `currency_bitcoin` | 0 | – |  |
| `baseline_dangerous_24` | `dangerous` | 0 | – |  |
| `baseline_data_usage_24` | `data_usage` | 0 | – |  |
| `baseline_date_range_24` | `date_range` | 0 | – |  |
| `baseline_delete_18` | `delete` | 0 | – |  |
| `baseline_delete_24` | `delete` | 0 | – |  |
| `baseline_delete_alert_24` | `delete_forever` | 0 | – | delete account |
| `baseline_delete_forever_24` | `delete_forever` | 0 | – |  |
| `baseline_delete_sweep_24` | `delete_sweep` | 0 | – |  |
| `baseline_device_android_24` | `android` | 0 | – | Material Symbols ships the Android glyph |
| `baseline_device_android_x_24` | kept | – | – | kept: brand: Telegram X app glyph (sessions) |
| `baseline_device_imac_24` | `desktop_mac` | 0 | – |  |
| `baseline_device_ipad_24` | `tablet_mac` | 0 | – |  |
| `baseline_device_iphone_24` | `phone_iphone` | 0 | – |  |
| `baseline_device_linux_24` | kept | – | – | kept: brand: Linux (Tux) logo |
| `baseline_device_macbook_24` | `laptop_mac` | 0 | – |  |
| `baseline_device_other_24` | `devices` | 0 | – | unknown device |
| `baseline_device_web_brave_24` | kept | – | – | kept: brand: Brave logo |
| `baseline_device_web_opera_24` | kept | – | – | kept: brand: Opera logo |
| `baseline_device_web_vivaldi_24` | kept | – | – | kept: brand: Vivaldi logo |
| `baseline_device_windows_24` | kept | – | – | kept: brand: Windows logo |
| `baseline_device_xbox_24` | kept | – | – | kept: brand: Xbox logo |
| `baseline_devices_other_24` | `devices_other` | 0 | – |  |
| `baseline_devices_other_96` | `devices_other` | 0 | – |  |
| `baseline_direction_arrow_down_24` | `expand_more` | 0 | – | "show more" row |
| `baseline_directions_24` | `directions` | 0 | – |  |
| `baseline_directions_car_24` | `directions_car` | 0 | – |  |
| `baseline_done_all_24` | `done_all` | 0 | – |  |
| `baseline_done_all_24_white` | `done_all` | 0 | – |  |
| `baseline_drag_handle_24` | `drag_handle` | 0 | – |  |
| `baseline_edit_12` | `edit` | 0 | – |  |
| `baseline_edit_24` | `edit` | 0 | – |  |
| `baseline_edit_folders_24` | `folder_managed` | 0 | – | edit chat folders |
| `baseline_emoticon_24` | `mood` | 1 | – | emoji panel Smileys, selected counterpart |
| `baseline_emoticon_outline_24` | `mood` | 0 | – | emoji panel Smileys, inactive |
| `baseline_error_18` | `error` | 0 | – |  |
| `baseline_error_24` | `error` | 0 | – |  |
| `baseline_exit_to_app_24` | `exit_to_app` | 0 | – |  |
| `baseline_explore_24` | `explore` | 0 | – |  |
| `baseline_extension_24` | `extension` | 0 | – |  |
| `baseline_eye_off_24` | `visibility_off` | 0 | – |  |
| `baseline_fast_forward_24` | `fast_forward` | 1 | – | media transport |
| `baseline_favorite_12` | `favorite` | 1 | – | favourite marker drawn over media thumbnails |
| `baseline_favorite_14` | `favorite` | 0 | – |  |
| `baseline_favorite_16` | `favorite` | 0 | – |  |
| `baseline_favorite_20` | `favorite` | 0 | – |  |
| `baseline_favorite_24` | `favorite` | 0 | – |  |
| `baseline_feedback_white_24` | `feedback` | 0 | – |  |
| `baseline_ffmpeg_24` | kept | – | – | kept: brand: FFmpeg logo |
| `baseline_file_caption_24` | `subtitles` | 0 | – | add caption |
| `baseline_file_download_24` | `file_download` | 0 | – |  |
| `baseline_filter_variant_bot_18` | kept | – | – | kept: composite: filter glyph + bot badge, no equivalent |
| `baseline_filter_variant_channel_18` | kept | – | – | kept: composite: filter glyph + channel badge, no equivalent |
| `baseline_filter_variant_group_18` | kept | – | – | kept: composite: filter glyph + group badge, no equivalent |
| `baseline_filter_variant_remove_24` | `filter_list_off` | 0 | – | no filter |
| `baseline_filter_variant_unread_18` | kept | – | – | kept: composite: filter glyph + unread badge, no equivalent |
| `baseline_filter_variant_user_18` | kept | – | – | kept: composite: filter glyph + user badge, no equivalent |
| `baseline_finance_24` | `finance` | 0 | – |  |
| `baseline_fingerprint_24` | `fingerprint` | 0 | – |  |
| `baseline_flash_auto_24` | `flash_auto` | 0 | – |  |
| `baseline_flash_off_24` | `flash_off` | 0 | – |  |
| `baseline_flash_on_24` | `flash_on` | 0 | – |  |
| `baseline_flight_24` | `flight` | 0 | – |  |
| `baseline_folder_24` | `folder` | 0 | – |  |
| `baseline_folder_96` | `folder` | 0 | – |  |
| `baseline_folder_copy_24` | `folder_copy` | 0 | – |  |
| `baseline_folder_open_96` | `folder_open` | 0 | – |  |
| `baseline_format_bold_24` | `format_bold` | 0 | – |  |
| `baseline_format_clear_24` | `format_clear` | 0 | – |  |
| `baseline_format_color_fill_24` | `format_color_fill` | 0 | – |  |
| `baseline_format_italic_24` | `format_italic` | 0 | – |  |
| `baseline_format_list_bulleted_type_24` | `format_list_bulleted` | 0 | – |  |
| `baseline_format_quote_close_16` | `format_quote` | 0 | – |  |
| `baseline_format_quote_close_18` | `format_quote` | 0 | – |  |
| `baseline_format_quote_close_24` | `format_quote` | 0 | – |  |
| `baseline_format_text_24` | `text_format` | 0 | – | text formatting |
| `baseline_format_underlined_24` | `format_underlined` | 0 | – |  |
| `baseline_forum_16` | `forum` | 0 | – |  |
| `baseline_forum_18` | `forum` | 0 | – |  |
| `baseline_forum_24` | `forum` | 1 | – | nav bar Chats, selected counterpart |
| `baseline_forum_96` | `forum` | 0 | – |  |
| `baseline_forward_24` | `forward` | 0 | – |  |
| `baseline_galaxy_store_24` | kept | – | – | kept: brand: Galaxy Store logo |
| `baseline_gavel_24` | `gavel` | 0 | – |  |
| `baseline_gesture_24` | `gesture` | 0 | – |  |
| `baseline_ghost_24` | `person_off` | 0 | – | deleted account avatar |
| `baseline_ghost_56` | `person_off` | 0 | – | deleted account avatar |
| `baseline_gif_96` | `gif` | 0 | – |  |
| `baseline_gift_72` | `featured_seasonal_and_gifts` | 0 | – | gift header |
| `baseline_gift_outline_24` | `featured_seasonal_and_gifts` | 0 | – |  |
| `baseline_github_24` | kept | – | – | kept: brand: GitHub logo |
| `baseline_google_play_24` | kept | – | – | kept: brand: Google Play logo |
| `baseline_gpp_maybe_14` | `gpp_maybe` | 0 | – |  |
| `baseline_gpp_maybe_24` | `gpp_maybe` | 0 | – |  |
| `baseline_gps_fixed_12` | `my_location` | 0 | – |  |
| `baseline_gps_fixed_16` | `my_location` | 0 | – |  |
| `baseline_gps_fixed_24` | `my_location` | 0 | – |  |
| `baseline_group_16` | `group` | 0 | – |  |
| `baseline_group_24` | `group` | 0 | – |  |
| `baseline_group_56` | `group` | 0 | – |  |
| `baseline_group_96` | `group` | 0 | – |  |
| `baseline_group_add_16` | `group_add` | 0 | – |  |
| `baseline_group_add_24` | `group_add` | 0 | – |  |
| `baseline_help_16` | `help` | 0 | – |  |
| `baseline_help_24` | `help` | 0 | – |  |
| `baseline_help_outline_24` | `help` | 0 | – |  |
| `baseline_history_24` | `history` | 0 | – |  |
| `baseline_home_24` | `home` | 0 | – |  |
| `baseline_hot_once_24` | kept | – | – | kept: no equivalent: Telegram view-once glyph (fire with 1) |
| `baseline_huawei_24` | kept | – | – | kept: brand: Huawei AppGallery logo |
| `baseline_identifier_24` | `badge` | 0 | – | user / chat ID |
| `baseline_image_24` | `image` | 0 | – |  |
| `baseline_image_96` | `image` | 0 | – |  |
| `baseline_image_aspect_ratio_24` | `image_aspect_ratio` | 0 | – |  |
| `baseline_import_export_24` | `swap_vert` | 0 | – |  |
| `baseline_incognito_circle_18` | `domino_mask` | 0 | – | anonymous admin badge |
| `baseline_indeterminate_check_box_24` | `indeterminate_check_box` | 0 | – |  |
| `baseline_info_14` | `info` | 0 | – |  |
| `baseline_info_16` | `info` | 0 | – |  |
| `baseline_info_18` | `info` | 0 | – |  |
| `baseline_info_24` | `info` | 0 | – |  |
| `baseline_insert_drive_file_16` | `draft` | 0 | – |  |
| `baseline_insert_drive_file_24` | `draft` | 0 | – |  |
| `baseline_insert_drive_file_96` | `draft` | 0 | – |  |
| `baseline_keyboard_24` | `keyboard` | 0 | – |  |
| `baseline_keyboard_arrow_down_20` | `keyboard_arrow_down` | 0 | – |  |
| `baseline_keyboard_arrow_down_24` | `keyboard_arrow_down` | 0 | – |  |
| `baseline_keyboard_arrow_left_24` | `keyboard_arrow_left` | 0 | – |  |
| `baseline_keyboard_arrow_up_24` | `keyboard_arrow_up` | 0 | – |  |
| `baseline_label_24` | `label` | 0 | – |  |
| `baseline_language_24` | `language` | 0 | – |  |
| `baseline_language_96` | `language` | 0 | – |  |
| `baseline_launch_24` | `open_in_new` | 0 | – |  |
| `baseline_layers_24` | `layers` | 0 | – |  |
| `baseline_link_16` | `link` | 0 | – |  |
| `baseline_link_24` | `link` | 0 | – |  |
| `baseline_link_off_24` | `link_off` | 0 | – |  |
| `baseline_link_preview_bg_24` | kept | – | – | kept: layer: drawn together with link_preview_top_layer (LinkPreviewToggleView animates layers) |
| `baseline_link_preview_top_layer_24` | kept | – | – | kept: layer: top layer of link_preview_bg (LinkPreviewToggleView) |
| `baseline_live_help_24` | `live_help` | 0 | – |  |
| `baseline_local_florist_24` | `local_florist` | 0 | – |  |
| `baseline_location_on_18` | `location_on` | 0 | – |  |
| `baseline_location_on_20` | `location_on` | 0 | – |  |
| `baseline_location_on_24` | `location_on` | 0 | – |  |
| `baseline_location_on_24_white` | `location_on` | 0 | – |  |
| `baseline_location_on_48` | `location_on` | 0 | – |  |
| `baseline_lock_14` | `lock` | 0 | – |  |
| `baseline_lock_16` | `lock` | 0 | – |  |
| `baseline_lock_24` | `lock` | 0 | – |  |
| `baseline_lock_48` | `lock` | 0 | – |  |
| `baseline_lock_base_24` | kept | – | – | kept: layer: lock body, animated with lock_top (LockHeaderButton) |
| `baseline_lock_top_24` | kept | – | – | kept: layer: lock shackle, animated with lock_base (LockHeaderButton) |
| `baseline_logo_telegram_24` | kept | – | – | kept: brand: Telegram logo |
| `baseline_logout_24` | `logout` | 0 | – |  |
| `baseline_mail_96` | `mail` | 0 | – |  |
| `baseline_map_24` | `map` | 0 | – |  |
| `baseline_mark_chat_unread_24` | `mark_chat_unread` | 0 | – |  |
| `baseline_mic_16` | `mic` | 0 | – |  |
| `baseline_mic_24` | `mic` | 0 | – |  |
| `baseline_mic_96` | `mic` | 0 | – |  |
| `baseline_more_horiz_24` | `more_horiz` | 0 | – |  |
| `baseline_more_vert_24` | `more_vert` | 0 | – |  |
| `baseline_music_note_16` | `music_note` | 0 | – |  |
| `baseline_music_note_24` | `music_note` | 0 | – |  |
| `baseline_music_note_48` | `music_note` | 0 | – |  |
| `baseline_music_note_96` | `music_note` | 0 | – |  |
| `baseline_notification_important_14` | `notification_important` | 0 | – |  |
| `baseline_notification_important_24` | `notification_important` | 0 | – |  |
| `baseline_notifications_24` | `notifications` | 0 | – |  |
| `baseline_notifications_active_24` | `notifications_active` | 0 | – |  |
| `baseline_notifications_off_16` | `notifications_off` | 0 | – |  |
| `baseline_notifications_off_24` | `notifications_off` | 0 | – |  |
| `baseline_notifications_paused_24` | `notifications_paused` | 0 | – |  |
| `baseline_opacity_18` | `opacity` | 0 | – |  |
| `baseline_open_in_browser_24` | `open_in_browser` | 0 | – |  |
| `baseline_palette_16` | `palette` | 0 | – |  |
| `baseline_palette_24` | `palette` | 0 | – |  |
| `baseline_party_popper_16` | `celebration` | 0 | – |  |
| `baseline_party_popper_24` | `celebration` | 0 | – |  |
| `baseline_party_popper_72` | `celebration` | 0 | – |  |
| `baseline_pause_24` | `pause` | 1 | – | media transport |
| `baseline_pause_36_white` | `pause` | 1 | – | media transport |
| `baseline_pending_24` | `pending` | 0 | – |  |
| `baseline_perm_contact_calendar_24` | `perm_contact_calendar` | 1 | – | nav bar Contacts, selected counterpart |
| `baseline_person_12` | `person` | 0 | – |  |
| `baseline_person_16` | `person` | 0 | – |  |
| `baseline_person_24` | `person` | 0 | – |  |
| `baseline_person_56` | `person` | 0 | – |  |
| `baseline_person_add_16` | `person_add` | 0 | – |  |
| `baseline_person_add_24` | `person_add` | 0 | – |  |
| `baseline_phone_24` | `call` | 1 | – | nav bar Calls, selected counterpart |
| `baseline_phone_24_white` | `call` | 0 | – |  |
| `baseline_phone_36` | `call` | 0 | – |  |
| `baseline_phone_in_talk_24` | `phone_in_talk` | 0 | – |  |
| `baseline_play_arrow_36_white` | `play_arrow` | 1 | – | media transport |
| `baseline_play_circle_filled_24_white` | `play_circle` | 1 | – | filled by name |
| `baseline_playback_speed_0_5_24` | kept | – | – | kept: no full equivalent: Symbols lack 1.0x and 2.5x, a mixed set would be inconsistent |
| `baseline_playback_speed_0_7_24` | kept | – | – | kept: no full equivalent: Symbols lack 1.0x and 2.5x, a mixed set would be inconsistent |
| `baseline_playback_speed_1_0_24` | kept | – | – | kept: no full equivalent: Symbols lack 1.0x and 2.5x, a mixed set would be inconsistent |
| `baseline_playback_speed_1_2_24` | kept | – | – | kept: no full equivalent: Symbols lack 1.0x and 2.5x, a mixed set would be inconsistent |
| `baseline_playback_speed_1_5_24` | kept | – | – | kept: no full equivalent: Symbols lack 1.0x and 2.5x, a mixed set would be inconsistent |
| `baseline_playback_speed_2_0_24` | kept | – | – | kept: no full equivalent: Symbols lack 1.0x and 2.5x, a mixed set would be inconsistent |
| `baseline_playback_speed_2_5_24` | kept | – | – | kept: no full equivalent: Symbols lack 1.0x and 2.5x, a mixed set would be inconsistent |
| `baseline_playback_speed_x_5` | kept | – | – | kept: custom: 5dp "x" glyph appended to speed text |
| `baseline_playlist_add_18` | `playlist_add` | 0 | – |  |
| `baseline_playlist_add_24` | `playlist_add` | 0 | – |  |
| `baseline_playlist_add_check_24` | `playlist_add_check` | 0 | – |  |
| `baseline_policy_24` | `policy` | 0 | – |  |
| `baseline_poll_16` | `insert_chart` | 0 | – | poll |
| `baseline_poll_24` | `insert_chart` | 0 | – | poll |
| `baseline_premium_star_16` | kept | – | – | kept: brand: Telegram Premium star |
| `baseline_premium_star_24` | kept | – | – | kept: brand: Telegram Premium star |
| `baseline_premium_star_28` | kept | – | – | kept: brand: Telegram Premium star |
| `baseline_premium_star_96` | kept | – | – | kept: brand: Telegram Premium star |
| `baseline_public_24` | `public` | 0 | – |  |
| `baseline_queue_music_24` | `queue_music` | 0 | – |  |
| `baseline_radio_button_unchecked_16` | `radio_button_unchecked` | 0 | – |  |
| `baseline_receipt_16` | `receipt` | 0 | – |  |
| `baseline_recent_24` | `history` | 0 | – | recent languages |
| `baseline_redeem_16` | `redeem` | 0 | – |  |
| `baseline_redo_18` | `redo` | 0 | – |  |
| `baseline_remove_circle_24` | `do_not_disturb_on` | 0 | – |  |
| `baseline_repeat_24` | `repeat` | 0 | – |  |
| `baseline_replay_24` | `replay` | 0 | – |  |
| `baseline_reply_14` | `reply` | 0 | – |  |
| `baseline_reply_16` | `reply` | 0 | – |  |
| `baseline_reply_24` | `reply` | 0 | – |  |
| `baseline_reply_24_white` | `reply` | 0 | – |  |
| `baseline_reply_56` | `reply` | 0 | – |  |
| `baseline_report_24` | `report` | 0 | – |  |
| `baseline_restaurant_menu_24` | `restaurant_menu` | 0 | – |  |
| `baseline_rotate_90_degrees_ccw_24` | `rotate_90_degrees_ccw` | 0 | – |  |
| `baseline_router_24` | `router` | 0 | – |  |
| `baseline_schedule_24` | `schedule` | 0 | – |  |
| `baseline_school_24` | `school` | 0 | – |  |
| `baseline_sd_storage_24` | `sd_card` | 0 | – |  |
| `baseline_search_24` | `search` | 0 | – |  |
| `baseline_search_96` | `search` | 0 | – |  |
| `baseline_security_24` | `security` | 0 | – |  |
| `baseline_security_96` | `security` | 0 | – |  |
| `baseline_send_24` | `send` | 0 | – |  |
| `baseline_settings_24` | `settings` | 0 | – |  |
| `baseline_settings_56` | `settings` | 0 | – |  |
| `baseline_settings_96` | `settings` | 0 | – |  |
| `baseline_share_24` | `share` | 0 | – |  |
| `baseline_share_arrow_14` | `share` | 0 | – |  |
| `baseline_share_arrow_16` | `share` | 0 | – |  |
| `baseline_share_arrow_24` | `share` | 0 | – |  |
| `baseline_signal_cellular_alt_24` | `signal_cellular_alt` | 0 | – |  |
| `baseline_sim_card_24` | `sim_card` | 0 | – |  |
| `baseline_sim_card_96` | `sim_card` | 0 | – |  |
| `baseline_sim_card_alert_14` | `sim_card` | 0 | – |  |
| `baseline_sim_card_alert_24` | `sim_card` | 0 | – | check phone number reminder |
| `baseline_skip_next_24_white` | `skip_next` | 1 | – | media transport |
| `baseline_skip_previous_24_white` | `skip_previous` | 1 | – | media transport |
| `baseline_small_arrow_down_18` | `expand_more` | 0 | – |  |
| `baseline_small_arrow_down_24` | `expand_more` | 0 | – | expand reactions |
| `baseline_sports_esports_24` | `sports_esports` | 0 | – |  |
| `baseline_sports_soccer_24` | `sports_soccer` | 0 | – |  |
| `baseline_star_20` | `star` | 1 | – | used as "starFilled" (rating) and theme icon preview |
| `baseline_star_24` | `star` | 1 | – | selected counterpart of star_border |
| `baseline_star_border_24` | `star` | 0 | – | favorite off / emoji panel |
| `baseline_stars_24` | `stars` | 0 | – |  |
| `baseline_stop_24` | `stop` | 1 | – | media transport |
| `baseline_stop_24_white` | `stop` | 1 | – | media transport |
| `baseline_storage_24` | `storage` | 0 | – |  |
| `baseline_strikethrough_s_24` | `strikethrough_s` | 0 | – |  |
| `baseline_swap_horiz_24` | `swap_horiz` | 0 | – |  |
| `baseline_sync_24` | `sync` | 0 | – |  |
| `baseline_sync_problem_24` | `sync_problem` | 0 | – |  |
| `baseline_sync_white_24` | `sync` | 0 | – |  |
| `baseline_system_update_24` | `system_update` | 0 | – |  |
| `baseline_tab_24` | `tab` | 0 | – |  |
| `baseline_tdlib_24` | kept | – | – | kept: brand: TDLib logo |
| `baseline_telegram_24` | kept | – | – | kept: brand: Telegram paper plane |
| `baseline_text_search_variant_24` | `manage_search` | 0 | – | show only found |
| `baseline_thumb_up_24` | `thumb_up` | 0 | – |  |
| `baseline_timer_16` | `timer` | 0 | – |  |
| `baseline_timer_off_16` | `timer_off` | 0 | – |  |
| `baseline_toc_24` | `toc` | 0 | – |  |
| `baseline_translate_14` | `translate` | 0 | – |  |
| `baseline_translate_24` | `translate` | 0 | – |  |
| `baseline_translate_off_24` | kept | – | – | kept: no equivalent: no translate_off in Material Symbols |
| `baseline_tune_24` | `tune` | 0 | – |  |
| `baseline_unarchive_24` | `unarchive` | 0 | – |  |
| `baseline_undo_18` | `undo` | 0 | – |  |
| `baseline_undo_24` | `undo` | 0 | – |  |
| `baseline_unsubscribe_24` | `unsubscribe` | 0 | – |  |
| `baseline_update_24` | `update` | 0 | – |  |
| `baseline_video_chat_24` | `video_chat` | 0 | – |  |
| `baseline_video_library_96` | `video_library` | 0 | – |  |
| `baseline_videocam_16` | `videocam` | 0 | – |  |
| `baseline_videocam_24` | `videocam` | 0 | – |  |
| `baseline_videogame_asset_16` | `videogame_asset` | 0 | – |  |
| `baseline_view_sidebar_24` | `view_sidebar` | 0 | – |  |
| `baseline_visibility_14` | `visibility` | 0 | – |  |
| `baseline_visibility_16` | `visibility` | 0 | – |  |
| `baseline_visibility_24` | `visibility` | 0 | – |  |
| `baseline_volume_off_24_white` | `volume_off` | 0 | – |  |
| `baseline_volume_up_24` | `volume_up` | 0 | – |  |
| `baseline_volume_up_24_white` | `volume_up` | 0 | – |  |
| `baseline_vpn_key_24` | `vpn_key` | 0 | – |  |
| `baseline_warning_14` | `warning` | 0 | – |  |
| `baseline_warning_18` | `warning` | 0 | – |  |
| `baseline_warning_24` | `warning` | 0 | – |  |
| `baseline_watch_later_10` | `schedule` | 1 | – | scheduled badge; old glyph was a solid clock |
| `baseline_watch_later_24` | `schedule` | 1 | – | emoji panel Recent, selected counterpart |
| `baseline_webrtc_24` | kept | – | – | kept: brand: WebRTC logo |
| `baseline_whatshot_24` | `local_fire_department` | 1 | – | emoji panel Trending, selected counterpart ("whatshot" is a trend arrow in Symbols) |
| `baseline_wifi_24` | `wifi` | 0 | – |  |
| `baseline_work_24` | `work` | 0 | – |  |
| `baseline_zoom_in_24` | `zoom_in` | 0 | – |  |
| `baseline_zoom_out_map_24` | `zoom_out_map` | 0 | – |  |
| `deproko_baseline_animals_24` | `pets` | 1 | – | emoji panel Animals, selected |
| `deproko_baseline_animals_filled_blink_24` | `pets` | 1 | – | blink frame of selected Animals (no blink variant) |
| `deproko_baseline_animals_outline_24` | `pets` | 0 | – | emoji panel Animals, inactive |
| `deproko_baseline_attach_26` | `attach_file` | 0 | – | composer attach |
| `deproko_baseline_bots_16` | `smart_toy` | 0 | – | bot |
| `deproko_baseline_bots_24` | `smart_toy` | 0 | – | bot |
| `deproko_baseline_bots_56` | `smart_toy` | 0 | – | bot avatar |
| `deproko_baseline_bots_command_26` | `terminal` | 0 | – | bot commands button |
| `deproko_baseline_bots_keyboard_26` | `grid_view` | 0 | – | bot keyboard button; must differ from baseline_keyboard_24 (both are composer command states) |
| `deproko_baseline_camera_26` | `photo_camera` | 0 | – | composer camera |
| `deproko_baseline_check_double_24` | `done_all` | 0 | 4.7,7.7,20.4,16.7 | message read ticks |
| `deproko_baseline_check_single_24` | `check` | 0 | 4.7,7.7,16.2,16.7 | message sent tick |
| `deproko_baseline_clock_24` | `schedule` | 0 | 6.0,6.0,18.0,18.0 | message sending clock |
| `deproko_baseline_close_10` | `close` | 0 | – | file progress cancel |
| `deproko_baseline_close_18` | `close` | 0 | – | file progress cancel |
| `deproko_baseline_close_24` | `close` | 0 | – | file progress cancel |
| `deproko_baseline_flag_filled_24` | `flag` | 1 | – | emoji panel Flags, selected |
| `deproko_baseline_flag_outline_24` | `flag` | 0 | – | emoji panel Flags, inactive |
| `deproko_baseline_gif_24` | `gif_box` | 0 | – | GIF section, inactive |
| `deproko_baseline_gif_filled_16` | `gif_box` | 1 | – | filled by name (inline preview) |
| `deproko_baseline_gif_filled_24` | `gif_box` | 1 | – | GIF section, selected |
| `deproko_baseline_insert_emoticon_26` | `mood` | 0 | – | composer emoji button |
| `deproko_baseline_insert_sticker_24` | `sticker` | 0 | – |  |
| `deproko_baseline_insert_sticker_26` | `sticker` | 0 | – |  |
| `deproko_baseline_instantview_24` | `bolt` | 0 | 8.1,5.2,16.9,19.4 | Instant View |
| `deproko_baseline_lamp_22` | `lightbulb` | 0 | – | poll explanation, inactive |
| `deproko_baseline_lamp_24` | `lightbulb` | 0 | – | emoji panel Symbols, inactive |
| `deproko_baseline_lamp_filled_22` | `lightbulb` | 1 | – | poll explanation, active |
| `deproko_baseline_lamp_filled_24` | `lightbulb` | 1 | – | emoji panel Symbols, selected |
| `deproko_baseline_link_arrow_20` | `arrow_outward` | 0 | 8.5,7.3,16.7,15.5 | inline keyboard URL button corner |
| `deproko_baseline_lock_16` | `lock` | 0 | 8.0,6.5,16.0,17.0 |  |
| `deproko_baseline_lock_18` | `lock` | 0 | 8.0,6.5,16.0,17.0 | secret chat |
| `deproko_baseline_lock_24` | `lock` | 0 | 8.0,6.5,16.0,17.0 |  |
| `deproko_baseline_masks_24` | `theater_comedy` | 0 | – | masks (not the medical "masks" symbol) |
| `deproko_baseline_mosaic_group_24` | `auto_awesome_mosaic` | 0 | – | group media |
| `deproko_baseline_msg_video_16` | `videocam` | 0 | – | video message |
| `deproko_baseline_msg_video_24` | `videocam` | 0 | – | video message |
| `deproko_baseline_msg_video_96` | `videocam` | 0 | – | video message |
| `deproko_baseline_msg_voice_24` | `mic` | 0 | – | voice message |
| `deproko_baseline_notifications_off_24` | `notifications_off` | 0 | 6.0,6.0,18.0,19.0 | muted chat |
| `deproko_baseline_outinline_24` | `picture_in_picture_alt` | 0 | – | picture-in-picture |
| `deproko_baseline_pin_14` | `keep` | 0 | – | pinned |
| `deproko_baseline_pin_16` | `keep` | 0 | – | pinned |
| `deproko_baseline_pin_24` | `keep` | 0 | – | pin |
| `deproko_baseline_pin_undo_24` | `keep_off` | 0 | – | unpin |
| `deproko_baseline_send_24` | `send` | 0 | – |  |
| `deproko_baseline_sound_muted_24` | `volume_off` | 0 | 5.2,6.0,19.0,18.0 |  |
| `deproko_baseline_stickers_24` | `sticker` | 0 | – |  |
| `deproko_baseline_stickers_filled_24` | `sticker` | 1 | – | filled by name |
| `deproko_baseline_text_add_24` | `text_fields` | 0 | – | add text (media editor) |
| `deproko_baseline_verify_24` | `verified` | 1 | 4.0,4.0,20.0,20.0 | verified badge (solid by design) |
| `deproko_baseline_verify_chat_24` | `verified` | 1 | 5.2,5.2,18.8,18.8 | verified badge (solid by design) |
| `deproko_baseline_whatshot_16` | `local_fire_department` | 0 | – | secret media |
| `deproko_baseline_whatshot_24` | `local_fire_department` | 0 | – | secret media / hide media |
| `deproko_logo_telegram_18` | kept | – | – | kept: brand: Telegram logo |
| `deproko_logo_telegram_passcode_56` | kept | – | – | kept: brand: Telegram logo |
| `outline_forum_24` | `forum` | 0 | – |  |
| `outline_notifications_24` | `notifications` | 0 | – |  |
| `outline_perm_contact_calendar_24` | `perm_contact_calendar` | 0 | – |  |
| `outline_phone_24` | `call` | 0 | – | nav bar Calls, inactive |
| `outline_toggle_on_24` | `toggle_on` | 0 | – |  |
| `outline_whatshot_24` | `local_fire_department` | 0 | – | emoji panel Trending, inactive ("whatshot" is a trend arrow in Symbols) |
| `round_call_24_white` | `call` | 0 | – |  |
| `round_call_end_24_white` | `call_end` | 0 | – |  |
| `round_keyboard_arrow_left_24` | `keyboard_arrow_left` | 0 | – |  |
| `round_keyboard_arrow_right_16` | `keyboard_arrow_right` | 0 | – |  |
| `round_keyboard_arrow_right_18` | `keyboard_arrow_right` | 0 | – |  |
| `round_keyboard_arrow_right_24` | `keyboard_arrow_right` | 0 | – |  |
| `round_repeat_24` | `repeat` | 0 | – |  |
| `round_repeat_one_24` | `repeat_one` | 0 | – |  |
| `round_shuffle_24` | `shuffle` | 0 | – |  |
| `round_skip_next_36` | `skip_next` | 1 | – | media transport |
| `round_skip_previous_36` | `skip_previous` | 1 | – | media transport |
| `round_warning_16` | `warning` | 0 | – |  |
| `templarian_baseline_account_switch_24` | `switch_account` | 0 | – | transfer ownership |
| `templarian_baseline_broom_24` | `cleaning_services` | 0 | – | clear history |
| `templarian_baseline_calculator_18` | `calculate` | 0 | – |  |
| `templarian_baseline_cat_24` | `pets` | 0 | – | folder icon "Cat" |
| `templarian_baseline_device_ubuntu_24` | kept | – | – | kept: brand: Ubuntu logo |
| `templarian_baseline_flask_24` | `science` | 0 | – | beta |
| `templarian_baseline_folder_plus_24` | `create_new_folder` | 0 | – | add to folder |
| `templarian_baseline_folder_remove_24` | `folder_off` | 0 | – | remove from folder |
| `templarian_baseline_foursquare_24` | kept | – | – | kept: brand: Foursquare logo (venue provider) |
| `templarian_baseline_gamepad_variant_16` | `sports_esports` | 0 | – | game |
| `templarian_baseline_import_16` | `input` | 0 | – | imported chat |
| `templarian_baseline_source_merge_24` | `merge` | 0 | – | source code / pull request |
| `templarian_device_web_chrome_24` | kept | – | – | kept: brand: Chrome logo |
| `templarian_device_web_edge_24` | kept | – | – | kept: brand: Edge logo |
| `templarian_device_web_firefox_24` | kept | – | – | kept: brand: Firefox logo |
| `templarian_device_web_safari_24` | kept | – | – | kept: brand: Safari logo |

## Glyphs drawn in code (not converted yet)

These icons are painted with `Canvas`/`Path` in Java, so the drawable swap does not reach them. Paths are relative
to `app/src/main/java/org/thunderdog/challegram/`.

| File | Method | Glyph |
|---|---|---|
| `tool/DrawAlgorithms.java` | `drawPlayPause` / `buildPlayPause` | play ↔ pause morph (file/music progress, `PlayPauseButton`, `PlaybackController`, `HeaderFilling` player, theme preview) |
| `tool/DrawAlgorithms.java` | `drawMark` | checkmark (`HeaderFilling` player bar, `LiveLocationHelper`) |
| `tool/DrawAlgorithms.java` | `drawCross` | diagonal "off" slash over an icon (mic/hang-up in `HeaderFilling`, `CallController`, `CircleButton`) |
| `tool/DrawAlgorithms.java` | `drawAnimatedCross` | animated ✕ (`ClearButton`, `SimplestCheckBox` cross mode) |
| `tool/DrawAlgorithms.java` | `drawCollapse` | chevron ↔ ✕ (`PinnedMessagesBar`, Instant View details `PageBlockRichText`) |
| `tool/DrawAlgorithms.java` | `drawDirection` | chevron arrow left/right/up/down (`TGCommentButton`, `VideoTimelineView`, `RecordLockView`, `RecordAudioVideoController` "slide to cancel") |
| `tool/DrawAlgorithms.java` | `drawHorizontalDirection` | double chevrons (`widget/voip/SlideHintView`) |
| `tool/DrawAlgorithms.java` | `drawCloud` | cloud download/upload state (`FileProgressComponent`) |
| `tool/DrawAlgorithms.java` | `drawSimplestCheckBox` | round checkbox with check (avatar selection) |
| `widget/FileProgressComponent.java` | `drawCloudState` | download arrow / cloud / cancel ✕ over files and media |
| `widget/SimplestCheckBox.java` | `drawInBitmap` | filled circle with checkmark (media/chat selection) |
| `widget/CheckBoxView.java` | `onDraw`, `drawCheckBox` | square checkbox + checkmark (settings rows) |
| `widget/RadioView.java` | `onDraw` | radio button |
| `widget/CheckView.java` | `onDraw` | circular check (selection) |
| `widget/SendButton.java` | `onDraw` | composer send button: edit-mode checkmark (two rotated bars), progress arc |
| `component/chat/SilentButton.java` | `onDraw` | slash over `outline_notifications_24` (silent broadcast) |
| `navigation/BackHeaderButton.java` | `drawButton` | header ☰ menu ↔ ← back ↔ ✕ close morph |
| `navigation/StopwatchHeaderButton.java` | `onDraw` | stopwatch / self-destruct timer header button |
| `navigation/TriangleView.java` | `draw` | small dropdown triangle (`ToggleHeaderView`, `RotationControlView`) |
| `widget/ExpanderView.java` | `draw` | rotating expand arrow (`DrawerHeaderView` account switcher) |
| `widget/ClearButton.java` | `onDraw` | clear-text ✕ |
| `widget/LinkPreviewToggleView.java` | `onDraw` | animated text lines of the link-preview position icon (with `baseline_link_preview_*` layers) |
| `player/RecordLockView.java` | `onDraw` | voice-record lock: shackle arc + body, pause bars, up chevron |
| `data/TGMessageRich.java` | `drawChevron` | expand/collapse chevron (quote/rich blocks) |
| `data/TGMessageChecklist.java` | `drawContent` | done checkmark of checklist items (plus `SimplestCheckBox`) |
| `data/TGMessagePoll.java` | `drawContent` | light rays around the poll explanation lamp (`deproko_baseline_lamp_*`) |

## Telegram for Android icon set (since build -81)

Icons are being moved to the official Telegram for Android 12.3.1 set (last release before the 2026 redesign).
`scripts/icons/tg-mapping.tsv` maps each of our files to a Telegram drawable (confidence high/medium/low);
`scripts/icons/tg_apply.py <Telegram res dir>` applies high+medium rows: same dp size → Telegram's own PNG per
density (mdpi..xxhdpi), smaller Telegram glyph → centred without resampling, larger → downscaled; large
(>40dp) icons that would need upscaling stay Material until redrawn. Remaining Material icons (low/none and
those large ones) are being replaced with icons from Telegram Desktop / iOS or drawn in Telegram's style.
