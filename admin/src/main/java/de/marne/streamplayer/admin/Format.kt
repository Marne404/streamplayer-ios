package de.marne.streamplayer.admin

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// Zahlen und Zeiten auf Deutsch: „1.234“, „3 Std 20 Min“, „1,2 GB“, „vor 5 Min“.

private val DE = Locale.GERMANY

fun count(n: Int): String = String.format(DE, "%,d", n)
fun count(n: Long): String = String.format(DE, "%,d", n)

/** Sehzeit: unter einer Stunde in Minuten, darüber in Stunden (ab 100 Std ohne Minuten). */
fun duration(ms: Long): String {
    val min = ms / 60_000
    val h = min / 60
    return when {
        min < 60 -> "$min Min"
        h >= 100 -> "${count(h)} Std"
        min % 60 == 0L -> "$h Std"
        else -> "$h Std ${min % 60} Min"
    }
}

/** Kurz für Diagramm-Achsen und enge Stellen: „45 Min“, „3,5 Std“. */
fun hours(ms: Long): String {
    val min = ms / 60_000.0
    return if (min < 60) "${min.roundToInt()} Min" else String.format(DE, "%.1f Std", min / 60).replace(",0 ", " ")
}

fun bytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024L * 1024 -> String.format(DE, "%.0f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(DE, "%.0f MB", b / 1024.0 / 1024)
    else -> String.format(DE, "%.1f GB", b / 1024.0 / 1024 / 1024)
}

fun percent(rate: Double?): String = when {
    rate == null -> "–"
    rate == 0.0 -> "0 %"
    rate < 0.1 -> String.format(DE, "%.1f %%", rate * 100)
    else -> "${(rate * 100).roundToInt()} %"
}

private val shortDay = DateTimeFormatter.ofPattern("d.M.", DE)
private val longDay = DateTimeFormatter.ofPattern("EE, d. MMMM", DE)

/** „11.11.“ aus „2026-11-11“; unlesbare Tage bleiben, wie sie sind. */
fun dayShort(iso: String): String = runCatching { LocalDate.parse(iso).format(shortDay) }.getOrDefault(iso)

/** „Mi., 11. November“, heute und gestern als Wort. */
fun dayLong(iso: String): String = runCatching {
    val d = LocalDate.parse(iso)
    when (d) {
        LocalDate.now() -> "Heute"
        LocalDate.now().minusDays(1) -> "Gestern"
        else -> d.format(longDay)
    }
}.getOrDefault(iso)

/** Wie lange etwas her ist: „gerade eben“, „vor 5 Min“, „vor 3 Std“, „gestern“, „vor 4 Tagen“, sonst Datum. */
fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0) return "nie"
    val min = (now - ms) / 60_000
    val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    val days = LocalDate.now().toEpochDay() - date.toEpochDay()
    return when {
        min < 2 -> "gerade eben"
        min < 60 -> "vor $min Min"
        days == 0L -> "vor ${min / 60} Std"
        days == 1L -> "gestern"
        days < 30 -> "vor $days Tagen"
        else -> date.format(DateTimeFormatter.ofPattern("d.M.yyyy", DE))
    }
}

/** Uhrzeit „14:32“ für „Stand …“. */
fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm", DE))

/** „1×“, „12×“ */
fun times(n: Int) = "${count(n)}×"
