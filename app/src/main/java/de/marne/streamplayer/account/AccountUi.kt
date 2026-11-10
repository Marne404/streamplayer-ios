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
import de.marne.streamplayer.ui.tr
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

/** Gruppe „Konto“ in den Einstellungen. Abgemeldet eine Zeile statt Absatz und großem Knopf. */
@Composable
fun AccountSection(onToast: (String) -> Unit) {
    val app = App.instance
    val user by app.cloud.user.collectAsStateWithLifecycle()
    val status by app.sync.status.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf(false) }
    var confirmOut by remember { mutableStateOf(false) }

    Column(Modifier.animateContentSize()) {
        val u = user
        when {
            !app.cloud.available -> ListGroup {
                ListRow(tr("Konto", "Account"), icon = Ic.cloudOff, subtitle = tr("In dieser Version nicht eingerichtet.", "Not set up in this version."))
            }
            u == null -> ListGroup {
                ListRow(
                    title = tr("Anmelden", "Sign in"),
                    subtitle = tr("Bibliothek, Profile und Fortschritt auf allen Geräten gleich.", "Same library, profiles and progress on all your devices."),
                    icon = Ic.account,
                    onClick = { sheet = true },
                )
            }
            else -> ListGroup {
                ListRow(
                    title = u.email ?: u.name ?: tr("Angemeldet", "Signed in"),
                    subtitle = if (u.google) tr("Google-Konto", "Google account") else tr("E-Mail-Konto", "Email account"),
                    icon = Ic.account,
                )
                RowDivider()
                val (icon, text) = when (val s = status) {
                    CloudSync.Status.Off -> Ic.cloudOff to tr("Abgleich aus", "Sync off")
                    CloudSync.Status.Working -> Ic.cloudSync to tr("Gleicht ab …", "Syncing …")
                    is CloudSync.Status.Done -> Ic.cloudDone to tr("Abgeglichen ${ago(s.at)}", "Synced ${ago(s.at)}")
                    is CloudSync.Status.Error -> Ic.cloudOff to s.message
                }
                ListRow(
                    title = text,
                    icon = icon,
                    titleColor = if (status is CloudSync.Status.Error) Palette.wine else Palette.text,
                    titleMaxLines = 2,
                    value = tr("Jetzt", "Now"),
                    showChevron = false,
                    onClick = { app.sync.syncNow() },
                )
                RowDivider()
                ListRow(tr("Abmelden", "Sign out"), icon = Ic.logout, titleColor = Palette.wine, showChevron = false, onClick = { confirmOut = true })
            }
        }
    }

    if (sheet) SignInSheet(onDone = { sheet = false; it?.let(onToast) })
    if (confirmOut) {
        AlertDialog(
            onDismissRequest = { confirmOut = false },
            title = { Text(tr("Abmelden?", "Sign out?")) },
            text = { Text(tr("Bibliothek und Fortschritt bleiben auf diesem Gerät. Was du ab jetzt änderst, wird nicht mehr abgeglichen.", "Your library and progress stay on this device. Changes from now on won't be synced.")) },
            confirmButton = { QuietButton(tr("Abmelden", "Sign out")) { confirmOut = false; app.cloud.signOut(); onToast(tr("Abgemeldet.", "Signed out.")) } },
            dismissButton = { QuietButton(tr("Abbrechen", "Cancel"), color = Palette.muted) { confirmOut = false } },
            containerColor = Palette.bg,
            shape = RoundedCornerShape(28.dp),
        )
    }
}

private fun ago(at: Long): String {
    val s = (System.currentTimeMillis() - at) / 1000
    return when {
        s < 60 -> tr("gerade eben", "just now")
        s < 3600 -> tr("vor ${s / 60} Min.", "${s / 60} min ago")
        s < 86_400 -> tr("vor ${s / 3600} Std.", "${s / 3600} h ago")
        s < 2 * 86_400 -> tr("vor ${s / 86_400} Tagen", "1 day ago")
        else -> tr("vor ${s / 86_400} Tagen", "${s / 86_400} days ago")
    }
}

