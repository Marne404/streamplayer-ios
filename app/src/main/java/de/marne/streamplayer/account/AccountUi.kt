package de.marne.streamplayer.account

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marne.streamplayer.App
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.IvoryField
import de.marne.streamplayer.ui.design.ListGroup
import de.marne.streamplayer.ui.design.ListRow
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.PrimaryButton
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.RowDivider
import de.marne.streamplayer.ui.design.SecondaryButton
import de.marne.streamplayer.ui.design.SheetHandle
import de.marne.streamplayer.ui.design.StatusLine
import de.marne.streamplayer.ui.design.VSpace
import kotlinx.coroutines.launch

/** Abschnitt „Konto“ in den Einstellungen. */
@Composable
fun AccountSection(onProfiles: () -> Unit, onToast: (String) -> Unit) {
    val app = App.instance
    val user by app.cloud.user.collectAsStateWithLifecycle()
    val status by app.sync.status.collectAsStateWithLifecycle()
    val profiles by app.profiles.profiles.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf(false) }
    var confirmOut by remember { mutableStateOf(false) }

    Column(Modifier.animateContentSize()) {
        val u = user
        when {
            !app.cloud.available -> ListGroup {
                ListRow("Konto", icon = Ic.cloudOff, subtitle = "In dieser Version nicht eingerichtet.")
            }
            u == null -> {
                Text(
                    "Mit einem Konto sind Bibliothek, Profile und Fortschritt auf allen deinen Geräten gleich – und nach einer Neuinstallation wieder da.",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
                )
                VSpace(14.dp)
                PrimaryButton("Anmelden", icon = Ic.account) { sheet = true }
            }
            else -> ListGroup {
                ListRow(
                    title = u.email ?: u.name ?: "Angemeldet",
                    subtitle = if (u.google) "Google-Konto" else "E-Mail-Konto",
                    icon = Ic.account,
                )
                RowDivider()
                val (icon, text) = when (val s = status) {
                    CloudSync.Status.Off -> Ic.cloudOff to "Abgleich aus"
                    CloudSync.Status.Working -> Ic.cloudSync to "Gleicht ab …"
                    is CloudSync.Status.Done -> Ic.cloudDone to "Abgeglichen ${ago(s.at)}"
                    is CloudSync.Status.Error -> Ic.cloudOff to s.message
                }
                ListRow(
                    title = text,
                    icon = icon,
                    titleColor = if (status is CloudSync.Status.Error) Palette.wine else Palette.text,
                    titleMaxLines = 2,
                    value = "Jetzt",
                    showChevron = false,
                    onClick = { app.sync.syncNow() },
                )
                RowDivider()
                ListRow("Abmelden", icon = Ic.logout, showChevron = false, onClick = { confirmOut = true })
            }
        }
        VSpace(12.dp)
        ListGroup {
            ListRow(
                title = "Profile",
                subtitle = "Jedes Profil hat seinen eigenen Fortschritt.",
                icon = Ic.group,
                value = profiles.size.toString(),
                onClick = onProfiles,
            )
        }
    }

    if (sheet) SignInSheet(onDone = { sheet = false; it?.let(onToast) })
    if (confirmOut) {
        AlertDialog(
            onDismissRequest = { confirmOut = false },
            title = { Text("Abmelden?") },
            text = { Text("Bibliothek und Fortschritt bleiben auf diesem Gerät. Was du ab jetzt änderst, wird nicht mehr abgeglichen.") },
            confirmButton = { QuietButton("Abmelden") { confirmOut = false; app.cloud.signOut(); onToast("Abgemeldet.") } },
            dismissButton = { QuietButton("Abbrechen", color = Palette.muted) { confirmOut = false } },
            containerColor = Palette.bg,
            shape = RoundedCornerShape(28.dp),
        )
    }
}

private fun ago(at: Long): String {
    val s = (System.currentTimeMillis() - at) / 1000
    return when {
        s < 60 -> "gerade eben"
        s < 3600 -> "vor ${s / 60} Min."
        s < 86_400 -> "vor ${s / 3600} Std."
        else -> "vor ${s / 86_400} Tagen"
    }
}

/** Anmelden oder Konto anlegen: Google oder E-Mail. [onDone] mit Meldung für die Snackbar. */
@Composable
private fun SignInSheet(onDone: (String?) -> Unit) {
    val app = App.instance
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var register by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> Unit, success: String?) {
        if (busy) return
        busy = true; error = null; info = null
        scope.launch {
            runCatching { block() }
                .onSuccess { busy = false; if (success != null) onDone(success) }
                .onFailure { busy = false; error = Cloud.message(it) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { onDone(null) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.bg,
        contentColor = Palette.text,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
        tonalElevation = 0.dp,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp).navigationBarsPadding().imePadding().animateContentSize(),
        ) {
            Text("KONTO", style = MaterialTheme.typography.labelSmall, color = Palette.muted)
            VSpace(6.dp)
            AnimatedContent(register, label = "mode") { r ->
                Text(if (r) "Konto anlegen." else "Willkommen zurück.", style = MaterialTheme.typography.headlineSmall)
            }
            VSpace(20.dp)
            if (app.cloud.googleAvailable && activity != null) {
                SecondaryButton("Mit Google ${if (register) "anlegen" else "anmelden"}", icon = Ic.account, enabled = !busy) {
                    run({ app.cloud.signInWithGoogle(activity) }, "Angemeldet. Bibliothek wird abgeglichen.")
                }
                VSpace(16.dp)
                Text("oder mit E-Mail", style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.align(Alignment.CenterHorizontally))
                VSpace(12.dp)
            }
            IvoryField(email, { email = it; error = null }, placeholder = "E-Mail", type = KeyboardType.Email)
            VSpace(10.dp)
            IvoryField(password, { password = it; error = null }, placeholder = if (register) "Passwort (mind. 6 Zeichen)" else "Passwort", password = true)
            error?.let { VSpace(10.dp); StatusLine(Ic.error, it) }
            info?.let { VSpace(10.dp); StatusLine(Ic.mail, it, Palette.muted) }
            VSpace(18.dp)
            val ready = email.isNotBlank() && password.length >= (if (register) 6 else 1) && !busy
            PrimaryButton(
                when { busy -> "Einen Moment …"; register -> "Konto anlegen"; else -> "Anmelden" },
                enabled = ready,
            ) {
                if (register) run({ app.cloud.register(email, password) }, "Konto angelegt. Bibliothek wird abgeglichen.")
                else run({ app.cloud.signIn(email, password) }, "Angemeldet. Bibliothek wird abgeglichen.")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                QuietButton(if (register) "Ich habe schon ein Konto" else "Neues Konto anlegen", color = Palette.text) {
                    register = !register; error = null; info = null
                }
                if (!register) QuietButton("Passwort vergessen?", color = Palette.muted) {
                    if (email.isBlank()) error = "Erst die E-Mail-Adresse eintragen."
                    else run({ app.cloud.resetPassword(email); info = "Mail zum Zurücksetzen ist unterwegs." }, null)
                }
            }
            if (app.cloud.emulator) {
                VSpace(4.dp)
                Text("Testbetrieb: lokaler Firebase-Emulator.", style = MaterialTheme.typography.bodySmall, color = Palette.faint)
            }
        }
    }
}
