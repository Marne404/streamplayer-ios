package de.marne.streamplayer.onboarding

import org.jetbrains.compose.resources.DrawableResource

import android.Manifest
import de.marne.streamplayer.ui.Lang
import de.marne.streamplayer.ui.tr
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

private const val STEPS = 7
private const val STEP_LANGUAGE = 0
private const val STEP_ACCOUNT = 3
private const val STEP_PROFILE = 4
private const val STEP_PREFS = 5

/**
 * Willkommen beim ersten Start: Sprache, worum es geht, wie es funktioniert, Konto, Profil,
 * Einstellungen, fertig. Der Ablauf beginnt auf Englisch; der erste Schritt fragt nach der
 * Sprache und bietet die des Geräts an – ab dem Tipp spricht alles in ihr. Anmelden ist freiwillig – ohne Konto läuft alles auf dem Gerät.
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
    // Entwurf fürs Profil. Das unberührte Standardprofil („Ich“/„Me“) startet leer, damit man seinen Namen tippt.
    var name by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableIntStateOf(0) }
    var notify by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(active.id, active.name, active.color) {
        name = if (active.id == Profile.DEFAULT_ID && (active.name == "Ich" || active.name == "Me")) "" else active.name
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
    // Gesperrtes Profil aus dem Konto gewählt: erst die PIN.
    var pinFor by remember { mutableStateOf<Profile?>(null) }

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
                    STEP_LANGUAGE -> LanguageStep()
                    1 -> WelcomeStep()
                    2 -> TourStep()
                    STEP_ACCOUNT -> AccountStep()
                    STEP_PROFILE -> ProfileStep(profiles, active, name, { name = it.take(24) }, color, onPick = { p ->
                        if (p.locked && p.id != active.id) pinFor = p else app.profiles.select(p.id)
                    }) {
                        color = it
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                    STEP_PREFS -> PrefsStep(notify) { notify = it }
                    else -> ReadyStep(active.copy(name = name.trim().ifEmpty { tr("Ich", "Me") }, color = color), user?.email ?: user?.name)
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
                STEP_LANGUAGE -> tr("Weiter", "Continue")
                1 -> tr("Los", "Let's go")
                STEP_ACCOUNT -> if (user == null) tr("Ohne Konto weiter", "Continue without account") else tr("Weiter", "Continue")
                STEPS - 1 -> tr("Zur Bibliothek", "Go to library")
                else -> tr("Weiter", "Continue")
            },
            enabled = step != STEP_PROFILE || name.isNotBlank(),
            modifier = Modifier.padding(vertical = 16.dp),
            onClick = ::next,
        )
        }
    }
    pinFor?.let { p ->
        de.marne.streamplayer.account.PinDialog(p, onDismiss = { pinFor = null }) {
            app.profiles.select(p.id)
            pinFor = null
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

/**
 * Erster Schritt: Sprache. Startet Englisch; die des Geräts ist markiert und, wenn sie Deutsch ist,
 * oben angeboten. Ein Tipp schaltet sofort um – der Bildschirm wechselt die Sprache unter dem Finger.
 */
@Composable
private fun LanguageStep() {
    val s = App.instance.settings
    val current by s.language.collectAsStateWithLifecycle()
    val system = Lang.system()
    val enter = rememberStagger(4)
    VSpace(40.dp)
    Text("STREAMPLAYER", style = MaterialTheme.typography.labelSmall, color = Palette.brass, modifier = enter(0))
    VSpace(10.dp)
    Text(tr("Welche Sprache?", "Choose your language."), style = MaterialTheme.typography.headlineLarge, modifier = enter(1))
    VSpace(10.dp)
    Text(
        when (system) {
            Lang.Code.DE -> tr("Dein Gerät ist auf Deutsch eingestellt.", "Your phone is set to German. Streamplayer speaks it too.")
            Lang.Code.EN -> tr("Dein Gerät ist auf Englisch eingestellt.", "Your phone is set to English.")
            null -> tr("Streamplayer spricht Deutsch und Englisch.", "Streamplayer speaks English and German.")
        },
        style = MaterialTheme.typography.bodyLarge, color = Palette.muted, modifier = enter(2),
    )
    VSpace(28.dp)
    Box(enter(3)) {
        ListGroup {
            // Die Gerätesprache zuerst: das ist die Frage, die hier gestellt wird.
            val order = if (system == Lang.Code.DE) listOf(Lang.Code.DE, Lang.Code.EN) else listOf(Lang.Code.EN, Lang.Code.DE)
            order.forEachIndexed { i, code ->
                if (i > 0) RowDivider()
                ListRow(
                    // Jede Sprache heißt in sich selbst – wer die andere nicht liest, findet seine trotzdem.
                    title = if (code == Lang.Code.DE) "Deutsch" else "English",
                    subtitle = if (code == system) tr("Sprache des Geräts", "Device language") else null,
                    icon = Ic.browser,
                    showChevron = false,
                    onClick = { s.setLanguage(code) },
                    trailing = {
                        if (code == current) GIcon(Ic.checkCircle, tint = Palette.text, size = 22.dp)
                        else Box(Modifier.size(22.dp).border(1.5.dp, Palette.line, CircleShape))
                    },
                )
            }
        }
    }
    VSpace(14.dp)
    Text(tr("Lässt sich später in den Einstellungen ändern.", "You can change this later in Settings."), style = MaterialTheme.typography.bodySmall, color = Palette.faint)
}

