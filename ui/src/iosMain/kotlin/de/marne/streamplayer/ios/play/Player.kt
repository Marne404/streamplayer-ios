package de.marne.streamplayer.ios.play

import kotlinx.cinterop.useContents
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.timeControlStatus
import platform.AVKit.AVPictureInPictureController
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIColor
import platform.UIKit.UIView

/** UIView, die den AVPlayerLayer immer randgenau mitzieht. */
internal class PlayerView : UIView(frame = CGRectMake(0.0, 0.0, 16.0, 9.0)) {
    val playerLayer = AVPlayerLayer()

    init {
        backgroundColor = UIColor.blackColor
        playerLayer.videoGravity = AVLayerVideoGravityResizeAspect
        layer.addSublayer(playerLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        playerLayer.frame = bounds
    }
}

internal data class PlayState(
    val ready: Boolean = false,
    val failed: String? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val pip: Boolean = false,
    val pipPossible: Boolean = false,
)

/**
 * AVPlayer mit den Kopfzeilen der Seite (Referer, Safari-Kennung) wie die Android-App, Bild im
 * Bild am eigenen Layer – die Steuerung zeichnet Compose (Ivory & Ink).
 */
internal class IpadPlayer {
    private val player = AVPlayer()
    val view = PlayerView().also { it.playerLayer.player = player }
    private var pipController: AVPictureInPictureController? = null

    fun load(f: Found, startMs: Long) {
        val url = NSURL.URLWithString(f.url) ?: return
        val headers = mutableMapOf<Any?, Any?>("User-Agent" to SAFARI_UA)
        f.referer?.let { headers["Referer"] = it }
        val asset = AVURLAsset(uRL = url, options = mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to headers))
        player.replaceCurrentItemWithPlayerItem(AVPlayerItem(asset = asset))
        if (startMs > 0) seek(startMs)
        player.play()
        UIApplication.sharedApplication.idleTimerDisabled = true
        if (pipController == null && AVPictureInPictureController.isPictureInPictureSupported()) {
            pipController = AVPictureInPictureController(playerLayer = view.playerLayer).also {
                it.canStartPictureInPictureAutomaticallyFromInline = true
            }
        }
    }

    fun toggle() { if (player.timeControlStatus == AVPlayerTimeControlStatusPlaying) player.pause() else player.play() }
    fun pause() = player.pause()
    fun seek(ms: Long) = player.seekToTime(CMTimeMakeWithSeconds(ms / 1000.0, 600))
    fun skip(deltaMs: Long) = seek((state().positionMs + deltaMs).coerceAtLeast(0))

    fun togglePip() {
        val p = pipController ?: return
        if (p.pictureInPictureActive) p.stopPictureInPicture() else p.startPictureInPicture()
    }

    fun state(): PlayState {
        val item = player.currentItem
        val pos = CMTimeGetSeconds(player.currentTime()).let { if (it.isNaN()) 0.0 else it }
        val dur = item?.duration?.let { CMTimeGetSeconds(it) }?.takeIf { !it.isNaN() && !it.isInfinite() } ?: 0.0
        return PlayState(
            ready = item?.status == AVPlayerItemStatusReadyToPlay,
            failed = if (item?.status == AVPlayerItemStatusFailed) item.error?.localizedDescription ?: "Wiedergabe fehlgeschlagen" else null,
            playing = player.timeControlStatus == AVPlayerTimeControlStatusPlaying,
            buffering = player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate,
            positionMs = (pos * 1000).toLong(),
            durationMs = (dur * 1000).toLong(),
            pip = pipController?.pictureInPictureActive ?: false,
            pipPossible = pipController?.pictureInPicturePossible ?: false,
        )
    }

    fun release() {
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        UIApplication.sharedApplication.idleTimerDisabled = false
    }
}
