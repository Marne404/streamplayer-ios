package de.marne.streamplayer.ui

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Jetzt in Millisekunden seit 1970 – wie System.currentTimeMillis(), auch auf dem iPad. */
@OptIn(ExperimentalTime::class)
fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
