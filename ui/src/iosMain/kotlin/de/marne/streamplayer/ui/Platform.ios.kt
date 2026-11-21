package de.marne.streamplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCanonicalMapping

@Composable
actual fun screenHeightDp(): Int = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value.toInt() }

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun decomposeAccents(s: String): String = (s as NSString).decomposedStringWithCanonicalMapping
