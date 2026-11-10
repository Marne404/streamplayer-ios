package de.marne.streamplayer.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.marne.streamplayer.App
import de.marne.streamplayer.account.OnProfile
import de.marne.streamplayer.account.Profile
import de.marne.streamplayer.account.ProfileAvatar
import de.marne.streamplayer.account.ProfileColors
import de.marne.streamplayer.account.SignInForm
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.IconBadge
import de.marne.streamplayer.ui.design.IvoryField
import de.marne.streamplayer.ui.design.IvorySwitch
import de.marne.streamplayer.ui.design.ListGroup
import de.marne.streamplayer.ui.design.ListRow
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.PrimaryButton
import de.marne.streamplayer.ui.design.RowDivider
import de.marne.streamplayer.ui.design.Segmented
import de.marne.streamplayer.ui.design.VSpace

// Die zwei Kurven der Bewegungssprache: Bouncy kommt an, Spring räumt weg und ändert Größen.
private val Spring = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
private val Bouncy = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

private const val STEPS = 6
private const val STEP_ACCOUNT = 2
private const val STEP_PROFILE = 3

/**
 * Willkommen beim ersten Start: worum es geht, wie es funktioniert, Konto, Profil,
 * Einstellungen, fertig. Anmelden ist freiwillig – ohne Konto läuft alles auf dem Gerät.
 * Das Konto kommt *vor* dem Profil: wer schon eines hat, bringt seine Profile mit und wählt
 * hier nur noch aus, statt ein zweites „Ich“ anzulegen.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val app = App.instance
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val profiles by app.profiles.profiles.collectAsStateWithLifecycle()
    val activeId by app.profiles.activeId.collectAsStateWithLifecycle()
    val user by app.cloud.user.collectAsStateWithLifecycle()
    val active = profiles.firstOrNull { it.id == activeId } ?: profiles.first()

    var step by rememberSaveable { mutableIntStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    // Entwurf fürs Profil. Das unberührte Standardprofil („Ich“) startet leer, damit man seinen Namen tippt.
    var name by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableIntStateOf(0) }
    var notify by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(active.id, active.name, active.color) {
        name = if (active.id == Profile.DEFAULT_ID && active.name == "Ich") "" else active.name
        color = active.color
    }

    fun finish() {
        app.profiles.update(active.id, name, color)
        app.profiles.chosen()
        app.settings.setOnboarded(true)
        onFinished()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }

    fun go(delta: Int) {
        forward = delta > 0
        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        step = (step + delta).coerceIn(0, STEPS - 1)
    }

    fun next() {
        if (step == STEP_PROFILE) app.profiles.update(active.id, name, color)
        if (step < STEPS - 1) return go(1)
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        val ask = notify && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (ask) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else finish()
    }

    BackHandler(enabled = step > 0) { go(-1) }

    Column(Modifier.fillMaxSize().background(Palette.bg).statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Palette.surface2).clickable { go(-1) }, contentAlignment = Alignment.Center) {
                    GIcon(Ic.back, tint = Palette.text, size = 22.dp)
                }
                StepDots(step, Modifier.weight(1f))
                Box(Modifier.size(44.dp))
            }
        }
        AnimatedContent(
            targetState = step,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                // Vorwärts kommt der nächste Schritt von rechts, zurück von links – die Richtung erzählt.
                val dir = if (forward) 1 else -1
                (slideInHorizontally(tween(420, easing = Bouncy)) { it / 3 * dir } + fadeIn(tween(320, easing = Spring))) togetherWith
                    (slideOutHorizontally(tween(320, easing = Spring)) { -it / 3 * dir } + fadeOut(tween(220, easing = Spring)))
            },
            label = "onboarding",
        ) { s ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (s) {
                    0 -> WelcomeStep()
                    1 -> TourStep()
                    STEP_ACCOUNT -> AccountStep()
                    STEP_PROFILE -> ProfileStep(profiles, active, name, { name = it.take(24) }, color) {
                        color = it
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                    4 -> PrefsStep(notify) { notify = it }
                    else -> ReadyStep(active.copy(name = name.trim().ifEmpty { "Ich" }, color = color), user?.email ?: user?.name)
                }
            }
        }
        // Bei offener Tastatur weg: sonst schiebt er sich unter das E-Mail-Feld und verdeckt das Passwort.
        androidx.compose.animation.AnimatedVisibility(
            visible = !WindowInsets.isImeVisible,
            enter = fadeIn(tween(220, easing = Spring)) + androidx.compose.animation.expandVertically(tween(320, easing = Spring)),
            exit = fadeOut(tween(160, easing = Spring)) + androidx.compose.animation.shrinkVertically(tween(220, easing = Spring)),
        ) {
        PrimaryButton(
            when (step) {
                0 -> "Los"
                STEP_ACCOUNT -> if (user == null) "Ohne Konto weiter" else "Weiter"
                STEPS - 1 -> "Zur Bibliothek"
                else -> "Weiter"
            },
            enabled = step != STEP_PROFILE || name.isNotBlank(),
            modifier = Modifier.padding(vertical = 16.dp),
            onClick = ::next,
        )
        }
    }
}

/** Fortschritt als Punkte; der aktuelle wird zur Pille. */
@Composable
private fun StepDots(step: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        for (i in 1 until STEPS) {
            val w by animateDpAsState(if (i == step) 26.dp else 8.dp, tween(320, easing = Bouncy), label = "dot")
            Box(Modifier.height(8.dp).width(w).clip(CircleShape).background(if (i <= step) Palette.text else Palette.surface3))
        }
    }
}

