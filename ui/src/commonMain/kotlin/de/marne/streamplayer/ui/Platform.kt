package de.marne.streamplayer.ui

import androidx.compose.runtime.Composable
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Höhe des Bildschirms in dp (Android: Configuration.screenHeightDp). */
@Composable
expect fun screenHeightDp(): Int

/** Breite des Bildschirms in dp (Android: Configuration.screenWidthDp). */
@Composable
expect fun screenWidthDp(): Int

/** Unicode-Zerlegung (NFD): „é“ → „e“ + Akzent, damit die Suche Akzente ignorieren kann. */
expect fun decomposeAccents(s: String): String

/** Aktuelle Stunde in der Zeitzone des Geräts (0–23). */
@OptIn(ExperimentalTime::class)
fun localHour(): Int = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour

/** Tag seit 1970 in der Zeitzone des Geräts – wie früher LocalDate.toEpochDay(). */
@OptIn(ExperimentalTime::class)
fun epochDay(ms: Long): Long =
    Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault()).date.toEpochDays().toLong()
