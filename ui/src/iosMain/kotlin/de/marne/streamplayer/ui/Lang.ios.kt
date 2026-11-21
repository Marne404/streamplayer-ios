package de.marne.streamplayer.ui

import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

// „de-DE“ → „de“: die bevorzugte Sprache des iPads, nicht die Region.
internal actual fun deviceLanguage(): String =
    (NSLocale.preferredLanguages.firstOrNull() as? String)?.substringBefore('-') ?: "en"