@Composable
private fun StepTitle(eyebrow: String, title: String, subtitle: String) {
    VSpace(20.dp)
    Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
    VSpace(8.dp)
    Text(title, style = MaterialTheme.typography.headlineLarge)
    VSpace(10.dp)
    Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Palette.muted)
    VSpace(28.dp)
}

/** Erscheinen in Leserichtung: 150 ms Vorlauf, dann je Element 30 ms später. */
@Composable
private fun rememberStagger(count: Int): (Int) -> Modifier {
    val total = 150 + count * 30 + 420
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(total, easing = LinearEasing)) }
    val lift = with(LocalDensity.current) { 24.dp.toPx() }
    return { i ->
        Modifier.graphicsLayer {
            val t = ((a.value * total - 150 - i * 30) / 420f).coerceIn(0f, 1f)
            translationY = (1f - Bouncy.transform(t)) * lift
            alpha = Spring.transform(t)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Schritte

@Composable
private fun WelcomeStep() {
    val enter = rememberStagger(3)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        VSpace(40.dp)
        PosterFan()
        VSpace(44.dp)
        Text("STREAMPLAYER", style = MaterialTheme.typography.labelSmall, color = Palette.brass, modifier = enter(0))
        VSpace(10.dp)
        Text("Deine Mediathek.", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center, modifier = enter(1))
        VSpace(14.dp)
        Text(
            "Filme und Serien von den Seiten, auf denen sie laufen – geordnet wie bei Netflix, abgespielt im eigenen Player.",
            style = MaterialTheme.typography.bodyLarge, color = Palette.muted, textAlign = TextAlign.Center, modifier = enter(2),
        )
    }
}

/**
 * Drei Poster fächern sich auf und schweben dann ganz leicht. Rein dekorativ, aber es sagt
 * in einer Sekunde, worum es geht: Filme, Serien, abspielen.
 */
@Composable
private fun PosterFan() {
    val open = remember { Animatable(0f) }
    LaunchedEffect(Unit) { open.animateTo(1f, tween(900, easing = LinearEasing)) }
    val drift by rememberInfiniteTransition(label = "drift").animateFloat(
        -1f, 1f, infiniteRepeatable(tween(4200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "d",
    )
    val density = LocalDensity.current
    data class Card(@DrawableRes val icon: Int, val color: Color, val side: Int)
    val cards = listOf(Card(Ic.tv, ProfileColors[3], -1), Card(Ic.movie, ProfileColors[2], 1), Card(Ic.play, ProfileColors[0], 0))
    Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
        cards.forEachIndexed { i, c ->
            Box(
                Modifier
                    .size(width = 136.dp, height = 204.dp)
                    .graphicsLayer {
                        val t = ((open.value * 900 - i * 60) / 700f).coerceIn(0f, 1f)
                        val e = Bouncy.transform(t)
                        rotationZ = 11f * c.side * e
                        translationX = with(density) { 84.dp.toPx() } * c.side * e
                        translationY = with(density) { (if (c.side == 0) -10.dp else 6.dp).toPx() } * e +
                            with(density) { 5.dp.toPx() } * drift * (if (i % 2 == 0) 1 else -1)
                        alpha = Spring.transform(t)
                        val s = 0.9f + 0.1f * e
                        scaleX = s; scaleY = s
                    }
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.verticalGradient(listOf(c.color, lerp(c.color, Color.Black, 0.45f))))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                GIcon(c.icon, tint = OnProfile.copy(alpha = 0.9f), size = 44.dp)
            }
        }
    }
}

@Composable
private fun TourStep() {
    StepTitle("So funktioniert's", "Vier Dinge.", "Der Rest erklärt sich unterwegs.")
    val enter = rememberStagger(4)
    val items = listOf(
        Triple(Ic.importFile, "Kataloge", "Filme und Serien kommen als Katalog aus dem Streamplayer Creator. Ein Muster reicht für alle Folgen einer Serie."),
        Triple(Ic.play, "Ein Tipp – und es läuft", "Die App öffnet die Seite unsichtbar, findet das Video und spielt es im eigenen Player. Werbung bleibt draußen."),
        Triple(Ic.download, "Für unterwegs", "Filme und Folgen laden und ohne Netz schauen."),
        Triple(Ic.sparkle, "Wie eine Mediathek", "Weiterschauen, nächste Folge, Cover und Beschreibungen – mit einem TMDB-Schlüssel auch automatisch."),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEachIndexed { i, (icon, title, text) ->
            Row(
                enter(i).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.surface)
                    .border(1.dp, Palette.line, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(icon, size = 44.dp)
                Box(Modifier.width(14.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    VSpace(2.dp)
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                }
            }
        }
    }
}

@Composable
private fun AccountStep() {
    val app = App.instance
    val user by app.cloud.user.collectAsStateWithLifecycle()
    val titles by app.library.titles.collectAsStateWithLifecycle()
    var register by remember { mutableStateOf(false) }
    StepTitle(
        "Konto",
        "Mit Konto oder nur hier?",
        "Ohne Konto bleibt alles auf diesem Gerät. Mit Konto sind Bibliothek, Profile und Fortschritt auf allen deinen Geräten gleich – und nach einer Neuinstallation wieder da.",
    )
    val u = user
    Column(Modifier.animateContentSize()) {
        when {
            u != null -> Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.surface)
                    .border(1.dp, Palette.line, RoundedCornerShape(22.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(Ic.check, size = 44.dp, bg = Palette.green, tint = OnProfile)
                Box(Modifier.width(14.dp))
                Column {
                    Text("Angemeldet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(u.email ?: u.name, if (titles.isNotEmpty()) "${titles.size} Titel in der Bibliothek" else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                    )
                }
            }
            !app.cloud.available -> Text("Konten sind in dieser Version nicht eingerichtet.", style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
            else -> SignInForm(register, { register = it }, onSignedIn = { })
        }
        VSpace(14.dp)
        Text("Geht auch später: Einstellungen → Konto.", style = MaterialTheme.typography.bodySmall, color = Palette.faint)
    }
}

@Composable
private fun ProfileStep(
    profiles: List<Profile>,
    active: Profile,
    name: String,
    onName: (String) -> Unit,
    color: Int,
    onColor: (Int) -> Unit,
) {
    val app = App.instance
    val fromAccount = profiles.size > 1
    StepTitle(
        "Profil",
        if (fromAccount) "Wer bist du?" else "Wer schaut?",
        if (fromAccount) "Dein Konto hat schon Profile. Wähl deins – jedes hat seinen eigenen Fortschritt."
        else "Jedes Profil hat eigenen Fortschritt und eigenes Weiterschauen. Weitere legst du später über dein Profilbild an.",
    )
    if (fromAccount) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            profiles.forEach { p ->
                val sel = p.id == active.id
                Column(Modifier.width(64.dp).clickable { app.profiles.select(p.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(60.dp).border(2.dp, if (sel) Palette.text else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                        ProfileAvatar(p, if (sel) 50.dp else 60.dp)
                    }
                    VSpace(6.dp)
                    Text(p.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = if (sel) Palette.text else Palette.muted)
                }
            }
        }
        VSpace(28.dp)
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Kurzer Federschlag bei jedem Farbwechsel: man sieht, dass die Wahl angekommen ist.
        val pop = remember { Animatable(1f) }
        LaunchedEffect(color) {
            pop.snapTo(0.86f)
            pop.animateTo(1f, tween(420, easing = Bouncy))
        }
        Box(Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
            ProfileAvatar(active.copy(name = name.ifBlank { "?" }, color = color), 104.dp)
        }
        VSpace(22.dp)
        IvoryField(name, onName, placeholder = "Dein Name")
        VSpace(22.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ProfileColors.forEachIndexed { i, c ->
                val sel = i == color
                Box(
                    Modifier.size(48.dp).border(2.dp, if (sel) Palette.text else Color.Transparent, CircleShape).padding(5.dp)
                        .clip(CircleShape).background(c).clickable { onColor(i) },
                )
            }
        }
    }
}

@Composable
private fun PrefsStep(notify: Boolean, onNotify: (Boolean) -> Unit) {
    val s = App.instance.settings
    val theme by s.theme.collectAsStateWithLifecycle()
    val adblock by s.adblock.collectAsStateWithLifecycle()
    val wifi by s.wifiOnly.collectAsStateWithLifecycle()
    StepTitle("Einstellungen", "Wie soll es laufen?", "Alles lässt sich später in den Einstellungen ändern.")
    Text("DARSTELLUNG", style = MaterialTheme.typography.labelSmall, color = Palette.muted)
    VSpace(10.dp)
    // Gilt sofort: der ganze Ablauf färbt sich um, man sieht das Ergebnis direkt.
    Segmented(listOf("system" to "System", "light" to "Hell", "dark" to "Dunkel"), theme, s::setTheme)
    VSpace(24.dp)
    ListGroup {
        ListRow("Werbeblocker", icon = Ic.shield, subtitle = "Werbung und Tracker auf Seiten blockieren", trailing = { IvorySwitch(adblock, s::setAdblock) })
        RowDivider()
        ListRow("Nur im WLAN laden", icon = Ic.wifi, subtitle = "Downloads warten auf WLAN", trailing = { IvorySwitch(wifi, s::setWifiOnly) })
        RowDivider()
        ListRow("Download-Fortschritt", icon = Ic.downloads, subtitle = "Als Benachrichtigung anzeigen", trailing = { IvorySwitch(notify, onNotify) })
    }
}

@Composable
private fun ReadyStep(profile: Profile, account: String?) {
    val app = App.instance
    val titles by app.library.titles.collectAsStateWithLifecycle()
    val profiles by app.profiles.profiles.collectAsStateWithLifecycle()
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, tween(520, easing = Bouncy)) }
    val enter = rememberStagger(4)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        VSpace(48.dp)
        Box(contentAlignment = Alignment.BottomEnd, modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = pop.value.coerceIn(0f, 1f) }) {
            ProfileAvatar(profile, 116.dp)
            IconBadge(Ic.check, size = 38.dp, bg = Palette.green, tint = OnProfile)
        }
        VSpace(28.dp)
        Text("Alles bereit, ${profile.name}.", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = enter(0))
        VSpace(28.dp)
        Box(enter(1)) {
            ListGroup {
                ListRow(
                    title = account ?: "Nur auf diesem Gerät",
                    subtitle = if (account != null) "Konto · wird abgeglichen" else "Anmelden geht später in den Einstellungen",
                    icon = if (account != null) Ic.cloudDone else Ic.account,
                )
                RowDivider()
                ListRow(
                    title = if (titles.isEmpty()) "Bibliothek noch leer" else "${titles.size} Titel in der Bibliothek",
                    subtitle = if (titles.isEmpty()) "Katalog importieren oder im Browser suchen" else "Bereit zum Weiterschauen",
                    icon = Ic.library,
                )
                RowDivider()
                ListRow(
                    title = if (profiles.size == 1) "1 Profil" else "${profiles.size} Profile",
                    subtitle = "Wechseln über dein Profilbild oben rechts",
                    icon = Ic.group,
                )
            }
        }
    }
}
