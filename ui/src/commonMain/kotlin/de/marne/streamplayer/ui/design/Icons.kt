// Ivory & Ink – Material Symbols Rounded, Strichstärke 300, als Compose-Ressourcen (Android + iPad).
// Die Namen, die Components.kt erwartet (chevron, back, check, info, expandMore, expandLess), bleiben gleich.
package de.marne.streamplayer.ui.design

import de.marne.streamplayer.ui.res.Res
import de.marne.streamplayer.ui.res.ic_account_circle
import de.marne.streamplayer.ui.res.ic_add
import de.marne.streamplayer.ui.res.ic_arrow_back
import de.marne.streamplayer.ui.res.ic_arrow_forward
import de.marne.streamplayer.ui.res.ic_auto_awesome
import de.marne.streamplayer.ui.res.ic_bookmark
import de.marne.streamplayer.ui.res.ic_bookmark_filled
import de.marne.streamplayer.ui.res.ic_brightness_6
import de.marne.streamplayer.ui.res.ic_check
import de.marne.streamplayer.ui.res.ic_check_circle
import de.marne.streamplayer.ui.res.ic_chevron_right
import de.marne.streamplayer.ui.res.ic_circle
import de.marne.streamplayer.ui.res.ic_close
import de.marne.streamplayer.ui.res.ic_cloud_done
import de.marne.streamplayer.ui.res.ic_cloud_off
import de.marne.streamplayer.ui.res.ic_cloud_sync
import de.marne.streamplayer.ui.res.ic_contrast
import de.marne.streamplayer.ui.res.ic_crop_free
import de.marne.streamplayer.ui.res.ic_dark_mode
import de.marne.streamplayer.ui.res.ic_delete
import de.marne.streamplayer.ui.res.ic_download
import de.marne.streamplayer.ui.res.ic_download_filled
import de.marne.streamplayer.ui.res.ic_edit
import de.marne.streamplayer.ui.res.ic_edit_note
import de.marne.streamplayer.ui.res.ic_error
import de.marne.streamplayer.ui.res.ic_expand_less
import de.marne.streamplayer.ui.res.ic_expand_more
import de.marne.streamplayer.ui.res.ic_fit_screen
import de.marne.streamplayer.ui.res.ic_folder_open
import de.marne.streamplayer.ui.res.ic_forward_10
import de.marne.streamplayer.ui.res.ic_group
import de.marne.streamplayer.ui.res.ic_home
import de.marne.streamplayer.ui.res.ic_image
import de.marne.streamplayer.ui.res.ic_info
import de.marne.streamplayer.ui.res.ic_language
import de.marne.streamplayer.ui.res.ic_language_filled
import de.marne.streamplayer.ui.res.ic_light_mode
import de.marne.streamplayer.ui.res.ic_lock
import de.marne.streamplayer.ui.res.ic_lock_open
import de.marne.streamplayer.ui.res.ic_logout
import de.marne.streamplayer.ui.res.ic_mail
import de.marne.streamplayer.ui.res.ic_more_vert
import de.marne.streamplayer.ui.res.ic_movie
import de.marne.streamplayer.ui.res.ic_open_in_new
import de.marne.streamplayer.ui.res.ic_pause
import de.marne.streamplayer.ui.res.ic_play_arrow
import de.marne.streamplayer.ui.res.ic_playlist_play
import de.marne.streamplayer.ui.res.ic_refresh
import de.marne.streamplayer.ui.res.ic_remove
import de.marne.streamplayer.ui.res.ic_replay_10
import de.marne.streamplayer.ui.res.ic_schedule
import de.marne.streamplayer.ui.res.ic_search
import de.marne.streamplayer.ui.res.ic_settings
import de.marne.streamplayer.ui.res.ic_settings_filled
import de.marne.streamplayer.ui.res.ic_share
import de.marne.streamplayer.ui.res.ic_shield
import de.marne.streamplayer.ui.res.ic_shield_lock
import de.marne.streamplayer.ui.res.ic_skip_next
import de.marne.streamplayer.ui.res.ic_speed
import de.marne.streamplayer.ui.res.ic_subtitles
import de.marne.streamplayer.ui.res.ic_tv
import de.marne.streamplayer.ui.res.ic_upload_file
import de.marne.streamplayer.ui.res.ic_video_library
import de.marne.streamplayer.ui.res.ic_video_library_filled
import de.marne.streamplayer.ui.res.ic_volume_up
import de.marne.streamplayer.ui.res.ic_wifi
import org.jetbrains.compose.resources.DrawableResource

