package de.marne.streamplayer.library

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.account.ProfileColors
import de.marne.streamplayer.ui.design.GIcon
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.IvoryField
import de.marne.streamplayer.ui.design.ListGroup
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.PrimaryButton
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.RowDivider
import de.marne.streamplayer.ui.design.SheetHandle
import de.marne.streamplayer.ui.design.VSpace
import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.launch

/** Was das Blatt auslösen kann; umgesetzt in AppRoot. */
class LibrariesActions(
    val select: (String) -> Unit,
    /** Neue Bibliothek anlegen und gleich zeigen. */
    val create: (String, Int) -> Unit,
    val update: (String, String, Int) -> Unit,
    val delete: (String) -> Unit,
    /** Einladungstext über das Teilen-Menü von Android verschicken. */
    val sendInvite: (String) -> Unit,
    /** Einladung einlösen (Code oder ganze Nachricht). */
    val join: (String) -> Unit,
    val toast: (String) -> Unit,
)

/** Kleiner Farbpunkt einer Bibliothek – „Alle“ hat einen Ring statt einer Farbe. */
@Composable
fun LibraryDot(color: Int?, size: androidx.compose.ui.unit.Dp = 12.dp) {
    if (color == null) Box(Modifier.size(size).clip(CircleShape).border(1.5.dp, Palette.muted, CircleShape))
    else Box(Modifier.size(size).clip(CircleShape).background(ProfileColors[color.mod(ProfileColors.size)]))
}

