// Ivory & Ink – aus dem Design-System übernommen (Quelle: GlowUp). Diagramme aus GlowUp sind weggelassen.
package de.marne.streamplayer.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.marne.streamplayer.ui.tr
import org.jetbrains.compose.resources.DrawableResource

// ---------- Grundbausteine ----------

@Composable
fun GIcon(res: DrawableResource, modifier: Modifier = Modifier, tint: Color = Palette.text, size: Dp = 22.dp) {
    Icon(painterResource(res), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Palette.line))
}

/** Karte: ruhige Fläche mit Haarlinie. tone = "card" | "sunk" | "ink" */
@Composable
fun GCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    tone: String = "card",
    padding: Dp = 20.dp,
    radius: Dp = 24.dp,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    val bg = when (tone) { "sunk" -> Palette.surface2; "ink" -> Palette.ink; else -> Palette.surface }
    val border = if (tone == "card") Palette.line else Color.Transparent
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .let { if (onClick != null) it.clickable { onClick() } else it }
            .padding(padding)
    ) { content() }
}

/** Kleine Überschrift in Versalien mit Laufweite – wie in einem Magazin. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 36.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action, style = MaterialTheme.typography.labelMedium, color = Palette.text,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onAction() }.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/** Seitenkopf: kleine Zeile darüber, große Serifen-Überschrift, optional Untertitel und Aktion. */
@Composable
fun ScreenHeader(title: String, eyebrow: String? = null, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
                VSpace(6.dp)
            }
            Text(title, style = MaterialTheme.typography.headlineLarge, color = Palette.text)
            if (subtitle != null) {
                VSpace(6.dp)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
            }
        }
        if (trailing != null) trailing()
    }
}

// ---------- Listen im Stil gruppierter Einstellungen (ruhig, viel Luft) ----------

/** Karte ohne Innenabstand für Zeilen mit Trennlinien. */
@Composable
fun ListGroup(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Palette.surface).border(1.dp, Palette.line, shape),
        content = content
    )
}

@Composable
fun RowDivider(inset: Dp = 56.dp) {
    Box(Modifier.fillMaxWidth().padding(start = inset).height(1.dp).background(Palette.line))
}

/** Eine Zeile: Icon, Titel, optionaler Wert rechts, Pfeil wenn antippbar. */
@Composable
fun ListRow(
    title: String,
    icon: DrawableResource? = null,
    value: String? = null,
    subtitle: String? = null,
    titleColor: Color = Palette.text,
    showChevron: Boolean = true,
    titleMaxLines: Int = Int.MAX_VALUE,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable { onClick() } else it }
            .padding(horizontal = 18.dp, vertical = if (subtitle != null) 12.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            GIcon(icon, tint = Palette.muted, size = 20.dp)
            HSpace(18.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor, maxLines = titleMaxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1)
        }
        if (value != null) {
            HSpace(12.dp)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = Palette.muted, maxLines = 1)
        }
        if (trailing != null) { HSpace(12.dp); trailing() }
        else if (onClick != null && showChevron) { HSpace(6.dp); GIcon(Ic.chevron, tint = Palette.faint, size = 18.dp) }
    }
}

/** Aufklappbarer Abschnitt: Details erst auf Wunsch (progressive Offenlegung). */
@Composable
fun Disclosure(
    title: String,
    icon: DrawableResource? = null,
    value: String? = null,
    initiallyOpen: Boolean = false,
    content: @Composable () -> Unit,
) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(title) { androidx.compose.runtime.mutableStateOf(initiallyOpen) }
    Column(Modifier.fillMaxWidth().animateContentSizeCompat()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) { GIcon(icon, tint = Palette.muted, size = 20.dp); HSpace(18.dp) }
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (value != null) { Text(value, style = MaterialTheme.typography.bodyMedium, color = Palette.muted); HSpace(8.dp) }
            GIcon(if (open) Ic.expandLess else Ic.expandMore, tint = Palette.faint, size = 20.dp)
        }
        if (open) Box(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp)) { content() }
    }
}

@Composable
private fun Modifier.animateContentSizeCompat(): Modifier =
    this.then(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()))

/** Kleines (i): Erklärungen stehen im Dialog statt auf dem Bildschirm. */
@Composable
fun InfoButton(title: String, text: String) {
    var show by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    IconButton(onClick = { show = true }) { GIcon(Ic.info, tint = Palette.muted, size = 20.dp) }
    if (show) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { show = false },
            containerColor = Palette.surface,
            title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.muted) },
            confirmButton = { QuietButton("OK") { show = false } },
        )
    }
}

/** Fließtext, der nach wenigen Zeilen abbricht und sich auf Antippen öffnet. */
@Composable
fun ExpandableText(text: String, collapsedLines: Int = 3, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge, color: Color = Palette.muted) {
    var open by androidx.compose.runtime.remember(text) { androidx.compose.runtime.mutableStateOf(false) }
    var overflow by androidx.compose.runtime.remember(text) { androidx.compose.runtime.mutableStateOf(false) }
    Column(Modifier.animateContentSizeCompat()) {
        Text(
            text, style = style, color = color,
            maxLines = if (open) Int.MAX_VALUE else collapsedLines,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            onTextLayout = { if (!open) overflow = it.hasVisualOverflow },
            modifier = Modifier.clickable(enabled = overflow || open) { open = !open }
        )
        if (overflow || open) {
            VSpace(4.dp)
            Text(if (open) tr("Weniger", "Less") else tr("Mehr", "More"), style = MaterialTheme.typography.labelLarge, color = Palette.text, modifier = Modifier.clickable { open = !open })
        }
    }
}

