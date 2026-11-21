package de.marne.streamplayer.ios

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import de.marne.streamplayer.account.Profile
import de.marne.streamplayer.library.LibraryHost
import de.marne.streamplayer.ui.Lang
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.IvoryInkTheme
import de.marne.streamplayer.ui.design.ListGroup
import de.marne.streamplayer.ui.design.ListRow
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.PrimaryButton
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.RowDivider
import de.marne.streamplayer.ui.design.SecondaryButton
import de.marne.streamplayer.ui.design.SheetHandle
import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import platform.UIKit.UIViewController

/** Einstieg für Swift: die ganze Oberfläche der iPad-App. */
fun MainViewController(): UIViewController = ComposeUIViewController { IpadApp() }

private fun appVersion(): String {
    val info = NSBundle.mainBundle.infoDictionary
    return "${info?.get("CFBundleShortVersionString") ?: "?"} (${info?.get("CFBundleVersion") ?: "?"})"
}

@Composable
fun IpadApp() {
    remember { Lang.current = Lang.system() ?: Lang.Code.DE }
    IvoryInkTheme(isSystemInDarkTheme()) {
        val auth = remember { FirebaseAuth().also { if (Config.accountAvailable) it.restore() } }
        val user by auth.user.collectAsState()
        var demo by remember { mutableStateOf(false) }
        var settings by remember { mutableStateOf(false) }

        val screen = when {
            demo -> "demo"
            user != null -> "cloud:${user!!.uid}"
            else -> "signin"
        }
        AnimatedContent(screen, transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(200)) }, label = "root") { s ->
            when {
                s == "demo" -> DemoLibrary(onProfiles = { settings = true }, onSettings = { settings = true })
                s.startsWith("cloud:") -> CloudRoot(auth, s.removePrefix("cloud:"), onSettings = { settings = true })
                else -> SignIn(auth, onDemo = { demo = true })
            }
        }
        if (settings) SettingsSheet(
            account = user?.let { it.email ?: it.name ?: it.uid },
            demo = demo,
            onSignOut = { settings = false; if (demo) demo = false else auth.signOut() },
            onDismiss = { settings = false },
        )
    }
}

