package de.marne.streamplayer.admin

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.ui.Lang
import de.marne.streamplayer.ui.design.Hairline
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.IvoryField
import de.marne.streamplayer.ui.design.IvoryInkTheme
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.PrimaryButton
import de.marne.streamplayer.ui.design.ScreenHeader
import de.marne.streamplayer.ui.design.StatusLine
import de.marne.streamplayer.ui.design.VSpace
import de.marne.streamplayer.ui.design.isAppDark
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Die Bausteine aus :core sprechen zweisprachig (tr); diese App ist nur Deutsch.
        Lang.current = Lang.Code.DE
        Api.init(applicationContext)
        de.marne.streamplayer.update.Updater.configure(BuildConfig.UPDATE_URL.ifEmpty { null }?.let { "$it/admin/version.json" }.orEmpty())
        de.marne.streamplayer.update.Updater.check(this)
        setContent {
            val dark = isAppDark("system")
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            IvoryInkTheme(dark) {
                Box(Modifier.fillMaxSize().background(Palette.bg)) {
                    Crossfade(Api.token.isEmpty(), animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), label = "login") { signedOut ->
                        if (signedOut) LoginScreen() else AdminRoot()
                    }
                    // Statusleiste deckend, damit gescrollter Inhalt nicht unter der Uhr steht.
                    Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Palette.bg))
                    de.marne.streamplayer.update.UpdateOffer("Streamplayer Admin")
                }
            }
        }
    }
}

private class TabSpec(val label: String, val icon: Int)

private val tabs = listOf(
    TabSpec("Überblick", Ic.home),
    TabSpec("Nutzer", Ic.group),
    TabSpec("Probleme", Ic.error),
)

@Composable
private fun AdminRoot() {
    val scope = rememberCoroutineScope()
    // Die Loader leben hier, nicht in den Tabs: Zurückwechseln zeigt sofort die letzten Zahlen.
    val overview = remember { Loader(scope) { parseOverview(Api.call("overview")) } }
    val users = remember { Loader(scope) { parseUsers(Api.call("users")) } }
    val problems = remember { Loader(scope) { parseProblems(Api.call("problems")) } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    // Zurück in die App: veraltete Zahlen neu holen (jeder Reiter einzeln, nur wenn alt).
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        listOf(overview, users, problems).forEach { it.ensure() }
        onPauseOrDispose { }
    }

    BackHandler(enabled = open != null) { open = null }
    BackHandler(enabled = open == null && tab != 0) { tab = 0 }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            Crossfade(open ?: tab, animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), label = "screen") { s ->
                when (s) {
                    is String -> UserDetailScreen(s, onBack = { open = null })
                    1 -> UsersScreen(users, onOpen = { open = it })
                    2 -> ProblemsScreen(problems)
                    else -> OverviewScreen(overview)
                }
            }
        }
        if (open == null) FloatingNav(tab) { tab = it }
    }
}

/**
 * Schwebende Leiste wie im Player: drei Ziele in einer Kapsel, eine Markierung gleitet unter das
 * gewählte, die Beschriftung steht nur dort.
 */
@Composable
private fun FloatingNav(selected: Int, onSelect: (Int) -> Unit) {
    val bouncy = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
    val shape = RoundedCornerShape(30.dp)
    Box(Modifier.fillMaxWidth().background(Palette.bg).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(60.dp)
                .shadow(14.dp, shape, ambientColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.12f), spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.12f))
                .clip(shape).background(Palette.surface).border(1.dp, Palette.line, shape),
        ) {
            val w = maxWidth / tabs.size
            val x by animateDpAsState(w * selected, tween(420, easing = bouncy), label = "navPill")
            Box(Modifier.padding(6.dp).width(w - 12.dp).fillMaxSize().offset(x = x).clip(RoundedCornerShape(24.dp)).background(Palette.surface3))
            Row(Modifier.fillMaxSize()) {
                tabs.forEachIndexed { i, t ->
                    val isSel = i == selected
                    val tint by animateColorAsState(if (isSel) Palette.text else Palette.muted, tween(220), label = "navTint")
                    Row(
                        Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(24.dp))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(t.icon), contentDescription = t.label, tint = tint, modifier = Modifier.size(22.dp))
                        AnimatedVisibility(
                            visible = isSel,
                            enter = fadeIn(tween(220)) + expandHorizontally(tween(320, easing = bouncy)),
                            exit = fadeOut(tween(160)) + shrinkHorizontally(tween(220)),
                        ) {
                            Text(t.label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Anmeldung, wenn kein Schlüssel eingebaut ist oder der Server ihn ablehnt. Geprüft wird vor dem
 * Speichern, damit ein Tippfehler nicht erst auf der nächsten Seite auffällt.
 */
@Composable
private fun LoginScreen() {
    val scope = rememberCoroutineScope()
    var key by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(
        Modifier.fillMaxSize().background(Palette.bg).imePadding().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = top + 16.dp, bottom = 32.dp),
    ) {
        ScreenHeader(
            title = "Admin-Schlüssel",
            eyebrow = "Streamplayer · Nutzung",
            subtitle = "Er steht auf dem Rechner in local.properties als stats.adminToken.",
        )
        VSpace(32.dp)
        IvoryField(key, { key = it; error = null }, placeholder = "Schlüssel", password = true)
        if (error != null) {
            VSpace(10.dp)
            StatusLine(Ic.error, error!!)
        }
        VSpace(20.dp)
        PrimaryButton(if (busy) "Prüfe …" else "Anmelden", enabled = key.isNotBlank() && !busy) {
            busy = true
            scope.launch {
                val reason = Api.verify(key)
                busy = false
                if (reason == null) Api.signIn(key) else error = reason
            }
        }
        VSpace(28.dp)
        Hairline()
        VSpace(16.dp)
        Text(
            "Die Statistik enthält nur Zahlen: keine Titel, keine Seitenadressen, keine Suchbegriffe. " +
                "Bei erfolgloser Video-Suche nur den Namen der Seite.",
            style = MaterialTheme.typography.bodySmall, color = Palette.muted,
        )
    }
}