/** Anmelden oder Konto anlegen als Blatt (Einstellungen). [onDone] mit Meldung für die Snackbar. */
@Composable
private fun SignInSheet(onDone: (String?) -> Unit) {
    var register by remember { mutableStateOf(false) }
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
            Text(tr("KONTO", "ACCOUNT"), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
            VSpace(6.dp)
            AnimatedContent(register, label = "mode") { r ->
                Text(if (r) tr("Konto anlegen.", "Create an account.") else tr("Willkommen zurück.", "Welcome back."), style = MaterialTheme.typography.headlineSmall)
            }
            VSpace(20.dp)
            SignInForm(register, { register = it }, onSignedIn = { onDone(it) })
        }
    }
}

/**
 * Google-Knopf und E-Mail-Formular. Im Blatt der Einstellungen und im Willkommens-Ablauf –
 * eine Fassung, damit beide sich gleich verhalten. [onSignedIn] mit einer Meldung.
 */
@Composable
fun SignInForm(register: Boolean, onRegister: (Boolean) -> Unit, onSignedIn: (String) -> Unit) {
    val app = App.instance
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
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
                .onSuccess { busy = false; if (success != null) onSignedIn(success) }
                .onFailure { busy = false; error = Cloud.message(it) }
        }
    }

    Column(Modifier.fillMaxWidth().animateContentSize()) {
        if (app.cloud.googleAvailable && activity != null) {
            SecondaryButton(if (register) tr("Mit Google anlegen", "Sign up with Google") else tr("Mit Google anmelden", "Sign in with Google"), icon = Ic.account, enabled = !busy) {
                run({ app.cloud.signInWithGoogle(activity) }, tr("Angemeldet. Bibliothek wird abgeglichen.", "Signed in. Syncing your library."))
            }
            VSpace(16.dp)
            Text(tr("oder mit E-Mail", "or with email"), style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.align(Alignment.CenterHorizontally))
            VSpace(12.dp)
        }
        IvoryField(email, { email = it; error = null }, placeholder = tr("E-Mail", "Email"), type = KeyboardType.Email)
        VSpace(10.dp)
        IvoryField(password, { password = it; error = null }, placeholder = if (register) tr("Passwort (mind. 6 Zeichen)", "Password (at least 6 characters)") else tr("Passwort", "Password"), password = true)
        error?.let { VSpace(10.dp); StatusLine(Ic.error, it) }
        info?.let { VSpace(10.dp); StatusLine(Ic.mail, it, Palette.muted) }
        VSpace(18.dp)
        val ready = email.isNotBlank() && password.length >= (if (register) 6 else 1) && !busy
        PrimaryButton(
            when { busy -> tr("Einen Moment …", "One moment …"); register -> tr("Konto anlegen", "Create account"); else -> tr("Anmelden", "Sign in") },
            enabled = ready,
        ) {
            if (register) run({ app.cloud.register(email, password) }, tr("Konto angelegt. Bibliothek wird abgeglichen.", "Account created. Syncing your library."))
            else run({ app.cloud.signIn(email, password) }, tr("Angemeldet. Bibliothek wird abgeglichen.", "Signed in. Syncing your library."))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            QuietButton(if (register) tr("Ich habe schon ein Konto", "I already have an account") else tr("Neues Konto anlegen", "Create a new account"), color = Palette.text) {
                onRegister(!register); error = null; info = null
            }
            if (!register) QuietButton(tr("Passwort vergessen?", "Forgot password?"), color = Palette.muted) {
                if (email.isBlank()) error = tr("Erst die E-Mail-Adresse eintragen.", "Enter your email address first.")
                else run({ app.cloud.resetPassword(email); info = tr("Mail zum Zurücksetzen ist unterwegs.", "Reset email is on its way.") }, null)
            }
        }
        if (app.cloud.emulator) {
            VSpace(4.dp)
            Text(tr("Testbetrieb: lokaler Firebase-Emulator.", "Test mode: local Firebase emulator."), style = MaterialTheme.typography.bodySmall, color = Palette.faint)
        }
    }
}