/** Schritt-Überschrift mit Nummer, z. B. im Analyse-Ablauf. */
@Composable
fun StepTitle(number: Int, title: String, done: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(if (done) Palette.ink else Palette.surface2),
            contentAlignment = Alignment.Center
        ) {
            if (done) GIcon(Ic.check, tint = Palette.onInk, size = 16.dp)
            else Text("$number", style = MaterialTheme.typography.labelLarge, color = Palette.text)
        }
        HSpace(12.dp)
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

@Composable
fun BackBar(onBack: () -> Unit, title: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { GIcon(Ic.back) }
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        else Spacer(Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

@Composable
fun Pill(text: String, icon: DrawableResource? = null, color: Color = Palette.surface2, textColor: Color = Palette.text, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { GIcon(icon, tint = textColor, size = 14.dp); HSpace(5.dp) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = textColor)
    }
}

@Composable
fun OutlinePill(text: String, icon: DrawableResource? = null, modifier: Modifier = Modifier, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(if (selected) Palette.ink else Color.Transparent)
            .border(1.dp, if (selected) Palette.ink else Palette.line, shape)
            .let { if (onClick != null) it.clickable { onClick() } else it }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tc = if (selected) Palette.onInk else Palette.text
        if (icon != null) { GIcon(icon, tint = tc, size = 16.dp); HSpace(6.dp) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = tc)
    }
}

@Composable
fun IconBadge(icon: DrawableResource, size: Dp = 40.dp, bg: Color = Palette.surface2, tint: Color = Palette.text) {
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        GIcon(icon, tint = tint, size = size * 0.5f)
    }
}


// ---------- Buttons (Material 3 Expressive: Form wechselt beim Drücken) ----------

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: DrawableResource? = null, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapesFor(56.dp),
        modifier = modifier.fillMaxWidth().height(56.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Palette.ink, contentColor = Palette.onInk,
            disabledContainerColor = Palette.surface3, disabledContentColor = Palette.faint,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp),
    ) { ButtonContent(text, icon) }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: DrawableResource? = null, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapesFor(52.dp),
        modifier = modifier.fillMaxWidth().height(52.dp),
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.text),
        border = BorderStroke(1.dp, Palette.faint.copy(alpha = 0.6f)),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) { ButtonContent(text, icon) }
}

@Composable
fun QuietButton(text: String, modifier: Modifier = Modifier, color: Color = Palette.text, onClick: () -> Unit) {
    TextButton(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = modifier, colors = ButtonDefaults.textButtonColors(contentColor = color)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun RowScope.ButtonContent(text: String, icon: DrawableResource?) {
    if (icon != null) { GIcon(icon, tint = androidx.compose.material3.LocalContentColor.current, size = 20.dp); HSpace(10.dp) }
    Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp))
}

// ---------- Fortschritt & Diagramme ----------

@Composable
fun Bar(progress: Float, modifier: Modifier = Modifier, color: Color = Palette.ink, track: Color = Palette.surface2, height: Dp = 6.dp) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(700), label = "bar")
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(track)) {
        Box(Modifier.fillMaxWidth(p).height(height).clip(RoundedCornerShape(50)).background(color))
    }
}

/** Balken von 0–10 mit aktuellem Wert (gefüllt) und Potenzial (Markierung). */
@Composable
fun ScoreBar(score: Double, potential: Double?, modifier: Modifier = Modifier, color: Color = Palette.ink) {
    val p by animateFloatAsState((score / 10.0).toFloat().coerceIn(0f, 1f), tween(800), label = "score")
    val pot = potential?.let { (it / 10.0).toFloat().coerceIn(0f, 1f) }
    val track = Palette.surface2
    val brass = Palette.brass
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val h = size.height
        val r = h / 2
        drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
        if (pot != null && pot > p) {
            drawRoundRect(brass.copy(alpha = 0.28f), size = Size(size.width * pot, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
        }
        drawRoundRect(color, size = Size(size.width * p, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
        if (pot != null && pot > p) {
            val x = size.width * pot
            drawLine(brass, Offset(x, -2f), Offset(x, h + 2f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
fun ProgressRing(progress: Float, size: Dp, stroke: Dp = 6.dp, color: Color = Palette.ink, center: @Composable () -> Unit = {}) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(700), label = "ring")
    val track = Palette.surface2
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx()
            val inset = sw / 2
            val arc = Size(this.size.width - sw, this.size.height - sw)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arc, style = Stroke(sw, cap = StrokeCap.Round))
            if (p > 0f) drawArc(color, -90f, 360f * p, false, Offset(inset, inset), arc, style = Stroke(sw, cap = StrokeCap.Round))
        }
        center()
    }
}
