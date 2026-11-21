package de.marne.streamplayer.ui

import java.util.Locale

internal actual fun deviceLanguage(): String = Locale.getDefault().language
