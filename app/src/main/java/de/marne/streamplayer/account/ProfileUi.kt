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
import de.marne.streamplayer.ui.tr
import androidx.compose.foundation.layout.Row
import kotlinx.coroutines.launch


private val Spring = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
private val Bouncy = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/**
 * „Wer schaut?“ – Profil wählen, anlegen, umbenennen, löschen, sperren. [canDismiss]: false, wenn
 * gewählt werden muss (Start mit gesperrtem Profil) – dann führt Zurück aus der App, nicht hinein.
 * Gesperrte Profile verlangen die PIN zum Wählen und zum Bearbeiten.
 */
@Composable
fun ProfilePicker(visible: Boolean, startManaging: Boolean, canDismiss: Boolean, onDone: () -> Unit) {
    val app = App.instance
    val profiles by app.profiles.profiles.collectAsStateWithLifecycle()
    val active by app.profiles.activeId.collectAsStateWithLifecycle()
    var managing by remember(visible) { mutableStateOf(startManaging) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    var adding by remember { mutableStateOf(false) }
    // PIN abfragen: zum Wählen (manage = false) oder zum Bearbeiten (true); oder eine neue setzen.
    var pinFor by remember { mutableStateOf<Pair<Profile, Boolean>?>(null) }
    var setPinFor by remember { mutableStateOf<Profile?>(null) }
    val activity = androidx.activity.compose.LocalActivity.current

    BackHandler(enabled = visible) {
        when {
            managing && !startManaging -> managing = false
            canDismiss -> onDone()
            // Ohne Wahl kein Zutritt: Zurück legt die App weg, statt ein gesperrtes Profil zu öffnen.
            else -> activity?.moveTaskToBack(true)
        }
    }

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
                Text(if (managing) tr("PROFILE", "PROFILES") else "STREAMPLAYER", style = MaterialTheme.typography.labelSmall, color = Palette.muted, modifier = Modifier.enter(0))
                VSpace(8.dp)
                Text(if (managing) tr("Profile verwalten.", "Manage profiles.") else tr("Wer schaut?", "Who's watching?"), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.enter(0))
                VSpace(36.dp)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    profiles.forEachIndexed { i, p ->
                        ProfileTile(p, selected = p.id == active && !managing, managing = managing, modifier = Modifier.enter(i + 1)) {
                            when {
                                p.locked -> pinFor = p to managing
                                managing -> editing = p
                                else -> { app.profiles.select(p.id); onDone() }
                            }
                        }
                    }
                    if (profiles.size < MAX_PROFILES) AddTile(Modifier.enter(profiles.size + 1)) { adding = true }
                }
                VSpace(40.dp)
                Box(Modifier.enter(6)) {
                    if (startManaging) QuietButton(tr("Fertig", "Done"), color = Palette.text, onClick = onDone)
                    else QuietButton(if (managing) tr("Fertig", "Done") else tr("Profile verwalten", "Manage profiles"), color = Palette.muted) { managing = !managing }
                }
            }
        }
    }

    editing?.let { p ->
        // Immer den aktuellen Stand zeigen (Sperre gerade gesetzt/aufgehoben).
        val now = profiles.firstOrNull { it.id == p.id } ?: p
        EditProfileDialog(now, canDelete = profiles.size > 1, onDismiss = { editing = null }, onSetPin = { setPinFor = now; editing = null })
    }
    if (adding) NameDialog(tr("Neues Profil", "New profile"), "", confirm = tr("Anlegen", "Create"), onDismiss = { adding = false }) { name ->
        app.profiles.add(name)
        adding = false
    }
    pinFor?.let { (p, manage) ->
        PinDialog(p, onDismiss = { pinFor = null }) {
            pinFor = null
            if (manage) editing = p else { app.profiles.select(p.id); onDone() }
        }
    }
    setPinFor?.let { p ->
        PinSetupDialog(p, onDismiss = { setPinFor = null }) { pin ->
            app.profiles.setLock(p.id, pin)
            setPinFor = null
        }
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
        // Wie bei Netflix: ein kleines Schloss unter gesperrten Profilen – man weiß vorher, dass eine PIN kommt.
        if (p.locked) GIcon(Ic.lock, tint = Palette.muted, size = 16.dp, modifier = Modifier.padding(top = 4.dp))
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
        Text(tr("Neu", "Add"), style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
    }
}