@Composable
private fun WelcomeStep() {
    val enter = rememberStagger(3)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        VSpace(40.dp)
        PosterFan()
        VSpace(44.dp)
        Text("STREAMPLAYER", style = MaterialTheme.typography.labelSmall, color = Palette.brass, modifier = enter(0))
        VSpace(10.dp)
        Text(tr("Deine Mediathek.", "Your library."), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center, modifier = enter(1))
        VSpace(14.dp)
        Text(
            tr(
                "Filme und Serien von den Seiten, auf denen sie laufen – geordnet wie bei Netflix, abgespielt im eigenen Player.",
                "Movies and shows from the sites they stream on – organized like Netflix, played in a player of its own.",
            ),
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
    data class Card(val icon: DrawableResource, val color: Color, val side: Int)
    val cards = listOf(Card(Ic.tv, ProfileColors[3], -1), Card(Ic.movie, ProfileColors[2], 1), Card(Ic.play, ProfileColors[0], 0))
    // Der Fächer braucht gut 340 dp. Auf schmalen Bildschirmen (oder mit großer Anzeigegröße) wurde er
    // links und rechts abgeschnitten – also mit der verfügbaren Breite verkleinern statt beschneiden.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(230.dp)) {
    val fit = (maxWidth / 352.dp).coerceAtMost(1f)
    Box(Modifier.fillMaxSize().graphicsLayer { scaleX = fit; scaleY = fit }, contentAlignment = Alignment.Center) {
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
}

@Composable
private fun TourStep() {
    StepTitle(tr("So funktioniert's", "How it works"), tr("Vier Dinge.", "Four things."), tr("Der Rest erklärt sich unterwegs.", "The rest explains itself along the way."))
    val enter = rememberStagger(4)
    val items = listOf(
        Triple(Ic.importFile, tr("Kataloge", "Catalogs"), tr("Filme und Serien kommen als Katalog aus dem Streamplayer Creator. Ein Muster reicht für alle Folgen einer Serie.", "Movies and shows arrive as a catalog from Streamplayer Creator. One pattern covers every episode of a show.")),
        Triple(Ic.play, tr("Ein Tipp – und es läuft", "One tap – and it plays"), tr("Die App öffnet die Seite unsichtbar, findet das Video und spielt es im eigenen Player. Werbung bleibt draußen.", "The app opens the page invisibly, finds the video and plays it in its own player. Ads stay out.")),
        Triple(Ic.download, tr("Für unterwegs", "On the go"), tr("Filme und Folgen laden und ohne Netz schauen.", "Download movies and episodes and watch offline.")),
        Triple(Ic.sparkle, tr("Wie eine Mediathek", "Like a streaming service"), tr("Weiterschauen, nächste Folge, Cover und Beschreibungen – mit einem TMDB-Schlüssel auch automatisch.", "Continue watching, next episode, covers and descriptions – automatic with a TMDB key.")),
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
        tr("Konto", "Account"),
        tr("Mit Konto oder nur hier?", "With an account or just here?"),
        tr(
            "Ohne Konto bleibt alles auf diesem Gerät. Mit Konto sind Bibliothek, Profile und Fortschritt auf allen deinen Geräten gleich – und nach einer Neuinstallation wieder da.",
            "Without an account everything stays on this device. With one, library, profiles and progress are the same on all your devices – and come back after a reinstall.",
        ),
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
                    Text(tr("Angemeldet", "Signed in"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(u.email ?: u.name, if (titles.isNotEmpty()) tr("${titles.size} Titel in der Bibliothek", "${titles.size} titles in the library") else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                    )
                }
            }
            !app.cloud.available -> Text(tr("Konten sind in dieser Version nicht eingerichtet.", "Accounts aren't set up in this version."), style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
            else -> SignInForm(register, { register = it }, onSignedIn = { })
        }
        VSpace(14.dp)
        Text(tr("Geht auch später: Einstellungen → Konto.", "You can do this later: Settings → Account."), style = MaterialTheme.typography.bodySmall, color = Palette.faint)
    }
}

@Composable
private fun ProfileStep(
    profiles: List<Profile>,
    active: Profile,
    name: String,
    onName: (String) -> Unit,
    color: Int,
    onPick: (Profile) -> Unit,
    onColor: (Int) -> Unit,
) {
    val fromAccount = profiles.size > 1
    StepTitle(
        tr("Profil", "Profile"),
        if (fromAccount) tr("Wer bist du?", "Who are you?") else tr("Wer schaut?", "Who's watching?"),
        if (fromAccount) tr("Dein Konto hat schon Profile. Wähl deins – jedes hat seinen eigenen Fortschritt.", "Your account already has profiles. Pick yours – each has its own progress.")
        else tr("Jedes Profil hat eigenen Fortschritt und eigenes Weiterschauen. Weitere legst du später über dein Profilbild an.", "Each profile has its own progress and continue watching. Add more later via your profile picture."),
    )
    if (fromAccount) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            profiles.forEach { p ->
                val sel = p.id == active.id
                Column(Modifier.width(64.dp).clickable { onPick(p) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(60.dp).border(2.dp, if (sel) Palette.text else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                        ProfileAvatar(p, if (sel) 50.dp else 60.dp)
                    }
                    VSpace(6.dp)
                    Text(p.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = if (sel) Palette.text else Palette.muted)
                    if (p.locked) GIcon(Ic.lock, tint = Palette.muted, size = 14.dp)
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
        IvoryField(name, onName, placeholder = tr("Dein Name", "Your name"))
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
    StepTitle(tr("Einstellungen", "Settings"), tr("Wie soll es laufen?", "How should it run?"), tr("Alles lässt sich später in den Einstellungen ändern.", "Everything can be changed later in Settings."))
    Text(tr("DARSTELLUNG", "APPEARANCE"), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
    VSpace(10.dp)
    // Gilt sofort: der ganze Ablauf färbt sich um, man sieht das Ergebnis direkt.
    Segmented(listOf("system" to "System", "light" to tr("Hell", "Light"), "dark" to tr("Dunkel", "Dark")), theme, s::setTheme)
    VSpace(24.dp)
    ListGroup {
        ListRow(tr("Werbeblocker", "Ad blocker"), icon = Ic.shield, subtitle = tr("Werbung und Tracker auf Seiten blockieren", "Block ads and trackers on sites"), trailing = { IvorySwitch(adblock, s::setAdblock) })
        RowDivider()
        ListRow(tr("Nur im WLAN laden", "Download on Wi-Fi only"), icon = Ic.wifi, subtitle = tr("Downloads warten auf WLAN", "Downloads wait for Wi-Fi"), trailing = { IvorySwitch(wifi, s::setWifiOnly) })
        RowDivider()
        ListRow(tr("Download-Fortschritt", "Download progress"), icon = Ic.downloads, subtitle = tr("Als Benachrichtigung anzeigen", "Show as a notification"), trailing = { IvorySwitch(notify, onNotify) })
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
        Text(tr("Alles bereit, ${profile.name}.", "All set, ${profile.name}."), style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = enter(0))
        VSpace(28.dp)
        Box(enter(1)) {
            ListGroup {
                ListRow(
                    title = account ?: tr("Nur auf diesem Gerät", "Only on this device"),
                    subtitle = if (account != null) tr("Konto · wird abgeglichen", "Account · syncing") else tr("Anmelden geht später in den Einstellungen", "You can sign in later in Settings"),
                    icon = if (account != null) Ic.cloudDone else Ic.account,
                )
                RowDivider()
                ListRow(
                    title = if (titles.isEmpty()) tr("Bibliothek noch leer", "Library still empty") else tr("${titles.size} Titel in der Bibliothek", "${titles.size} titles in the library"),
                    subtitle = if (titles.isEmpty()) tr("Katalog importieren oder im Browser suchen", "Import a catalog or search in the browser") else tr("Bereit zum Weiterschauen", "Ready to continue watching"),
                    icon = Ic.library,
                )
                RowDivider()
                ListRow(
                    title = if (profiles.size == 1) tr("1 Profil", "1 profile") else tr("${profiles.size} Profile", "${profiles.size} profiles"),
                    subtitle = tr("Wechseln über dein Profilbild oben rechts", "Switch via your profile picture at the top right"),
                    icon = Ic.group,
                )
            }
        }
    }
}