/**
 * Bibliotheken wechseln, anlegen, bearbeiten. Ein Blatt mit zwei Seiten: die Liste und das
 * Formular (Name, Farbe, Löschen). Die Seiten gehen ineinander über, statt dass ein Dialog
 * über dem Blatt aufgeht – zwei Ebenen übereinander verdecken, worum es geht.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrariesSheet(state: LibState, actions: LibrariesActions, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun then(block: () -> Unit) {
        scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss(); block() }
    }
    // null = Liste; "" = neue Bibliothek; "+join" = Einladung einlösen; sonst die ID der bearbeiteten.
    var editing by remember { mutableStateOf<String?>(null) }
    val counts = remember(state.all) { state.all.groupingBy { it.library }.eachCount() }

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = Palette.bg, contentColor = Palette.text,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp), tonalElevation = 0.dp, dragHandle = { SheetHandle() },
    ) {
        AnimatedContent(
            targetState = editing,
            transitionSpec = { fadeIn(tween(220, delayMillis = 80)) togetherWith fadeOut(tween(140)) },
            label = "libs",
        ) { ed ->
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
                if (ed == null) {
                    Text(tr("BIBLIOTHEKEN", "LIBRARIES"), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
                    VSpace(6.dp)
                    Text(tr("Was schaust du?", "What are you watching?"), style = MaterialTheme.typography.headlineSmall)
                    VSpace(4.dp)
                    Text(
                        tr("Jede Bibliothek hat ihre eigene Startseite – z. B. eine für Anime, eine für Serien.",
                            "Each library has its own home – one for anime, one for shows, for example."),
                        style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                    )
                    VSpace(18.dp)
                    ListGroup {
                        if (state.libraries.size > 1) {
                            LibraryRow(null, tr("Alles", "Everything"), state.all.size, state.activeLibrary == Library.ALL, onEdit = null) { then { actions.select(Library.ALL) } }
                            RowDivider(inset = 50.dp)
                        }
                        state.libraries.forEachIndexed { i, l ->
                            if (i > 0) RowDivider(inset = 50.dp)
                            LibraryRow(
                                l.color, l.label, counts[l.id] ?: 0, state.activeLibrary == l.id || (state.libraries.size == 1), onEdit = { editing = l.id },
                                note = when {
                                    l.joined -> tr("von ${l.owner ?: "einem Freund"}", "from ${l.owner ?: "a friend"}")
                                    l.shared -> tr("geteilt", "shared")
                                    else -> null
                                },
                            ) {
                                then { actions.select(l.id) }
                            }
                        }
                    }
                    VSpace(12.dp)
                    ListGroup {
                        de.marne.streamplayer.ui.design.ListRow(tr("Neue Bibliothek", "New library"), icon = Ic.add, showChevron = false, onClick = { editing = "" })
                        RowDivider()
                        de.marne.streamplayer.ui.design.ListRow(tr("Einladung einlösen", "Redeem invite"), icon = Ic.group, subtitle = tr("Bibliothek eines Freundes ansehen", "View a friend's library"), onClick = { editing = "+join" })
                    }
                } else if (ed == "+join") {
                    JoinForm(onBack = { editing = null }) { text -> then { actions.join(text) } }
                } else if (state.libraries.firstOrNull { it.id == ed }?.joined == true) {
                    JoinedForm(state.libraries.first { it.id == ed }, counts[ed] ?: 0, onBack = { editing = null }) {
                        then { actions.delete(ed) }
                    }
                } else {
                    val lib = state.libraries.firstOrNull { it.id == ed }
                    LibraryForm(
                        lib, counts[ed] ?: 0,
                        onBack = { editing = null },
                        onSave = { name, color ->
                            if (lib == null) then { actions.create(name, color) } else { actions.update(lib.id, name, color); editing = null }
                        },
                        onDelete = { lib?.let { actions.delete(it.id) }; editing = null },
                        nextColor = (state.libraries.size) % ProfileColors.size,
                    )
                    // Teilen nur für eigene, schon angelegte Bibliotheken.
                    if (lib != null) {
                        VSpace(24.dp)
                        ShareSection(lib, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(color: Int?, label: String, count: Int, selected: Boolean, onEdit: (() -> Unit)?, note: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryDot(color, 14.dp)
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(if (count == 1) tr("1 Titel", "1 title") else tr("$count Titel", "$count titles"), note).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) GIcon(Ic.check, tint = Palette.text, size = 20.dp, modifier = Modifier.padding(end = 6.dp))
        if (onEdit != null) IconButton(onClick = onEdit) { GIcon(Ic.edit, tint = Palette.muted, size = 18.dp) }
        else Box(Modifier.size(48.dp))
    }
}

@Composable
private fun LibraryForm(lib: Library?, count: Int, onBack: () -> Unit, onSave: (String, Int) -> Unit, onDelete: () -> Unit, nextColor: Int) {
    var name by remember { mutableStateOf(lib?.name ?: "") }
    var color by remember { mutableIntStateOf(lib?.color ?: nextColor) }
    var confirm by remember { mutableStateOf(false) }
    val main = lib?.id == Library.MAIN
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.padding(end = 4.dp)) { GIcon(Ic.back) }
        Text(if (lib == null) tr("Neue Bibliothek", "New library") else lib.label, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    VSpace(14.dp)
    IvoryField(name, { name = it }, if (main) tr("Meine Bibliothek", "My Library") else tr("Name, z. B. Anime", "Name, e.g. Anime"))
    VSpace(14.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ProfileColors.indices.forEach { i ->
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(ProfileColors[i])
                    .then(if (i == color) Modifier.border(2.5.dp, Palette.text, CircleShape) else Modifier)
                    .clickable { color = i },
            )
        }
    }
    VSpace(20.dp)
    PrimaryButton(
        if (lib == null) tr("Anlegen", "Create") else tr("Speichern", "Save"),
        enabled = main || name.isNotBlank(), modifier = Modifier.fillMaxWidth(),
    ) { onSave(name, color) }
    if (lib != null && !main) {
        VSpace(12.dp)
        AnimatedContent(confirm, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) }, label = "del") { asking ->
            if (!asking) QuietButton(tr("Bibliothek löschen", "Delete library"), color = Palette.wine, modifier = Modifier.fillMaxWidth()) { confirm = true }
            else Column {
                Text(
                    if (count == 0) tr("Die Bibliothek ist leer.", "The library is empty.")
                    else tr("Ihre $count Titel wandern nach „Meine Bibliothek“ – gelöscht wird kein Titel.",
                        "Its $count titles move to “My Library” – no title is deleted."),
                    style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                )
                Row {
                    QuietButton(tr("Löschen", "Delete"), color = Palette.wine, onClick = onDelete)
                    QuietButton(tr("Behalten", "Keep"), color = Palette.muted) { confirm = false }
                }
            }
        }
    }
}

/** Eine Bibliothek wählen: Ziel eines Katalog-Imports oder wohin ein Titel umzieht. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryPickSheet(eyebrow: String, title: String, libraries: List<Library>, selected: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = Palette.bg, contentColor = Palette.text,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp), tonalElevation = 0.dp, dragHandle = { SheetHandle() },
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp).navigationBarsPadding()) {
            Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
            VSpace(6.dp)
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            VSpace(18.dp)
            ListGroup {
                libraries.forEachIndexed { i, l ->
                    if (i > 0) RowDivider(inset = 50.dp)
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss(); onPick(l.id) }
                        }.padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LibraryDot(l.color, 14.dp)
                        Text(l.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 18.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (l.id == selected) GIcon(Ic.check, tint = Palette.text, size = 20.dp)
                    }
                }
            }
        }
    }
}


/**
 * Teilen einer eigenen Bibliothek: Einladung schicken, sehen, wer drin ist, jemanden entfernen,
 * Teilen beenden. Freunde können schauen und laden, aber nichts ändern.
 */