@Composable
private fun EditProfileDialog(p: Profile, canDelete: Boolean, onDismiss: () -> Unit, onSetPin: () -> Unit) {
    val app = App.instance
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("„${p.name}“ löschen?", "Delete “${p.name}”?")) },
            text = { Text(tr("Der Fortschritt dieses Profils geht verloren – auf allen Geräten. Die Bibliothek bleibt.", "This profile's progress will be lost on all devices. The library stays.")) },
            confirmButton = {
                QuietButton(tr("Löschen", "Delete"), color = Palette.wine) {
                    if (app.profiles.remove(p.id)) app.progress.removeProfile(p.id)
                    confirmDelete = false; onDismiss()
                }
            },
            dismissButton = { QuietButton(tr("Abbrechen", "Cancel"), color = Palette.muted) { confirmDelete = false } },
            containerColor = Palette.bg,
            shape = RoundedCornerShape(28.dp),
        )
        return
    }
    NameDialog(tr("Profil bearbeiten", "Edit profile"), p.name, confirm = tr("Speichern", "Save"), onDismiss = onDismiss, extra = {
        // Profilsperre: eine vierstellige PIN wie bei Netflix.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            GIcon(if (p.locked) Ic.lock else Ic.lockOpen, tint = Palette.muted, size = 18.dp)
            Text(
                if (p.locked) tr("Mit PIN gesperrt", "Locked with a PIN") else tr("Nicht gesperrt", "Not locked"),
                style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.padding(start = 8.dp),
            )
        }
        Row {
            QuietButton(if (p.locked) tr("PIN ändern", "Change PIN") else tr("Mit PIN sperren", "Lock with PIN"), color = Palette.text, onClick = onSetPin)
            if (p.locked) QuietButton(tr("Sperre aufheben", "Remove lock"), color = Palette.muted) { app.profiles.setLock(p.id, null) }
        }
        if (canDelete) QuietButton(tr("Profil löschen", "Delete profile"), color = Palette.wine) { confirmDelete = true }
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
                IvoryField(name, { name = it.take(24) }, placeholder = tr("Name", "Name"))
                VSpace(4.dp)
                extra()
            }
        },
        confirmButton = { QuietButton(confirm, color = if (name.isNotBlank()) Palette.text else Palette.faint) { if (name.isNotBlank()) onConfirm(name) } },
        dismissButton = { QuietButton(tr("Abbrechen", "Cancel"), color = Palette.muted, onClick = onDismiss) },
        containerColor = Palette.bg,
        shape = RoundedCornerShape(28.dp),
    )
}

private const val MAX_PROFILES = 6

// ---------- Profilsperre ----------

/**
 * PIN abfragen, bevor ein gesperrtes Profil geöffnet oder bearbeitet wird. Bildschirmfüllend wie
 * bei Netflix; falsche PIN: die Punkte schütteln, kurze Vibration, Eingabe leer. „PIN vergessen?“
 * bestätigt den Kontoinhaber (Passwort bzw. Google) und hebt die Sperre auf.
 */
@Composable
fun PinDialog(p: Profile, onDismiss: () -> Unit, onSuccess: () -> Unit) {
    val app = App.instance
    var forgot by remember { mutableStateOf(false) }
    PinScreen(
        profile = p,
        title = tr("PIN eingeben", "Enter your PIN"),
        hint = tr("„${p.name}“ ist gesperrt.", "“${p.name}” is locked."),
        onDismiss = onDismiss,
        onForgot = { forgot = true },
        check = { pin -> app.profiles.checkPin(p.id, pin) },
        onDone = { onSuccess() },
    )
    if (forgot) ForgotPinDialog(p, onDismiss = { forgot = false }) {
        app.profiles.setLock(p.id, null)
        forgot = false
        onSuccess()
    }
}

