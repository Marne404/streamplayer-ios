package de.marne.streamplayer.account

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import de.marne.streamplayer.library.Library
import de.marne.streamplayer.library.LibraryStore
import de.marne.streamplayer.ui.tr
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom

/**
 * Bibliotheken mit Freunden teilen – nur zum Ansehen. Aufbau im Konto (Regeln: firebase/firestore.rules):
 *
 *   shared/{libraryId}                  { owner, ownerName, name, color, updatedAt }
 *   shared/{libraryId}/titles/{titleId} { json, updatedAt, deleted }   – spiegelt CloudSync
 *   shared/{libraryId}/members/{uid}    { code, name, joinedAt }       – wer beigetreten ist
 *   invites/{code}                      { shareId, owner, createdAt }  – Einladung, Code = Dokument-ID
 *
 * Beitreten heißt: mit dem Code das eigene Mitglieds-Dokument anlegen. Die Regeln prüfen, dass
 * der Code zu genau dieser Bibliothek gehört – wer den Code nicht kennt, kommt nicht hinein.
 */
class Sharing(private val cloud: Cloud, private val library: LibraryStore) {

    data class Member(val uid: String, val name: String, val joinedAt: Long)

    class ShareException(message: String) : Exception(message)

    private val db: FirebaseFirestore get() = cloud.db ?: throw ShareException(tr("Konto ist in dieser Version nicht eingerichtet.", "Accounts aren't set up in this version."))
    private fun me(): Cloud.User = cloud.user.value ?: throw ShareException(tr("Zum Teilen erst bei einem Konto anmelden.", "Sign in to an account to share."))
    private fun myName(u: Cloud.User) = u.name?.takeIf { it.isNotBlank() } ?: u.email?.substringBefore('@') ?: tr("Ein Freund", "A friend")

    /**
     * Teilen einschalten (oder neue Einladung): legt die geteilte Bibliothek an bzw. aktualisiert
     * Name und Farbe und gibt einen gültigen Einladungscode zurück. Die Titel spiegelt dann CloudSync.
     */
    suspend fun invite(l: Library): String {
        val u = me()
        require(!l.joined && l.id != Library.MAIN)
        db.collection("shared").document(l.id).set(mapOf(
            "owner" to u.uid, "ownerName" to myName(u), "name" to l.label, "color" to l.color,
            "updatedAt" to FieldValue.serverTimestamp(),
        )).await()
        // Den gemerkten Code nur weitergeben, wenn es die Einladung am Server wirklich noch gibt –
        // sonst bekäme der Freund einen Code, der nirgends hinführt.
        val known = l.invite?.takeIf { c ->
            val snap = try { db.collection("invites").document(c).get(Source.SERVER).await() } catch (e: FirebaseFirestoreException) { null }
            snap?.let { it.exists() && it.getString("shareId") == l.id } ?: true // ohne Netz: nicht wegwerfen
        }
        val code = known ?: newCode().also { c ->
            db.collection("invites").document(c).set(mapOf("shareId" to l.id, "owner" to u.uid, "createdAt" to FieldValue.serverTimestamp())).await()
        }
        library.setLibrary(l.copy(shared = true, invite = code))
        return code
    }

    /** Name oder Farbe geändert: auch bei den Freunden. */
    suspend fun updateMeta(l: Library) {
        if (!l.shared) return
        val u = me()
        db.collection("shared").document(l.id).set(mapOf(
            "owner" to u.uid, "ownerName" to myName(u), "name" to l.label, "color" to l.color,
            "updatedAt" to FieldValue.serverTimestamp(),
        )).await()
    }

    suspend fun members(l: Library): List<Member> =
        db.collection("shared").document(l.id).collection("members").get().await().documents.map {
            Member(it.id, it.getString("name") ?: tr("Ein Freund", "A friend"), it.getTimestamp("joinedAt")?.toDate()?.time ?: 0)
        }.sortedBy { it.joinedAt }

    suspend fun removeMember(l: Library, uid: String) {
        db.collection("shared").document(l.id).collection("members").document(uid).delete().await()
    }

    /**
     * Teilen beenden: Einladung, Mitglieder, gespiegelte Titel und die geteilte Bibliothek löschen –
     * in dieser Reihenfolge, denn die Regeln für die Unterlagen fragen das Hauptdokument.
     * Bei Freunden verschwindet die Bibliothek beim nächsten Abgleich.
     */
    suspend fun stop(l: Library) {
        me()
        val root = db.collection("shared").document(l.id)
        l.invite?.let { runCatching { db.collection("invites").document(it).delete().await() } }
        for (sub in listOf("members", "titles")) {
            val docs = root.collection(sub).get().await().documents
            docs.chunked(400).forEach { part -> db.batch().apply { part.forEach { delete(it.reference) } }.commit().await() }
        }
        root.delete().await()
        library.setLibrary(l.copy(shared = false, invite = null))
    }

