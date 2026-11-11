package de.marne.streamplayer.admin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.ui.design.HSpace
import de.marne.streamplayer.ui.design.OutlinePill
import de.marne.streamplayer.ui.design.Palette
import kotlin.math.max
import kotlin.math.min

/*
 * Diagramme ohne Bibliothek, schlicht wie der Rest von Ivory & Ink: eine Reihe in einer Farbe (der
 * Titel darüber sagt, was sie ist – keine Legende), schmale Säulen mit runden Enden, nur zwei blasse
 * Hilfslinien. Antippen oder Wischen zeigt den Wert des Tages oben im Kopf.
 */

/**
 * Säulen je Tag. [values] null = an dem Tag gibt es keinen Wert (z. B. Fehlerquote ohne beendete
 * Downloads) – das ist etwas anderes als 0 und bleibt darum leer. [summary] steht im Kopf, solange
 * kein Tag gewählt ist.
 */
@Composable
fun DayBars(
    values: List<Double?>,
    days: List<String>,
    format: (Double) -> String,
    summary: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.ink,
    height: Dp = 140.dp,
) {
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    val grid = Palette.line
    val today = Palette.brass
    Column(modifier.fillMaxWidth()) {
        // Kopf: der gewählte Tag oder die Zusammenfassung – feste Höhe, damit nichts springt.
        Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
            val i = selected
            if (i != null && i in values.indices) {
                Text(dayLong(days.getOrElse(i) { "" }), style = MaterialTheme.typography.labelLarge, color = Palette.muted)
                HSpace(8.dp)
                Text(values[i]?.let(format) ?: "kein Wert", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Palette.text)
            } else {
                Text(summary, style = MaterialTheme.typography.labelLarge, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(10.dp))
        val top = values.filterNotNull().maxOrNull() ?: 0.0
        if (top <= 0.0) {
            Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
                Text("Keine Daten in diesem Zeitraum", style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
            }
            return@Column
        }
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .pointerInput(values) {
                    fun index(x: Float) = (x / size.width * values.size).toInt().coerceIn(0, values.size - 1)
                    detectTapGestures { p -> selected = index(p.x).takeIf { it != selected } }
                }
                .pointerInput(values) {
                    fun index(x: Float) = (x / size.width * values.size).toInt().coerceIn(0, values.size - 1)
                    detectHorizontalDragGestures { change, _ -> selected = index(change.position.x) }
                },
        ) {
            val n = values.size
            val slot = size.width / n
            val barW = max(1f, min(slot - 3.dp.toPx(), 14.dp.toPx()))
            val inset = (slot - barW) / 2
            val r = CornerRadius(min(4.dp.toPx(), barW / 2))
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            values.forEachIndexed { i, v ->
                if (v == null || v <= 0.0) return@forEachIndexed
                val h = max(2.dp.toPx(), (v / top * size.height).toFloat())
                val x = i * slot + inset
                // Der letzte Tag ist heute und noch nicht vorbei: Messing statt Tinte.
                val base = if (i == n - 1) today else color
                val c = if (selected != null && selected != i) base.copy(alpha = 0.3f) else base
                val rect = RoundRect(
                    left = x, top = size.height - h, right = x + barW, bottom = size.height,
                    topLeftCornerRadius = r, topRightCornerRadius = r, bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero,
                )
                drawPath(Path().apply { addRoundRect(rect) }, c)
            }
        }
        // Achse: erster, mittlerer und letzter Tag.
        if (days.size >= 2) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                val style = MaterialTheme.typography.labelMedium
                Text(dayShort(days.first()), style = style, color = Palette.faint)
                Spacer(Modifier.weight(1f))
                Text(dayShort(days[days.size / 2]), style = style, color = Palette.faint)
                Spacer(Modifier.weight(1f))
                Text("heute", style = style, color = Palette.faint)
            }
        }
    }
}

/** Eine Kennzahl für das Diagramm: Name, Wert je Tag, Format für einen Wert, Zusammenfassung. */
class Metric<T>(
    val label: String,
    val value: (T) -> Double?,
    val format: (Double) -> String,
    val summary: (List<T>) -> String,
)

/** Pillen zur Wahl der Kennzahl, darunter das Diagramm. */
@Composable
fun <T> MetricChart(metrics: List<Metric<T>>, rows: List<T>, days: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val m = metrics[selected.coerceIn(0, metrics.size - 1)]
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            metrics.forEachIndexed { i, x -> OutlinePill(x.label, selected = i == selected, onClick = { onSelect(i) }) }
        }
        Spacer(Modifier.height(18.dp))
        DayBars(rows.map(m.value), days, m.format, m.summary(rows))
    }
}