/** Neue PIN: zweimal eintippen. Stimmen beide nicht überein, schütteln die Punkte und es geht von vorn los. */
@Composable
fun PinSetupDialog(p: Profile, onDismiss: () -> Unit, onSet: (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    PinScreen(
        profile = p,
        title = if (first == null) tr("Neue PIN", "New PIN") else tr("PIN bestätigen", "Confirm PIN"),
        hint = if (first == null) tr("Vier Ziffern für „${p.name}“.", "Four digits for “${p.name}”.") else tr("Noch einmal, zur Sicherheit.", "Once more, to be sure."),
        onDismiss = onDismiss,
        onForgot = null,
        check = { pin ->
            val f = first
            when {
                f == null -> { first = pin; null }   // weiter zur Bestätigung, kein Schütteln
                f == pin -> true
                else -> { first = null; false }
            }
        },
        onDone = { pin -> onSet(pin) },
    )
}

/**
 * Gemeinsamer PIN-Bildschirm. [check]: true = richtig (→ [onDone]), false = falsch (Schütteln),
 * null = Eingabe angenommen, aber noch nicht fertig (erste Eingabe beim Festlegen).
 */
@Composable
private fun PinScreen(
    profile: Profile,
    title: String,
    hint: String,
    onDismiss: () -> Unit,
    onForgot: (() -> Unit)?,
    check: (String) -> Boolean?,
    onDone: (String) -> Unit,
) {
    val app = App.instance
    var pin by remember { mutableStateOf("") }
    val shake = remember { Animatable(0f) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var wait by remember { mutableStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Zu viele Fehlversuche: Sekunden herunterzählen, so lange keine Eingabe.
    LaunchedEffect(wait > 0) {
        while (true) {
            val left = ((app.profiles.lockedUntil - android.os.SystemClock.elapsedRealtime()) / 1000).toInt() + 1
            wait = if (app.profiles.lockedUntil > android.os.SystemClock.elapsedRealtime()) left else 0
            if (wait == 0) break
            kotlinx.coroutines.delay(250)
        }
    }

    fun type(d: Char) {
        if (pin.length >= 4 || wait > 0) return
        pin += d
        if (pin.length < 4) return
        val entered = pin
        when (check(entered)) {
            true -> onDone(entered)
            null -> pin = ""
            false -> scope.launch {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Reject)
                // Schütteln: hin und her, abklingend – eine Feder, die „nein“ sagt.
                shake.animateTo(0f, androidx.compose.animation.core.keyframes {
                    durationMillis = 420
                    -18f at 60; 14f at 130; -10f at 200; 6f at 270; -3f at 340
                })
                pin = ""
                if (app.profiles.lockedUntil > android.os.SystemClock.elapsedRealtime()) wait = 1
            }
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Palette.bg)) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ProfileAvatar(profile, 72.dp)
                VSpace(18.dp)
                Text(tr("PROFILSPERRE", "PROFILE LOCK"), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
                VSpace(6.dp)
                Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                VSpace(6.dp)
                Text(
                    if (wait > 0) tr("Zu viele Versuche – noch $wait s.", "Too many attempts – wait $wait s.") else hint,
                    style = MaterialTheme.typography.bodyMedium, color = if (wait > 0) Palette.wine else Palette.muted, textAlign = TextAlign.Center,
                )
                VSpace(28.dp)
                Row(Modifier.graphicsLayer { translationX = shake.value * density }, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    repeat(4) { i ->
                        val filled = i < pin.length
                        val s by animateFloatAsState(if (filled) 1f else 0.82f, tween(160, easing = Bouncy), label = "dot")
                        Box(
                            Modifier.size(18.dp).graphicsLayer { scaleX = s; scaleY = s }.clip(CircleShape)
                                .background(if (filled) Palette.text else Color.Transparent)
                                .border(1.5.dp, if (filled) Palette.text else Palette.faint, CircleShape),
                        )
                    }
                }
                VSpace(36.dp)
                val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "<")
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    keys.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            row.forEach { k -> PinKey(k, enabled = wait == 0) { if (k == "<") pin = pin.dropLast(1) else type(k[0]) } }
                        }
                    }
                }
                VSpace(24.dp)
                Row {
                    QuietButton(tr("Abbrechen", "Cancel"), color = Palette.muted, onClick = onDismiss)
                    if (onForgot != null) QuietButton(tr("PIN vergessen?", "Forgot PIN?"), color = Palette.text, onClick = onForgot)
                }
            }
        }
    }
}