@Composable
private fun ShareSection(lib: Library, actions: LibrariesActions) {
    val app = de.marne.streamplayer.App.instance
    val user by app.cloud.user.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var members by remember(lib.id, lib.shared) { mutableStateOf<List<de.marne.streamplayer.account.Sharing.Member>?>(null) }
    var confirmStop by remember { mutableStateOf(false) }
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { block() }.onFailure { actions.toast(it.message ?: tr("Hat nicht geklappt.", "That didn't work.")) }
            busy = false
        }
    }
    LaunchedEffect(lib.id, lib.shared, user?.uid) {
        if (lib.shared && user != null) members = runCatching { app.sharing.members(lib) }.getOrNull()
    }

    Text(tr("MIT FREUNDEN TEILEN", "SHARE WITH FRIENDS"), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
    VSpace(8.dp)
    when {
        !app.cloud.available -> Text(tr("Konten sind in dieser Version nicht eingerichtet.", "Accounts aren't set up in this version."), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        // „Meine Bibliothek“ heißt bei allen „main“ – geteilt würden sich Konten gegenseitig überschreiben.
        lib.id == Library.MAIN -> Text(
            tr("Diese Bibliothek bleibt deine. Zum Teilen leg eine eigene an (z. B. „Anime mit Freunden“) und verschieb Titel hinein.",
                "This library stays yours. To share, create a separate one (e.g. “Anime with friends”) and move titles into it."),
            style = MaterialTheme.typography.bodySmall, color = Palette.muted,
        )
        user == null -> Text(
            tr("Zum Teilen bei einem Konto anmelden (Einstellungen → Konto). Freunde brauchen auch eins.",
                "Sign in to an account to share (Settings → Account). Friends need one too."),
            style = MaterialTheme.typography.bodySmall, color = Palette.muted,
        )
        !lib.shared -> {
            Text(
                tr("Freunde mit Konto sehen diese Bibliothek bei sich und können daraus schauen und laden – ändern kannst nur du.",
                    "Friends with an account see this library and can watch and download from it – only you can change it."),
                style = MaterialTheme.typography.bodySmall, color = Palette.muted,
            )
            VSpace(12.dp)
            de.marne.streamplayer.ui.design.SecondaryButton(if (busy) tr("Einen Moment …", "One moment …") else tr("Einladung schicken", "Send invite"), icon = Ic.share, modifier = Modifier.fillMaxWidth()) {
                run { val code = app.sharing.invite(lib); actions.sendInvite(de.marne.streamplayer.account.Sharing.inviteText(lib, code)) }
            }
        }
        else -> {
            ListGroup {
                de.marne.streamplayer.ui.design.ListRow(tr("Einladung schicken", "Send invite"), icon = Ic.share, showChevron = false, onClick = {
                    run { val code = app.sharing.invite(lib); actions.sendInvite(de.marne.streamplayer.account.Sharing.inviteText(lib, code)) }
                })
                val list = members
                if (list == null) {
                    RowDivider()
                    de.marne.streamplayer.ui.design.ListRow(tr("Mitglieder werden geladen …", "Loading members …"), icon = Ic.group, showChevron = false)
                } else if (list.isEmpty()) {
                    RowDivider()
                    de.marne.streamplayer.ui.design.ListRow(tr("Noch niemand beigetreten", "Nobody has joined yet"), icon = Ic.group, showChevron = false)
                } else list.forEach { m ->
                    RowDivider()
                    de.marne.streamplayer.ui.design.ListRow(m.name, icon = Ic.account, showChevron = false, trailing = {
                        QuietButton(tr("Entfernen", "Remove"), color = Palette.wine) {
                            run { app.sharing.removeMember(lib, m.uid); members = members?.filterNot { it.uid == m.uid } }
                        }
                    })
                }
            }
            VSpace(10.dp)
            AnimatedContent(confirmStop, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) }, label = "stop") { asking ->
                if (!asking) QuietButton(tr("Teilen beenden", "Stop sharing"), color = Palette.wine, modifier = Modifier.fillMaxWidth()) { confirmStop = true }
                else Column {
                    Text(tr("Bei deinen Freunden verschwindet die Bibliothek. Bei dir bleibt alles.", "The library disappears for your friends. Everything stays with you."), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                    Row {
                        QuietButton(tr("Beenden", "Stop"), color = Palette.wine) { confirmStop = false; run { app.sharing.stop(lib) } }
                        QuietButton(tr("Weiter teilen", "Keep sharing"), color = Palette.muted) { confirmStop = false }
                    }
                }
            }
        }
    }
}