@Composable
private fun SignIn(auth: FirebaseAuth, onDemo: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().background(Palette.bg).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.widthIn(max = 460.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("STREAMPLAYER", style = MaterialTheme.typography.labelSmall, color = Palette.brass)
            Spacer(Modifier.height(10.dp))
            Text(tr("Deine Mediathek.", "Your library."), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                tr(
                    "Melde dich mit demselben Google-Konto an wie in der App auf dem Handy – Bibliothek, Profile und Fortschritt kommen mit.",
                    "Sign in with the same Google account as in the app on your phone – library, profiles and progress come along.",
                ),
                style = MaterialTheme.typography.bodyLarge, color = Palette.muted, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            if (Config.accountAvailable) {
                PrimaryButton(
                    if (busy) tr("Anmelden …", "Signing in …") else tr("Mit Google anmelden", "Sign in with Google"),
                    Modifier.fillMaxWidth(), enabled = !busy, icon = Ic.account,
                ) {
                    busy = true; error = null
                    scope.launch {
                        try { auth.signInWithGoogle() } catch (e: GoogleSignIn.Cancelled) { } catch (e: Exception) { error = e.message }
                        busy = false
                    }
                }
                Spacer(Modifier.height(12.dp))
            } else {
                Text(
                    tr("Das Konto ist in dieser Version nicht eingerichtet.", "Accounts aren't set up in this version."),
                    style = MaterialTheme.typography.bodyMedium, color = Palette.wine, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
            }
            SecondaryButton(tr("Mit Beispieltiteln ansehen", "Look around with sample titles"), Modifier.fillMaxWidth(), onClick = onDemo)
            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.wine, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun CloudRoot(auth: FirebaseAuth, uid: String, onSettings: () -> Unit) {
    val library = remember { CloudLibrary(Firestore(auth)) }
    var data by remember { mutableStateOf<CloudData?>(null) }
    var step by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var profileId by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        if (step != null) return
        step = tr("Verbinden", "Connecting"); error = null
        scope.launch {
            try {
                data = library.load(uid) { step = it }
            } catch (e: CloudException) {
                if (e.status == 400 || e.status == 401) { library.forget(uid); auth.signOut() } else error = e.message
            } catch (e: Exception) {
                error = e.message ?: tr("Laden fehlgeschlagen", "Loading failed")
            }
            step = null
        }
    }

    val edits = remember(uid) {
        CloudEdits(
            Firestore(auth), library, uid, scope,
            get = { data }, set = { data = it },
            onFailed = { msg ->
                notice = tr("Nicht gespeichert: $msg – lädt neu aus dem Konto.", "Not saved: $msg – reloading from your account.")
                refresh()
            },
        )
    }

    LaunchedEffect(uid) {
        data = library.cached(uid)
        refresh()
    }
    // Schlägt das Aktualisieren fehl, während die Bibliothek (aus dem Zwischenspeicher) schon da ist:
    // nur ein Hinweis, die Bibliothek bleibt.
    LaunchedEffect(error, data != null) {
        val e = error
        if (e != null && data != null) {
            notice = tr("Aktualisieren fehlgeschlagen: $e", "Refresh failed: $e")
            error = null
        }
    }

    val d = data
    when {
        d == null && error != null -> Message(
            tr("Bibliothek nicht geladen", "Couldn't load the library"), error!!,
            primary = tr("Erneut versuchen", "Try again") to { refresh() },
            secondary = tr("Abmelden", "Sign out") to { auth.signOut() },
        )
        d == null -> Loading(step ?: tr("Verbinden", "Connecting"))
        else -> {
            val profiles = d.profiles.ifEmpty { listOf(Profile(Profile.DEFAULT_ID, "Profil", 0, 0)) }
            val chosen = profiles.firstOrNull { it.id == profileId }
            val single = profiles.singleOrNull()?.takeIf { it.lock == null }
            val profile = chosen ?: single
            if (profile == null) {
                ProfileGate(profiles, onPick = { p -> profileId = p.id }, onSignOut = { auth.signOut() })
            } else {
                val pid = profile.id
                val host = remember(pid) { edits.host { pid } }
                LibraryShell(
                    data = ShellData(d.titles, d.libraries, profile, d.progress[profile.id].orEmpty()),
                    host = host,
                    // Titel umbenennen/entfernen bleibt auf dem Handy (dort liegen Katalog und Downloads).
                    edits = ShellEdits(
                        toggleList = { t -> edits.toggleList(pid, t.id) },
                        setWatched = { keys, w -> edits.setWatched(pid, keys, w) },
                        rename = null,
                        remove = null,
                        progress = { key, pos, dur, ended, played -> edits.recordProgress(pid, key, pos, dur, ended, played) },
                    ),
                    onProfiles = { if (profiles.size > 1 || profile.lock != null) profileId = null else onSettings() },
                    onSettings = onSettings,
                    notice = notice,
                    onNotice = { notice = it },
                )
            }
        }
    }
}

@Composable
private fun Loading(step: String) {
    Column(Modifier.fillMaxSize().background(Palette.bg), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = Palette.brass, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(18.dp))
        Text(tr("Deine Bibliothek wird geladen", "Loading your library"), style = MaterialTheme.typography.titleMedium)
        Text("$step …", style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
    }
}

@Composable
private fun Message(title: String, body: String, primary: Pair<String, () -> Unit>, secondary: Pair<String, () -> Unit>) {
    Column(Modifier.fillMaxSize().background(Palette.bg).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Column(Modifier.widthIn(max = 460.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            PrimaryButton(primary.first, Modifier.fillMaxWidth(), onClick = primary.second)
            Spacer(Modifier.height(8.dp))
            QuietButton(secondary.first, color = Palette.muted, onClick = secondary.second)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(account: String?, demo: Boolean, onSignOut: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.bg, contentColor = Palette.text, dragHandle = { SheetHandle() },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(tr("EINSTELLUNGEN", "SETTINGS"), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
            Text("Streamplayer", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            ListGroup {
                ListRow(
                    if (demo) tr("Beispieltitel", "Sample titles") else tr("Konto", "Account"),
                    icon = Ic.account,
                    subtitle = if (demo) tr("Nicht angemeldet", "Not signed in") else account,
                    showChevron = false,
                )
                RowDivider()
                ListRow(
                    if (demo) tr("Beispieltitel verlassen", "Leave sample titles") else tr("Abmelden", "Sign out"),
                    icon = Ic.logout, titleColor = Palette.wine, showChevron = false, onClick = onSignOut,
                )
                RowDivider()
                ListRow(tr("Version", "Version"), icon = Ic.info, value = appVersion(), showChevron = false)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
