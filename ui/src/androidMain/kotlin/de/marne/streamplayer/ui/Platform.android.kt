package de.marne.streamplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.text.Normalizer

@Composable
actual fun screenHeightDp(): Int = LocalConfiguration.current.screenHeightDp

actual fun decomposeAccents(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
