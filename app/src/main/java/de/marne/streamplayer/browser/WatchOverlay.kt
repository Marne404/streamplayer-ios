package de.marne.streamplayer.browser

import de.marne.streamplayer.ui.candidateMeta

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.ui.Format
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.PrimaryButton
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.SecondaryButton
import de.marne.streamplayer.ui.design.StatusLine
import de.marne.streamplayer.ui.design.Thumb
import de.marne.streamplayer.ui.design.VSpace
import de.marne.streamplayer.ui.tr

/** Deckt die WebView ab, solange „Nur ansehen“ läuft. Fängt alle Berührungen ab. */
@Composable
fun WatchOverlay(
    state: WatchController.State,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onShowPage: () -> Unit,
    onCancel: () -> Unit,
    saved: Boolean = false,
    onSave: () -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center,
    ) {
        // Die Spezifikation wird im Composable gelesen; transitionSpec selbst ist kein Composable.
        val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        AnimatedContent(
            targetState = state,
            contentKey = { it::class },
            transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
            label = "watch",
        ) { s ->
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (s) {
                    is WatchController.State.Searching -> {
                        LoadingIndicator(color = Palette.ink, modifier = Modifier.size(56.dp))
                        VSpace(28.dp)
                        Eyebrow(s.host)
                        Text(tr("Suche das Video …", "Looking for the video …"), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                        VSpace(6.dp)
                        Text(s.step, style = MaterialTheme.typography.bodyMedium, color = Palette.muted, textAlign = TextAlign.Center)
                        VSpace(32.dp)
                        QuietButton(tr("Abbrechen", "Cancel"), color = Palette.muted, onClick = onCancel)
                    }
                    is WatchController.State.Ready -> {
                        Thumb(s.thumbnail, Modifier.fillMaxWidth(0.72f).aspectRatio(16f / 9f), radius = 22.dp)
                        VSpace(24.dp)
                        Eyebrow(Format.candidateMeta(s.candidate))
                        Text(
                            s.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                            maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                        if (s.candidate.live) {
                            VSpace(10.dp)
                            StatusLine(Ic.info, tr("Livestream. Ansehen geht, Laden nicht.", "Livestream. You can watch it, but not download it."), Palette.muted)
                        }
                        VSpace(32.dp)
                        PrimaryButton(tr("Nochmal ansehen", "Watch again"), icon = Ic.play, onClick = onPlay)
                        if (s.candidate.downloadable) {
                            VSpace(12.dp)
                            SecondaryButton(tr("Laden", "Download"), icon = Ic.download, onClick = onDownload)
                        }
                        VSpace(4.dp)
                        androidx.compose.foundation.layout.Row {
                            QuietButton(if (saved) tr("In der Bibliothek", "In your library") else tr("Merken", "Save"), color = if (saved) Palette.muted else Palette.text, onClick = onSave)
                            QuietButton(tr("Seite anzeigen", "Show page"), color = Palette.muted, onClick = onShowPage)
                        }
                    }
                    is WatchController.State.NotFound -> {
                        Eyebrow(s.host)
                        Text(tr("Kein Video gefunden.", "No video found."), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                        VSpace(6.dp)
                        Text(
                            tr("Manche Seiten zeigen es erst nach einem Tipp. Öffne die Seite und starte es selbst.", "Some sites only show it after a tap. Open the page and start it yourself."),
                            style = MaterialTheme.typography.bodyMedium, color = Palette.muted, textAlign = TextAlign.Center,
                        )
                        VSpace(32.dp)
                        PrimaryButton(tr("Seite anzeigen", "Show page"), icon = Ic.browser, onClick = onShowPage)
                        VSpace(4.dp)
                        QuietButton(tr("Abbrechen", "Cancel"), color = Palette.muted, onClick = onCancel)
                    }
                    is WatchController.State.Protected -> {
                        Eyebrow(tr("Geschützt", "Protected"))
                        Text(s.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        VSpace(10.dp)
                        StatusLine(Ic.shieldLock, tr("Kopierschutz (DRM). Geht nur auf der Seite selbst.", "Copy protection (DRM). Only plays on the site itself."), Palette.wine)
                        VSpace(32.dp)
                        PrimaryButton(tr("Seite anzeigen", "Show page"), icon = Ic.browser, onClick = onShowPage)
                        VSpace(4.dp)
                        QuietButton(tr("Abbrechen", "Cancel"), color = Palette.muted, onClick = onCancel)
                    }
                    // Kommt nicht vor: bei einer Prüfung tritt die Abdeckung beiseite (ChallengeBar).
                    is WatchController.State.Challenge, WatchController.State.Idle -> Unit
                }
            }
        }
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted, textAlign = TextAlign.Center, maxLines = 1)
    VSpace(8.dp)
}

/**
 * Leiste über der Seite, solange eine Prüfung wartet: Die Abdeckung ist weg, damit man das Kästchen
 * antippen kann. Kommt von unten und federt an; geht kürzer, als sie kam. Danach sucht die App
 * von selbst weiter.
 */
@Composable
fun ChallengeBar(visible: Boolean, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val bouncy = androidx.compose.animation.core.CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
    val spring = androidx.compose.animation.core.CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(420, easing = bouncy)) { it } +
            fadeIn(androidx.compose.animation.core.tween(220)),
        exit = androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(320, easing = spring)) { it } +
            fadeOut(androidx.compose.animation.core.tween(200)),
    ) {
        val shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
        androidx.compose.foundation.layout.Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .shadow(16.dp, shape, ambientColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.18f), spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.18f))
                .background(Palette.ink, shape)
                .padding(start = 18.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            de.marne.streamplayer.ui.design.GIcon(Ic.shieldLock, tint = Palette.onInk, size = 22.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                Text(tr("Kurz bestätigen", "Quick check"), style = MaterialTheme.typography.titleSmall, color = Palette.onInk)
                Text(
                    tr("Die Seite will wissen, ob ein Mensch da ist. Bestätige es oben – danach geht es von selbst weiter.",
                        "The site wants to know you’re human. Confirm it above – then it carries on by itself."),
                    style = MaterialTheme.typography.bodySmall, color = Palette.onInk.copy(alpha = 0.72f),
                )
            }
            QuietButton(tr("Abbrechen", "Cancel"), color = Palette.onInk, onClick = onCancel)
        }
    }
}