/** Einladung einlösen: Code oder die ganze Nachricht einfügen; steht einer in der Zwischenablage, ist er schon drin. */
@Composable
private fun JoinForm(onBack: () -> Unit, onJoin: (String) -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var text by remember {
        mutableStateOf(clipboard.getText()?.text?.let(de.marne.streamplayer.account.Sharing::codeIn).orEmpty())
    }
    val code = de.marne.streamplayer.account.Sharing.codeIn(text)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.padding(end = 4.dp)) { GIcon(Ic.back) }
        Text(tr("Einladung einlösen", "Redeem invite"), style = MaterialTheme.typography.headlineSmall)
    }
    VSpace(6.dp)
    Text(
        tr("Code oder die ganze Nachricht deines Freundes einfügen. Du brauchst dafür ein Konto.",
            "Paste the code or your friend's whole message. You need an account for this."),
        style = MaterialTheme.typography.bodySmall, color = Palette.muted,
    )
    VSpace(14.dp)
    IvoryField(text, { text = it }, tr("Code", "Code"))
    VSpace(16.dp)
    PrimaryButton(tr("Beitreten", "Join"), enabled = code != null, modifier = Modifier.fillMaxWidth()) { code?.let(onJoin) }
}

/** Bibliothek eines Freundes: wem sie gehört, und Verlassen. */
@Composable
private fun JoinedForm(lib: Library, count: Int, onBack: () -> Unit, onLeave: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.padding(end = 4.dp)) { GIcon(Ic.back) }
        LibraryDot(lib.color, 14.dp)
        Text(lib.label, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
    }
    VSpace(8.dp)
    Text(
        tr("Geteilt von ${lib.owner ?: "einem Freund"} · $count Titel. Du kannst schauen und laden; ändern kann nur ${lib.owner ?: "er oder sie"}. Dein Fortschritt bleibt deiner.",
            "Shared by ${lib.owner ?: "a friend"} · $count titles. You can watch and download; only ${lib.owner ?: "they"} can change it. Your progress stays yours."),
        style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
    )
    VSpace(20.dp)
    AnimatedContent(confirm, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) }, label = "leave") { asking ->
        if (!asking) QuietButton(tr("Bibliothek verlassen", "Leave library"), color = Palette.wine, modifier = Modifier.fillMaxWidth()) { confirm = true }
        else Column {
            Text(tr("Die Titel verschwinden hier; Downloads daraus bleiben unter Downloads.", "The titles disappear here; downloads from it stay under Downloads."), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
            Row {
                QuietButton(tr("Verlassen", "Leave"), color = Palette.wine, onClick = onLeave)
                QuietButton(tr("Bleiben", "Stay"), color = Palette.muted) { confirm = false }
            }
        }
    }
}
