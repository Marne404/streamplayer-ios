package de.marne.streamplayer.browser

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.marne.streamplayer.detect.Candidate
import de.marne.streamplayer.detect.PageMeta
import de.marne.streamplayer.detect.UrlClassifier
import de.marne.streamplayer.detect.Variant
import de.marne.streamplayer.ui.Format
import de.marne.streamplayer.ui.design.GCard
import de.marne.streamplayer.ui.design.HSpace
import de.marne.streamplayer.ui.design.Ic
import de.marne.streamplayer.ui.design.OutlinePill
import de.marne.streamplayer.ui.design.Palette
import de.marne.streamplayer.ui.design.PrimaryButton
import de.marne.streamplayer.ui.design.QuietButton
import de.marne.streamplayer.ui.design.SheetHandle
import de.marne.streamplayer.ui.design.StatusLine
import de.marne.streamplayer.ui.design.Thumb
import de.marne.streamplayer.ui.design.VSpace

/** Titel für einen Fund: eigener Titel, sonst der der Seite, sonst der Dateiname. */
fun titleFor(c: Candidate, meta: PageMeta, pageTitle: String): String =
    c.title?.takeIf { it.isNotBlank() }
        ?: meta.title.takeIf { it.isNotBlank() }
        ?: pageTitle.takeIf { it.isNotBlank() }
        ?: UrlClassifier.fileName(c.url).ifBlank { "Video" }

@Composable
fun FoundSheet(
    found: List<Candidate>,
    meta: PageMeta,
    pageTitle: String,
    onDismiss: () -> Unit,
    onDownload: (Candidate, Variant?, String, String?) -> Unit,
    onPreview: (Candidate, String) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selected by rememberSaveable { mutableStateOf(found.firstOrNull { it.downloadable }?.url) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Palette.bg,
        contentColor = Palette.text,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
        tonalElevation = 0.dp,
        dragHandle = { SheetHandle() },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column {
                    Text("AUF DIESER SEITE", style = MaterialTheme.typography.labelSmall, color = Palette.muted)
                    VSpace(6.dp)
                    Text(
                        if (found.size == 1) "Ein Video gefunden" else "${found.size} Videos gefunden",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    VSpace(8.dp)
                }
            }
            items(found, key = { it.url }) { c ->
                val title = titleFor(c, meta, pageTitle)
                val thumb = c.thumbnail ?: meta.image
                CandidateCard(
                    c = c,
                    title = title,
                    thumb = thumb,
                    selected = c.url == selected,
                    onSelect = { selected = c.url },
                    onDownload = { v -> onDownload(c, v, title, thumb) },
                    onPreview = { onPreview(c, title) },
                )
            }
        }
    }
}

@Composable
private fun CandidateCard(
    c: Candidate,
    title: String,
    thumb: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    onDownload: (Variant?) -> Unit,
    onPreview: () -> Unit,
) {
    GCard(padding = 18.dp, onClick = if (!selected && c.downloadable) onSelect else null) {
        Column(Modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(thumb, Modifier.size(width = 76.dp, height = 48.dp))
                HSpace(14.dp)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    VSpace(2.dp)
                    Text(Format.candidateMeta(c), style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1)
                }
            }
            when {
                c.drm -> {
                    VSpace(14.dp)
                    StatusLine(Ic.shield, "Geschützt (DRM). Lässt sich nicht laden.", Palette.wine)
                }
                c.live -> {
                    VSpace(14.dp)
                    StatusLine(Ic.info, "Livestream. Lässt sich nicht laden.", Palette.muted)
                }
                selected -> {
                    var variant by remember(c.url) { mutableStateOf(c.variants.firstOrNull()) }
                    if (c.variants.size > 1) {
                        VSpace(16.dp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            c.variants.forEach { v ->
                                OutlinePill(v.label, selected = v == variant, onClick = { variant = v })
                            }
                        }
                    }
                    VSpace(16.dp)
                    val exact = c.sizeBytes
                    val estimate = variant?.estimatedBytes
                    val label = when {
                        exact != null -> "Laden · ${Format.bytes(exact)}"
                        estimate != null -> "Laden · ca. ${Format.bytes(estimate)}"
                        else -> "Laden"
                    }
                    PrimaryButton(label, icon = Ic.download) { onDownload(variant) }
                    QuietButton("Vorher ansehen", color = Palette.muted, modifier = Modifier.align(Alignment.CenterHorizontally)) { onPreview() }
                }
            }
        }
    }
}