@Composable
private fun PinKey(k: String, enabled: Boolean, onClick: () -> Unit) {
    if (k.isEmpty()) { Box(Modifier.size(72.dp)); return }
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, tween(120), label = "key")
    Box(
        Modifier.size(72.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape)
            .background(if (k == "<") Color.Transparent else Palette.surface2)
            .clickable(enabled = enabled, interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (k == "<") GIcon(Ic.back, tint = Palette.text, size = 22.dp)
        else Text(k, style = MaterialTheme.typography.headlineSmall, color = if (enabled) Palette.text else Palette.faint)
    }
}

/**
 * PIN vergessen: nur der Kontoinhaber darf die Sperre aufheben. Ohne Konto gibt es niemanden, der
 * das bestätigen könnte – dann bleibt nur, die App-Daten zu löschen; das steht ehrlich da.
 */
@Composable
private fun ForgotPinDialog(p: Profile, onDismiss: () -> Unit, onConfirmed: () -> Unit) {
    val app = App.instance
    val user by app.cloud.user.collectAsStateWithLifecycle()
    val activity = androidx.activity.compose.LocalActivity.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val u = user

    fun confirm() {
        if (busy || activity == null) return
        busy = true; error = null
        scope.launch {
            runCatching { app.cloud.confirmOwner(activity, password) }
                .onSuccess { busy = false; onConfirmed() }
                .onFailure { busy = false; error = Cloud.message(it) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("PIN vergessen?", "Forgot your PIN?")) },
        text = {
            Column {
                when {
                    u == null -> Text(tr(
                        "Ohne Konto kann niemand bestätigen, dass du das darfst. Die Sperre lässt sich dann nur aufheben, indem du die App-Daten löschst – dabei geht alles auf diesem Gerät verloren.",
                        "Without an account nobody can confirm you're allowed to do this. The lock can then only be removed by clearing the app's data – which erases everything on this device.",
                    ))
                    u.google -> Text(tr(
                        "Bestätige mit deinem Google-Konto, dann ist „${p.name}“ wieder offen.",
                        "Confirm with your Google account to unlock “${p.name}”.",
                    ))
                    else -> {
                        Text(tr(
                            "Gib das Passwort deines Kontos (${u.email}) ein, dann ist „${p.name}“ wieder offen.",
                            "Enter your account password (${u.email}) to unlock “${p.name}”.",
                        ))
                        VSpace(12.dp)
                        IvoryField(password, { password = it; error = null }, placeholder = tr("Passwort", "Password"), password = true)
                    }
                }
                error?.let { VSpace(8.dp); Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.wine) }
            }
        },
        confirmButton = {
            if (u != null) QuietButton(
                when { busy -> tr("Einen Moment …", "One moment …"); u.google -> tr("Mit Google bestätigen", "Confirm with Google"); else -> tr("Sperre aufheben", "Remove lock") },
                color = Palette.text,
            ) { if (u.google || password.isNotEmpty()) confirm() }
        },
        dismissButton = { QuietButton(if (u == null) tr("Verstanden", "Got it") else tr("Abbrechen", "Cancel"), color = Palette.muted, onClick = onDismiss) },
        containerColor = Palette.bg,
        shape = RoundedCornerShape(28.dp),
    )
}
