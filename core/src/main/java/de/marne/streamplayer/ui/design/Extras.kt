// Ivory & Ink – Bausteine, die das Design-System beschreibt (README, bundle.css),
// für die es aber noch keinen Compose-Code gab: Segment-Umschalter, Hinweiskarte, Vorschaubild, Menü.
package de.marne.streamplayer.ui.design

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage

/** Segment-Umschalter (.ii-seg): gewählt = ink, beim Wählen wird die Pill etwas eckiger. */
@Composable
fun Segmented(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val isSel = value == selected
            val radius by animateDpAsState(if (isSel) 14.dp else height / 2, MaterialTheme.motionScheme.fastSpatialSpec(), label = "seg")
            val shape = RoundedCornerShape(radius)
            Box(
                Modifier
                    .weight(1f)
                    .height(height)
                    .clip(shape)
                    .background(if (isSel) Palette.ink else Palette.surface2)
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (isSel) Palette.onInk else Palette.text)
            }
        }
    }
}

/** Hinweiskarte: Icon im Kreis, Titel, eine Zeile, rechts die Aktion als Wort. Höchstens eine pro Screen. */
@Composable
fun NoticeCard(
    @DrawableRes icon: Int,
    title: String,
    text: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    GCard(padding = 18.dp, onClick = onAction) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon)
            HSpace(16.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (action != null) {
                HSpace(12.dp)
                Text(action, style = MaterialTheme.typography.labelLarge, color = Palette.text)
            }
        }
    }
}

/** Vorschaubild mit ruhigem Platzhalter (surface-2, Film-Icon in faint). */
@Composable
fun Thumb(model: Any?, modifier: Modifier = Modifier, radius: Dp = 12.dp) {
    val shape = RoundedCornerShape(radius)
    Box(modifier.clip(shape).background(Palette.surface2), contentAlignment = Alignment.Center) {
        if (model == null) {
            GIcon(Ic.movie, tint = Palette.faint, size = 20.dp)
        } else {
            SubcomposeAsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { GIcon(Ic.movie, tint = Palette.faint, size = 20.dp) } },
                loading = { Box(Modifier.fillMaxSize().background(Palette.surface2)) },
            )
        }
    }
}

/** Zeile mit Status-Icon und einer kurzen Aussage, z. B. ein Fehler unter dem auslösenden Element. */
@Composable
fun StatusLine(@DrawableRes icon: Int, text: String, color: Color = Palette.wine) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GIcon(icon, tint = color, size = 16.dp)
        HSpace(6.dp)
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

data class MenuAction(val label: String, val destructive: Boolean = false, val onClick: () -> Unit)

/** Drei Punkte mit Menü. Fläche und Haarlinie statt Schatten. */
@Composable
fun RowMenu(actions: List<MenuAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { GIcon(Ic.more, tint = Palette.muted, size = 20.dp) }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = Palette.surface,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            border = BorderStroke(1.dp, Palette.line),
        ) {
            actions.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.label, style = MaterialTheme.typography.bodyLarge, color = if (a.destructive) Palette.wine else Palette.text) },
                    onClick = { open = false; a.onClick() },
                )
            }
        }
    }
}

@Composable
fun IvorySwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Palette.onInk,
            checkedTrackColor = Palette.ink,
            checkedBorderColor = Palette.ink,
            uncheckedThumbColor = Palette.faint,
            uncheckedTrackColor = Palette.surface2,
            uncheckedBorderColor = Palette.faint,
        ),
    )
}

/** Kleiner Griff oben am Sheet. */
@Composable
fun SheetHandle() {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.height(4.dp).fillMaxWidth(0.1f).clip(RoundedCornerShape(50)).background(Palette.surface3))
    }
}

/** Textfeld in Ivory & Ink: radius-field, Haarlinie, Fokus in text. */
@Composable
fun IvoryField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    type: KeyboardType = KeyboardType.Text,
    label: String? = null,
    singleLine: Boolean = true,
    password: Boolean = false,
) {
    OutlinedTextField(
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        label = label?.let { { Text(it) } },
        placeholder = { Text(placeholder, color = Palette.muted) },
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (password) KeyboardType.Password else type, autoCorrectEnabled = false),
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.text,
            unfocusedBorderColor = Palette.line,
            focusedLabelColor = Palette.text,
            unfocusedLabelColor = Palette.muted,
            cursorColor = Palette.text,
            focusedTextColor = Palette.text,
            unfocusedTextColor = Palette.text,
            focusedContainerColor = Palette.surface,
            unfocusedContainerColor = Palette.surface,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