    /**
     * Einladung einlösen: Rückgabe ist die neue (nur lesbare) Bibliothek.
     *
     * Anlass (1.13.2): Jeder Fehler hieß „Diese Einladung gibt es nicht (mehr)“ – auch ohne Netz
     * oder bei einem Tippfehler in Groß/Klein. Jetzt sagt die Meldung, was wirklich los ist.
     */
    suspend fun join(code0: String): Library {
        val u = me()
        val code = code0.trim()
        // Neue Codes sind nur Großbuchstaben: Wer sie klein abtippt, kommt trotzdem an.
        val invite = lookup(code)?.takeIf { it.exists() }
            ?: code.uppercase().takeIf { it != code }?.let { lookup(it) }?.takeIf { it.exists() }
            ?: throw ShareException(tr(
                "Den Code „${code.take(6)}…“ gibt es nicht. Groß- und Kleinschreibung zählen – am besten die ganze Nachricht einfügen. Sonst um eine neue Einladung bitten.",
                "The code “${code.take(6)}…” doesn't exist. Upper and lower case matter – best paste the whole message. Otherwise ask for a new invite.",
            ))
        val sid = invite.getString("shareId") ?: throw ShareException(tr("Diese Einladung ist kaputt – bitte um eine neue.", "This invite is broken – ask for a new one."))
        if (invite.getString("owner") == u.uid) throw ShareException(tr("Das ist deine eigene Bibliothek.", "That's your own library."))
        val member = db.collection("shared").document(sid).collection("members").document(u.uid)
        // Schon Mitglied (etwa nach Neuinstallation): nicht noch einmal anlegen – das wäre ein Ändern, und das dürfen nur Neue nicht.
        val already = try { member.get(Source.SERVER).await().exists() } catch (e: FirebaseFirestoreException) { false }
        if (!already) firestore {
            member.set(mapOf("code" to invite.id, "name" to myName(u), "joinedAt" to FieldValue.serverTimestamp())).await()
        }
        val meta = firestore { db.collection("shared").document(sid).get().await() }
        if (!meta.exists()) throw ShareException(tr("Dein Freund teilt diese Bibliothek nicht mehr.", "Your friend no longer shares this library."))
        val l = Library(
            id = sid,
            name = meta.getString("name") ?: tr("Geteilt", "Shared"),
            color = (meta.getLong("color") ?: 0).toInt(),
            joined = true,
            owner = meta.getString("ownerName"),
        )
        library.setLibrary(l)
        return l
    }

    /** Einladung am Server nachschlagen (nicht aus dem Zwischenspeicher – der kennt sie nicht). */
    private suspend fun lookup(code: String): DocumentSnapshot? {
        if (code.isEmpty() || '/' in code) return null
        return firestore { db.collection("invites").document(code).get(Source.SERVER).await() }
    }

    /** Firestore-Fehler in verständliche Sätze übersetzen. */
    private suspend fun <T> firestore(block: suspend () -> T): T = try {
        block()
    } catch (e: FirebaseFirestoreException) {
        throw ShareException(when (e.code) {
            FirebaseFirestoreException.Code.UNAVAILABLE, FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ->
                tr("Keine Verbindung zum Server. Mit Internet nochmal versuchen.", "Can't reach the server. Try again with internet.")
            FirebaseFirestoreException.Code.PERMISSION_DENIED, FirebaseFirestoreException.Code.UNAUTHENTICATED ->
                tr("Der Server lässt dich nicht beitreten. Einmal vom Konto ab- und wieder anmelden, dann nochmal.", "The server won't let you join. Sign out of your account and back in, then try again.")
            else -> tr("Hat nicht geklappt (${e.code}).", "That didn't work (${e.code}).")
        })
    }

    /** Freundes-Bibliothek verlassen. Auch ohne Netz: lokal ist sie sofort weg. */
    suspend fun leave(l: Library) {
        library.removeJoined(l.id)
        val u = cloud.user.value ?: return
        runCatching { db.collection("shared").document(l.id).collection("members").document(u.uid).delete().await() }
    }

    companion object {
        /** Alle Zeichen, die in Codes vorkommen können – alte Codes hatten auch Kleinbuchstaben. */
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        /** Neue Codes: nur Großbuchstaben und Ziffern, ohne Verwechsler (0/O, 1/I). */
        private const val NEW_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        /** 22 Zeichen (110 Bit): nicht zu erraten, aber abtippbar – Groß/Klein egal. */
        fun newCode(): String {
            val r = SecureRandom()
            return (1..22).map { NEW_ALPHABET[r.nextInt(NEW_ALPHABET.length)] }.joinToString("")
        }

        private val codeRe = Regex("""(?:streamplayer://join/)?([A-Za-z2-9]{22})""")

        /** Code aus einem eingefügten Text (ganze Nachricht, Link oder nur der Code). */
        fun codeIn(text: String): String? =
            Regex("""streamplayer://join/([A-Za-z0-9]{22})""").find(text)?.groupValues?.get(1)
                ?: codeRe.findAll(text).map { it.groupValues[1] }.firstOrNull { c -> c.all { it in ALPHABET } }
                // Von Hand abgetippt, mit Leerzeichen oder Bindestrichen dazwischen.
                ?: text.filter { it.isLetterOrDigit() }.takeIf { it.length == 22 && it.all { c -> c in ALPHABET || c.uppercaseChar() in NEW_ALPHABET } }

        fun inviteText(l: Library, code: String) = tr(
            "Ich teile meine Streamplayer-Bibliothek „${l.label}“ mit dir. Öffnen: streamplayer://join/$code\n" +
                "Oder in Streamplayer: Bibliotheken → Einladung einlösen, Code: $code",
            "I'm sharing my Streamplayer library “${l.label}” with you. Open: streamplayer://join/$code\n" +
                "Or in Streamplayer: Libraries → Redeem invite, code: $code",
        )
    }
}
