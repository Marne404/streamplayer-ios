package de.marne.streamplayer.ios

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.marne.streamplayer.account.Profile
import de.marne.streamplayer.account.ProfileAvatar
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.Serif
import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

/** „Wer schaut?“ wie auf Android – mit Profilsperre (PIN, derselbe Hash wie auf dem Handy). */
@Composable
internal fun ProfileGate(profiles: List<Profile>, onPick: (Profile) -> Unit, onSignOut: () -> Unit) {
    var locked by remember { mutableStateOf<Profile?>(null) }
    AnimatedContent(locked, transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) }, label = "gate") { p ->
        if (p == null) Chooser(profiles, onPick = { if (it.lock != null) locked = it else onPick(it) }, onSignOut = onSignOut)
        else PinPad(p, onOk = { onPick(p) }, onCancel = { locked = null })
    }
}

@Composable
private fun Chooser(profiles: List<Profile>, onPick: (Profile) -> Unit, onSignOut: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.bg).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("STREAMPLAYER", style = MaterialTheme.typography.labelSmall, color = Palette.muted)
        Spacer(Modifier.height(10.dp))
        Text(tr("Wer schaut?", "Who's watching?"), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(40.dp))
        FlowRow(Modifier.widthIn(max = 760.dp), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            profiles.forEach { p ->
                Column(
                    Modifier.width(120.dp).clip(MaterialTheme.shapes.medium).clickable { onPick(p) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ProfileAvatar(p, 96.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    if (p.lock != null) Icon(painterResource(Ic.lock), null, tint = Palette.muted, modifier = Modifier.padding(top = 4.dp).size(14.dp))
                }
            }
        }
        Spacer(Modifier.height(48.dp))
        QuietButton(tr("Abmelden", "Sign out"), color = Palette.muted, onClick = onSignOut)
    }
}

@Composable
private fun PinPad(p: Profile, onOk: () -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pin) {
        if (pin.length < 4 || checking) return@LaunchedEffect
        checking = true
        val ok = withContext(Dispatchers.Default) { Crypto.pinMatches(p.lock!!, pin) }
        checking = false
        if (ok) onOk()
        else {
            wrong = true
            pin = ""
            scope.launch {
                for (x in listOf(18f, -14f, 10f, -6f, 0f)) shake.animateTo(x, tween(55))
            }
        }
    }

    fun type(d: String) { if (!checking && pin.length < 4) { wrong = false; pin += d } }

    Column(Modifier.fillMaxSize().background(Palette.bg).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        ProfileAvatar(p, 72.dp)
        Spacer(Modifier.height(16.dp))
        Text(tr("PROFILSPERRE", "PROFILE LOCK"), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
        Spacer(Modifier.height(6.dp))
        Text(tr("PIN eingeben", "Enter PIN"), style = MaterialTheme.typography.headlineMedium)
        Text(
            if (wrong) tr("Falsche PIN.", "Wrong PIN.") else tr("„${p.name}“ ist gesperrt.", "“${p.name}” is locked."),
            style = MaterialTheme.typography.bodyMedium, color = if (wrong) Palette.wine else Palette.muted,
        )
        Spacer(Modifier.height(24.dp))
        Row(Modifier.offset(x = shake.value.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(4) { i ->
                Box(Modifier.size(14.dp).clip(CircleShape).then(if (i < pin.length) Modifier.background(Palette.text) else Modifier.border(1.5.dp, Palette.faint, CircleShape)))
            }
        }
        Spacer(Modifier.height(32.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "<")
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    row.forEach { k ->
                        Box(
                            Modifier.size(76.dp).clip(CircleShape)
                                .then(if (k.isEmpty() || k == "<") Modifier else Modifier.background(Palette.surface2))
                                .then(if (k.isEmpty()) Modifier else Modifier.clickable { if (k == "<") pin = pin.dropLast(1) else type(k) }),
                            contentAlignment = Alignment.Center,
                        ) {
                            when (k) {
                                "" -> Unit
                                "<" -> Icon(painterResource(Ic.back), tr("Löschen", "Delete"), tint = Palette.text, modifier = Modifier.size(22.dp))
                                else -> Text(k, fontFamily = Serif, fontSize = 30.sp, color = Palette.text)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        QuietButton(tr("Abbrechen", "Cancel"), color = Palette.muted, onClick = onCancel)
    }
}
