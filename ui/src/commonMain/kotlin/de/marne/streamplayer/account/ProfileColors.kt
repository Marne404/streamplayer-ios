package de.marne.streamplayer.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import de.marne.streamplayer.ui.design.Serif

/** Gedeckte Töne aus Ivory & Ink: Messing, Grün, Wein, Blau, Pflaume, Graphit. */
val ProfileColors = listOf(
    Color(0xFF8E6F43), Color(0xFF34503F), Color(0xFF7A2E2E),
    Color(0xFF3B4D63), Color(0xFF5E4B6E), Color(0xFF5B5750),
)
val OnProfile = Color(0xFFF4F1EA)

/** Rundes Profilbild: Anfangsbuchstabe auf der Profilfarbe. */
@Composable
fun ProfileAvatar(p: Profile, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(ProfileColors[p.color.mod(ProfileColors.size)]),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            p.name.trim().take(1).uppercase().ifEmpty { "?" },
            color = OnProfile,
            fontFamily = Serif,
            fontSize = (size.value * 0.46f).sp,
            lineHeight = (size.value * 0.5f).sp,
        )
    }
}
