package de.marne.streamplayer.account

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marne.streamplayer.App
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.IvoryField
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.Serif
import de.marne.streamplayer.ui.design.VSpace

/** Gedeckte Töne aus Ivory & Ink: Messing, Grün, Wein, Blau, Pflaume, Graphit. */
private val ProfileColors = listOf(
    Color(0xFF8E6F43), Color(0xFF34503F), Color(0xFF7A2E2E),
    Color(0xFF3B4D63), Color(0xFF5E4B6E), Color(0xFF5B5750),
)
private val OnProfile = Color(0xFFF4F1EA)

private val Spring = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
private val Bouncy = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/** Rundes Profilbild: Anfangsbuchstabe auf der Profilfarbe. */
@Composable
fun ProfileAvatar(p: Profile, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(ProfileColors[p.color.mod(ProfileColors.size)]),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            p.name.trim().take(1).uppercase().ifEmpty { "?" },
            color = OnProfile,
            fontFamily = Serif,
            fontSize = (size.value * 0.46f).sp,
            lineHeight = (size.value * 0.5f).sp,
        )
    }
}

/**
 * „Wer schaut?“ – Profil wählen, anlegen, umbenennen, löschen. [canDismiss]: beim App-Start
 * mit mehreren Profilen muss gewählt werden, sonst lässt sich der Bildschirm wegtippen.
 */
@Composable
fun ProfilePicker(visible: Boolean, startManaging: Boolean, canDismiss: Boolean, onDone: () -> Unit) {
    val app = App.instance
    val profiles by app.profiles.profiles.collectAsStateWithLifecycle()
    val active by app.profiles.activeId.collectAsStateWithLifecycle()
    var managing by remember(visible) { mutableStateOf(startManaging) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    var adding by remember { mutableStateOf(false) }

    BackHandler(enabled = visible) { if (managing && !startManaging) managing = false else if (canDismiss) onDone() else app.profiles.select(active).also { onDone() } }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(420, easing = Spring)) + scaleIn(tween(420, easing = Bouncy), initialScale = 0.96f),
        exit = fadeOut(tween(220, easing = Spring)) + scaleOut(tween(220, easing = Spring), targetScale = 0.98f),
    ) {
        // Hülle zuerst, Kacheln danach – im 30-ms-Takt in Lesereihenfolge.
        val intro = remember { Animatable(0f) }
        LaunchedEffect(Unit) { intro.animateTo(1f, tween(150 + 6 * 30 + 420, easing = LinearEasing)) }
        val lift = with(LocalDensity.current) { 16.dp.toPx() }
        fun Modifier.enter(i: Int) = graphicsLayer {
            val t = ((intro.value * (150 + 6 * 30 + 420) - 150 - i.coerceAtMost(6) * 30) / 420f).coerceIn(0f, 1f)
            translationY = (1f - Bouncy.transform(t)) * lift
            alpha = Spring.transform(t)
        }

        Box(
            Modifier.fillMaxSize().background(Palette.bg)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        ) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(if (managing) "PROFILE" else "STREAMPLAYER", style = MaterialTheme.typography.labelSmall, color = Palette.muted, modifier = Modifier.enter(0))
                VSpace(8.dp)
                Text(if (managing) "Profile verwalten." else "Wer schaut?", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.enter(0))
                VSpace(36.dp)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    profiles.forEachIndexed { i, p ->
                        ProfileTile(p, selected = p.id == active && !managing, managing = managing, modifier = Modifier.enter(i + 1)) {
                            if (managing) editing = p
                            else { app.profiles.select(p.id); onDone() }
                        }
                    }
                    if (profiles.size < MAX_PROFILES) AddTile(Modifier.enter(profiles.size + 1)) { adding = true }
                }
                VSpace(40.dp)
                Box(Modifier.enter(6)) {
                    if (startManaging) QuietButton("Fertig", color = Palette.text, onClick = onDone)
                    else QuietButton(if (managing) "Fertig" else "Profile verwalten", color = Palette.muted) { managing = !managing }
                }
            }
        }
    }

    editing?.let { p -> EditProfileDialog(p, canDelete = profiles.size > 1, onDismiss = { editing = null }) }
    if (adding) NameDialog("Neues Profil", "", confirm = "Anlegen", onDismiss = { adding = false }) { name ->
        app.profiles.add(name)
        adding = false
    }
}

@Composable
private fun ProfileTile(p: Profile, selected: Boolean, managing: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    // Druck unter dem Finger: Kachel 0.92, ohne Kurve.
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(120), label = "press")
    Column(
        modifier.width(96.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Fester Rahmen für alle: der Auswahlring liegt innen, sonst stünden die Namen auf verschiedener Höhe.
        Box(
            Modifier.size(88.dp).border(2.dp, if (selected) Palette.text else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            ProfileAvatar(p, if (selected) 76.dp else 88.dp)
            if (managing) {
                Box(Modifier.size(88.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    GIcon(Ic.edit, tint = OnProfile, size = 26.dp)
                }
            }
        }
        VSpace(10.dp)
        Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AddTile(modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.width(96.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).border(1.dp, Palette.line, CircleShape).background(Palette.surface),
            contentAlignment = Alignment.Center,
        ) { GIcon(Ic.add, tint = Palette.muted, size = 30.dp) }
        VSpace(10.dp)
        Text("Neu", style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
    }
}

@Composable
private fun EditProfileDialog(p: Profile, canDelete: Boolean, onDismiss: () -> Unit) {
    val app = App.instance
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("„${p.name}“ löschen?") },
            text = { Text("Der Fortschritt dieses Profils geht verloren – auf allen Geräten. Die Bibliothek bleibt.") },
            confirmButton = {
                QuietButton("Löschen", color = Palette.wine) {
                    if (app.profiles.remove(p.id)) app.progress.removeProfile(p.id)
                    confirmDelete = false; onDismiss()
                }
            },
            dismissButton = { QuietButton("Abbrechen", color = Palette.muted) { confirmDelete = false } },
            containerColor = Palette.bg,
            shape = RoundedCornerShape(28.dp),
        )
        return
    }
    NameDialog("Profil bearbeiten", p.name, confirm = "Speichern", onDismiss = onDismiss, extra = {
        if (canDelete) QuietButton("Profil löschen", color = Palette.wine) { confirmDelete = true }
    }) { name ->
        app.profiles.rename(p.id, name)
        onDismiss()
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    extra: @Composable () -> Unit = {},
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                IvoryField(name, { name = it.take(24) }, placeholder = "Name")
                VSpace(4.dp)
                extra()
            }
        },
        confirmButton = { QuietButton(confirm, color = if (name.isNotBlank()) Palette.text else Palette.faint) { if (name.isNotBlank()) onConfirm(name) } },
        dismissButton = { QuietButton("Abbrechen", color = Palette.muted, onClick = onDismiss) },
        containerColor = Palette.bg,
        shape = RoundedCornerShape(28.dp),
    )
}

private const val MAX_PROFILES = 6
