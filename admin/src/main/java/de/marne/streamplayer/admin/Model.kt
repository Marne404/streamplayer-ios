package de.marne.streamplayer.admin

import org.json.JSONArray
import org.json.JSONObject

// Die Antworten von stats/src/admin.js als Kotlin-Klassen. Fehlende Felder werden 0 bzw. leer –
// ein älterer Worker soll die App nicht abstürzen lassen.

/** Summen eines Tages, entweder über alle Installationen (overview, active gefüllt) oder für eine. */
data class Day(
    val day: String,
    val active: Int,
    val sessions: Int,
    val watchMs: Long,
    val plays: Int,
    val dlStarted: Int,
    val dlDone: Int,
    val dlFailed: Int,
    val findFailed: Int,
    val crashes: Int,
)

data class VersionShare(val version: String, val versionCode: Int, val installs: Int)

data class Overview(
    val activeToday: Int,
    val active7: Int,
    val active30: Int,
    val watch7: Long,
    val watch30: Long,
    val plays7: Int,
    val plays30: Int,
    val installs: Int,
    val new7: Int,
    val withAccount: Int,
    val withoutAccount: Int,
    val distinctAccounts: Int,
    val days: List<Day>,
    val versions: List<VersionShare>,
)

data class Counts(val titles: Int, val libraries: Int, val profiles: Int, val downloadsStored: Int, val bytesStored: Long)

data class Install(
    val install: String,
    /** null = nicht angemeldet. */
    val accountName: String?,
    val version: String,
    val versionCode: Int,
    val sdk: Int,
    val lang: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val counts: Counts,
    val watch7: Long,
    val sessions7: Int,
) {
    /** Kontoname, sonst der Anfang der Installations-ID. */
    val label: String get() = accountName?.takeIf { it.isNotBlank() } ?: "Installation ${install.take(8)}"
}

data class DomainCount(val domain: String, val count: Int, val installs: Int, val days: Int, val last: String)

data class Crash(val sig: String, val count: Int, val installs: Int, val last: String, val versions: List<String>)

data class UserDetail(val user: Install, val days: List<Day>, val domains: List<DomainCount>, val crashes: List<Crash>)

data class DownloadDay(val day: String, val started: Int, val done: Int, val failed: Int, val rate: Double?)

data class Problems(
    val domains: List<DomainCount>,
    val crashes: List<Crash>,
    val dlDone: Int,
    val dlFailed: Int,
    val dlRate: Double?,
    val dlDays: List<DownloadDay>,
)

// ------------------------------------------------------------------------------------- Lesen

private inline fun <T> JSONArray?.list(map: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it)?.let(map) }

private fun JSONObject.rate(key: String): Double? = if (isNull(key) || !has(key)) null else optDouble(key).takeIf { !it.isNaN() }

private fun day(o: JSONObject) = Day(
    day = o.optString("day"), active = o.optInt("active"), sessions = o.optInt("sessions"), watchMs = o.optLong("watchMs"),
    plays = o.optInt("plays"), dlStarted = o.optInt("dlStarted"), dlDone = o.optInt("dlDone"), dlFailed = o.optInt("dlFailed"),
    findFailed = o.optInt("findFailed"), crashes = o.optInt("crashes"),
)

private fun install(o: JSONObject): Install {
    val c = o.optJSONObject("counts") ?: JSONObject()
    val acc = o.optJSONObject("account")
    return Install(
        install = o.optString("install"), accountName = acc?.optString("name"), version = o.optString("version"),
        versionCode = o.optInt("versionCode"), sdk = o.optInt("sdk"), lang = o.optString("lang"),
        firstSeen = o.optLong("firstSeen"), lastSeen = o.optLong("lastSeen"),
        counts = Counts(c.optInt("titles"), c.optInt("libraries"), c.optInt("profiles"), c.optInt("downloadsStored"), c.optLong("bytesStored")),
        watch7 = o.optLong("watchMs7"), sessions7 = o.optInt("sessions7"),
    )
}

private fun domain(o: JSONObject) = DomainCount(o.optString("domain"), o.optInt("count"), o.optInt("installs"), o.optInt("days"), o.optString("last"))

private fun crash(o: JSONObject): Crash {
    val v = o.optJSONArray("versions")
    return Crash(o.optString("sig"), o.optInt("count"), o.optInt("installs"), o.optString("last"),
        if (v == null) emptyList() else (0 until v.length()).map { v.optString(it) })
}

fun parseOverview(o: JSONObject): Overview {
    val a = o.optJSONObject("active") ?: JSONObject()
    val w = o.optJSONObject("watchMs") ?: JSONObject()
    val p = o.optJSONObject("plays") ?: JSONObject()
    val i = o.optJSONObject("installs") ?: JSONObject()
    val acc = o.optJSONObject("accounts") ?: JSONObject()
    return Overview(
        activeToday = a.optInt("today"), active7 = a.optInt("d7"), active30 = a.optInt("d30"),
        watch7 = w.optLong("d7"), watch30 = w.optLong("d30"), plays7 = p.optInt("d7"), plays30 = p.optInt("d30"),
        installs = i.optInt("total"), new7 = i.optInt("new7"),
        withAccount = acc.optInt("withAccount"), withoutAccount = acc.optInt("without"), distinctAccounts = acc.optInt("distinct"),
        days = o.optJSONArray("days").list(::day),
        versions = o.optJSONArray("versions").list { VersionShare(it.optString("version"), it.optInt("versionCode"), it.optInt("installs")) },
    )
}

fun parseUsers(o: JSONObject): List<Install> = o.optJSONArray("users").list(::install)

fun parseUser(o: JSONObject) = UserDetail(
    user = install(o.optJSONObject("user") ?: JSONObject()),
    days = o.optJSONArray("days").list(::day),
    domains = o.optJSONArray("domains").list(::domain),
    crashes = o.optJSONArray("crashes").list(::crash),
)

fun parseProblems(o: JSONObject): Problems {
    val d = o.optJSONObject("downloads") ?: JSONObject()
    return Problems(
        domains = o.optJSONArray("domains").list(::domain),
        crashes = o.optJSONArray("crashes").list(::crash),
        dlDone = d.optInt("done"), dlFailed = d.optInt("failed"), dlRate = d.rate("rate"),
        dlDays = d.optJSONArray("days").list { DownloadDay(it.optString("day"), it.optInt("started"), it.optInt("done"), it.optInt("failed"), it.rate("rate")) },
    )
}
