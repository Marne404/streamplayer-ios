package de.marne.spike

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
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.presentationSize
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.timeControlStatus
import platform.AVKit.AVPictureInPictureController
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSURL
import platform.UIKit.UIColor
import platform.UIKit.UIView

/** Eine UIView, deren einzige Aufgabe es ist, den AVPlayerLayer immer randgenau mitzuziehen. */
class PlayerView : UIView(frame = CGRectMake(0.0, 0.0, 16.0, 9.0)) {
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

data class PlayerState(
    val loaded: String? = null,
    val referer: String? = null,
    val item: String = "leer",
    val playing: Boolean = false,
    val position: Double = 0.0,
    val size: String = "",
    val pipPossible: Boolean = false,
    val pipActive: Boolean = false,
    val error: String? = null,
)

/**
 * AVPlayer mit eigenen Kopfzeilen, so wie Streamplayer auf Android Referer und User-Agent
 * mitschickt. Bild im Bild läuft über AVPictureInPictureController am eigenen Layer – damit
 * bleibt die Steuerung später frei in Compose (Ivory & Ink) gestaltbar.
 */
class SpikePlayer {
    private val player = AVPlayer()
    val view = PlayerView().also { it.playerLayer.player = player }
    private var pip: AVPictureInPictureController? = null
    private var loadedUrl: String? = null
    private var loadedReferer: String? = null

    fun load(url: String, referer: String?) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        val headers = mutableMapOf<Any?, Any?>(
            "User-Agent" to "Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1",
        )
        if (!referer.isNullOrBlank()) headers["Referer"] = referer
        // Inoffizieller, aber seit Jahren stabiler Schlüssel für eigene HTTP-Kopfzeilen.
        val asset = AVURLAsset(uRL = nsUrl, options = mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to headers))
        player.replaceCurrentItemWithPlayerItem(AVPlayerItem(asset = asset))
        player.play()
        loadedUrl = url
        loadedReferer = referer
        if (pip == null && AVPictureInPictureController.isPictureInPictureSupported()) {
            pip = AVPictureInPictureController(playerLayer = view.playerLayer).also {
                it.canStartPictureInPictureAutomaticallyFromInline = true
            }
        }
    }

    fun togglePlay() {
        if (player.timeControlStatus == AVPlayerTimeControlStatusPlaying) player.pause() else player.play()
    }

    fun togglePip() {
        val p = pip ?: return
        if (p.pictureInPictureActive) p.stopPictureInPicture() else p.startPictureInPicture()
    }

    fun state(): PlayerState {
        val item = player.currentItem
        val status = when (item?.status) {
            null -> "leer"
            AVPlayerItemStatusReadyToPlay -> "bereit"
            AVPlayerItemStatusFailed -> "Fehler"
            else -> "lädt …"
        }
        val waiting = player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
        val size = item?.presentationSize?.useContents { if (width > 0) "${width.toInt()}×${height.toInt()}" else "" } ?: ""
        return PlayerState(
            loaded = loadedUrl,
            referer = loadedReferer,
            item = if (waiting && status == "bereit") "puffert …" else status,
            playing = player.timeControlStatus == AVPlayerTimeControlStatusPlaying,
            position = CMTimeGetSeconds(player.currentTime()).let { if (it.isNaN()) 0.0 else it },
            size = size,
            pipPossible = pip?.pictureInPicturePossible ?: false,
            pipActive = pip?.pictureInPictureActive ?: false,
            error = item?.error?.localizedDescription,
        )
    }
}