object Ic {
    // Navigation (gefüllt nur für den gewählten Tab)
    val browser = Res.drawable.ic_language
    val browserFilled = Res.drawable.ic_language_filled
    val downloads = Res.drawable.ic_download
    val downloadsFilled = Res.drawable.ic_download_filled
    val settings = Res.drawable.ic_settings
    val settingsFilled = Res.drawable.ic_settings_filled

    // Allgemein
    val back = Res.drawable.ic_arrow_back
    val forward = Res.drawable.ic_arrow_forward
    val chevron = Res.drawable.ic_chevron_right
    val close = Res.drawable.ic_close
    val check = Res.drawable.ic_check
    val checkCircle = Res.drawable.ic_check_circle
    val circle = Res.drawable.ic_circle
    val info = Res.drawable.ic_info
    val error = Res.drawable.ic_error
    val refresh = Res.drawable.ic_refresh
    val delete = Res.drawable.ic_delete
    val pause = Res.drawable.ic_pause
    val play = Res.drawable.ic_play_arrow
    val openNew = Res.drawable.ic_open_in_new
    val more = Res.drawable.ic_more_vert
    val share = Res.drawable.ic_share
    val home = Res.drawable.ic_home
    val search = Res.drawable.ic_search
    val lock = Res.drawable.ic_lock
    val movie = Res.drawable.ic_movie
    val wifi = Res.drawable.ic_wifi
    val expandMore = Res.drawable.ic_expand_more
    val expandLess = Res.drawable.ic_expand_less
    val shield = Res.drawable.ic_shield
    val shieldLock = Res.drawable.ic_shield_lock
    val schedule = Res.drawable.ic_schedule
    val dark = Res.drawable.ic_dark_mode
    val light = Res.drawable.ic_light_mode
    val contrast = Res.drawable.ic_contrast
    val folder = Res.drawable.ic_folder_open
    val library = Res.drawable.ic_video_library
    val libraryFilled = Res.drawable.ic_video_library_filled
    val bookmark = Res.drawable.ic_bookmark
    val bookmarkFilled = Res.drawable.ic_bookmark_filled
    val add = Res.drawable.ic_add
    val edit = Res.drawable.ic_edit
    val importFile = Res.drawable.ic_upload_file
    val image = Res.drawable.ic_image
    val minus = Res.drawable.ic_remove
    val sparkle = Res.drawable.ic_auto_awesome
    val editNote = Res.drawable.ic_edit_note
    val tv = Res.drawable.ic_tv
    val download = Res.drawable.ic_download
    // Konto und Sync
    val account = Res.drawable.ic_account_circle
    val logout = Res.drawable.ic_logout
    val cloudDone = Res.drawable.ic_cloud_done
    val cloudSync = Res.drawable.ic_cloud_sync
    val cloudOff = Res.drawable.ic_cloud_off
    val group = Res.drawable.ic_group
    val mail = Res.drawable.ic_mail
    // Player
    val replay10 = Res.drawable.ic_replay_10
    val forward10 = Res.drawable.ic_forward_10
    val skipNext = Res.drawable.ic_skip_next
    val lockOpen = Res.drawable.ic_lock_open
    val fitScreen = Res.drawable.ic_fit_screen
    val cropFree = Res.drawable.ic_crop_free
    val subtitles = Res.drawable.ic_subtitles
    val speed = Res.drawable.ic_speed
    val brightness = Res.drawable.ic_brightness_6
    val volume = Res.drawable.ic_volume_up
    val episodes = Res.drawable.ic_playlist_play
}
